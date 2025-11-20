#!/bin/bash
# Comprehensive benchmark matrix for eru-http server
# Tests combinations of GC, connections, heap size, and semaphore limits

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test configuration
TEST_DURATION=${TEST_DURATION:-120}  # seconds
TEST_THREADS=${TEST_THREADS:-12}     # wrk threads
WARMUP_TIME=${WARMUP_TIME:-10}       # seconds
RESULTS_DIR=${RESULTS_DIR:-/tmp/eru-http-benchmarks}

# Create results directory
mkdir -p "$RESULTS_DIR"

# Function to kill all benchmark servers
kill_servers() {
    echo -e "${YELLOW}Killing all benchmark servers...${NC}"
    pkill -9 -f "net.ghoula.eru.http.server.BenchmarkServer" 2>/dev/null || true
    sleep 2
}

# Function to wait for server startup
wait_for_server() {
    local port=$1
    local max_wait=30
    local waited=0

    echo -ne "${YELLOW}Waiting for server on port $port...${NC}"
    while ! curl -sf http://localhost:$port/plaintext >/dev/null 2>&1; do
        if [ $waited -ge $max_wait ]; then
            echo -e " ${RED}TIMEOUT${NC}"
            return 1
        fi
        sleep 1
        waited=$((waited + 1))
        echo -n "."
    done
    echo -e " ${GREEN}OK${NC}"
    return 0
}

# Function to check if server is still running
is_server_running() {
    jps | grep -q BenchmarkServer
}

# Function to run a single benchmark test
run_benchmark() {
    local gc_type=$1
    local heap_size=$2
    local connections=$3
    local semaphore=$4
    local test_id="${gc_type}_heap${heap_size}_c${connections}_sem${semaphore}"

    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Test: $test_id${NC}"
    echo -e "${GREEN}GC: $gc_type, Heap: $heap_size, Connections: $connections, Semaphore: $semaphore${NC}"
    echo -e "${GREEN}========================================${NC}"

    # Kill any existing servers
    kill_servers

    # Map GC type to environment variable format
    local gc_env=""
    case "$gc_type" in
        "ParallelGC")
            gc_env="parallel"
            ;;
        "G1GC")
            gc_env="g1"
            ;;
        "ZGC-Gen")
            gc_env="zgc-gen"
            ;;
        "ZGC")
            gc_env="zgc"
            ;;
        *)
            echo -e "${RED}Unknown GC type: $gc_type${NC}"
            return 1
            ;;
    esac

    # Start server with specific configuration via environment variables
    local server_log="$RESULTS_DIR/${test_id}_server.log"

    echo "GC Type: $gc_type ($gc_env)"
    echo "Heap Size: $heap_size"
    echo "Max Connections: $semaphore"
    echo "Server log: $server_log"

    # Set environment variables for build.sbt to pick up
    GC_TYPE=$gc_env HEAP_SIZE=$heap_size \
        sbt -J-Xms2g -J-Xmx2g \
        -Deru.http.maxConnections=$semaphore \
        "project server" \
        "Test/runMain net.ghoula.eru.http.server.BenchmarkServer 8080" \
        > "$server_log" 2>&1 &

    local server_pid=$!
    echo "Server PID: $server_pid"

    # Wait for server to start
    if ! wait_for_server 8080; then
        echo -e "${RED}Server failed to start${NC}"
        echo "Server log tail:"
        tail -50 "$server_log"
        return 1
    fi

    # Warmup
    echo -e "${YELLOW}Warming up for ${WARMUP_TIME}s...${NC}"
    wrk -t4 -c100 -d${WARMUP_TIME}s http://localhost:8080/ >/dev/null 2>&1 || true
    sleep 2

    # Run benchmark
    local wrk_log="$RESULTS_DIR/${test_id}_wrk.txt"
    echo -e "${YELLOW}Running benchmark (${TEST_DURATION}s)...${NC}"
    wrk -t${TEST_THREADS} -c${connections} -d${TEST_DURATION}s http://localhost:8080/ 2>&1 | tee "$wrk_log"

    # Check if server is still running
    local server_status="RUNNING"
    if ! is_server_running; then
        server_status="CRASHED"
        echo -e "${RED}Server crashed during test!${NC}"
    else
        echo -e "${GREEN}Server still running${NC}"
    fi

    # Collect JVM stats if server is running
    if [ "$server_status" = "RUNNING" ]; then
        local stats_log="$RESULTS_DIR/${test_id}_stats.txt"
        echo "=== JVM Stats ===" > "$stats_log"
        jps | grep BenchmarkServer | awk '{print $1}' | xargs -I {} jcmd {} VM.flags >> "$stats_log" 2>&1 || true
        echo "" >> "$stats_log"
        jps | grep BenchmarkServer | awk '{print $1}' | xargs -I {} jcmd {} GC.heap_info >> "$stats_log" 2>&1 || true
    fi

    # Extract key metrics
    local throughput=$(grep "Requests/sec:" "$wrk_log" | awk '{print $2}')
    local avg_latency=$(grep "Latency" "$wrk_log" | awk '{print $2}')
    local timeouts=$(grep "timeout" "$wrk_log" | awk '{print $8}')

    # Append to summary
    echo "$test_id,$gc_type,$heap_size,$connections,$semaphore,$throughput,$avg_latency,$timeouts,$server_status" >> "$RESULTS_DIR/summary.csv"

    echo -e "${GREEN}Test completed: $throughput req/s, $avg_latency latency, status: $server_status${NC}"

    # Kill server
    kill_servers
    sleep 3

    return 0
}

# Initialize summary CSV
echo "test_id,gc_type,heap_size,connections,semaphore,throughput_rps,avg_latency,timeouts,status" > "$RESULTS_DIR/summary.csv"

echo -e "${GREEN}Starting Benchmark Matrix${NC}"
echo -e "${GREEN}Results will be saved to: $RESULTS_DIR${NC}"
echo ""

# Phase 1: GC Comparison at Current Settings (8GB, 400 connections)
echo -e "${YELLOW}=== PHASE 1: GC Comparison (8GB, 400 connections) ===${NC}"
run_benchmark "ParallelGC" "8g" 400 1024
run_benchmark "G1GC" "8g" 400 1024
run_benchmark "ZGC-Gen" "8g" 400 1024

# Phase 2: Connection Scaling with ParallelGC
echo -e "${YELLOW}=== PHASE 2: Connection Scaling with ParallelGC ===${NC}"
run_benchmark "ParallelGC" "8g" 256 1024
run_benchmark "ParallelGC" "8g" 1024 8192
run_benchmark "ParallelGC" "8g" 4096 8192
run_benchmark "ParallelGC" "8g" 16384 32768

# Phase 3: Memory Efficiency Study (1024 connections)
echo -e "${YELLOW}=== PHASE 3: Memory Efficiency (ParallelGC, 1024 connections) ===${NC}"
run_benchmark "ParallelGC" "2g" 1024 8192
run_benchmark "ParallelGC" "4g" 1024 8192
run_benchmark "ParallelGC" "8g" 1024 8192

# Phase 4: GC Comparison at High Concurrency (16384 connections)
echo -e "${YELLOW}=== PHASE 4: GC Comparison at High Concurrency (16384 connections) ===${NC}"
run_benchmark "ParallelGC" "8g" 16384 32768
run_benchmark "G1GC" "8g" 16384 32768
run_benchmark "ZGC-Gen" "8g" 16384 32768

# Final cleanup
kill_servers

# Display summary
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Benchmark Matrix Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Summary:"
column -t -s',' "$RESULTS_DIR/summary.csv"
echo ""
echo "Detailed results saved to: $RESULTS_DIR"
