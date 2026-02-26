#!/bin/bash
# Benchmark matrix for eru-http server
# Tests combinations of connections, heap size, and semaphore limits under ZGC generational.
# IMPORTANT: Only ZGC generational is supported. G1GC/ParallelGC have known SIGSEGV crashes
# with Virtual Threads under heavy load.

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
JAR_PATH="benchmarks/target/scala-3.8.2/eru-http-benchmark-server.jar"

# Create results directory
mkdir -p "$RESULTS_DIR"

# Function to kill all benchmark servers
kill_servers() {
    echo -e "${YELLOW}Killing all benchmark servers...${NC}"
    pkill -9 -f "eru-http-benchmark-server" 2>/dev/null || true
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

# Function to run a single benchmark test
run_benchmark() {
    local heap_size=$1
    local connections=$2
    local semaphore=$3
    local test_id="ZGC-Gen_heap${heap_size}_c${connections}_sem${semaphore}"

    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Test: $test_id${NC}"
    echo -e "${GREEN}GC: ZGC Generational, Heap: $heap_size, Connections: $connections, Semaphore: $semaphore${NC}"
    echo -e "${GREEN}========================================${NC}"

    # Kill any existing servers
    kill_servers

    # Start server from fat JAR (not inside sbt)
    local server_log="$RESULTS_DIR/${test_id}_server.log"

    echo "Heap Size: $heap_size"
    echo "Max Connections: $semaphore"
    echo "Server log: $server_log"

    java \
        -XX:+UseZGC -XX:+ZGenerational \
        -XX:-CreateCoredumpOnCrash \
        -server "-Xms${heap_size}" "-Xmx${heap_size}" \
        "-Deru.http.maxConnections=${semaphore}" \
        -jar "$JAR_PATH" 8080 \
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
    wrk -t4 -c100 -d${WARMUP_TIME}s http://localhost:8080/plaintext >/dev/null 2>&1 || true
    sleep 2

    # Run benchmark
    local wrk_log="$RESULTS_DIR/${test_id}_wrk.txt"
    echo -e "${YELLOW}Running benchmark (${TEST_DURATION}s)...${NC}"
    wrk -t${TEST_THREADS} -c${connections} -d${TEST_DURATION}s http://localhost:8080/plaintext 2>&1 | tee "$wrk_log"

    # Check if server is still running
    local server_status="RUNNING"
    if ! kill -0 $server_pid 2>/dev/null; then
        server_status="CRASHED"
        echo -e "${RED}Server crashed during test!${NC}"
    else
        echo -e "${GREEN}Server still running${NC}"
    fi

    # Collect JVM stats if server is running
    if [ "$server_status" = "RUNNING" ]; then
        local stats_log="$RESULTS_DIR/${test_id}_stats.txt"
        echo "=== JVM Stats ===" > "$stats_log"
        jcmd $server_pid VM.flags >> "$stats_log" 2>&1 || true
        echo "" >> "$stats_log"
        jcmd $server_pid GC.heap_info >> "$stats_log" 2>&1 || true
    fi

    # Extract key metrics
    local throughput=$(grep "Requests/sec:" "$wrk_log" | awk '{print $2}')
    local avg_latency=$(grep "Latency" "$wrk_log" | awk '{print $2}')
    local timeouts=$(grep "timeout" "$wrk_log" | awk '{print $8}')

    # Append to summary
    echo "$test_id,$heap_size,$connections,$semaphore,$throughput,$avg_latency,$timeouts,$server_status" >> "$RESULTS_DIR/summary.csv"

    echo -e "${GREEN}Test completed: $throughput req/s, $avg_latency latency, status: $server_status${NC}"

    # Kill server
    kill $server_pid 2>/dev/null || true
    sleep 3

    return 0
}

# Check that fat JAR exists
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${RED}Fat JAR not found at $JAR_PATH${NC}"
    echo "Build it first: sbt benchmarks/assembly"
    exit 1
fi

# Initialize summary CSV
echo "test_id,heap_size,connections,semaphore,throughput_rps,avg_latency,timeouts,status" > "$RESULTS_DIR/summary.csv"

echo -e "${GREEN}Starting Benchmark Matrix (ZGC Generational only)${NC}"
echo -e "${GREEN}Results will be saved to: $RESULTS_DIR${NC}"
echo ""

# Phase 1: Connection Scaling
echo -e "${YELLOW}=== PHASE 1: Connection Scaling (8GB heap) ===${NC}"
run_benchmark "8g" 100 1024
run_benchmark "8g" 400 1024
run_benchmark "8g" 1024 8192
run_benchmark "8g" 4096 8192

# Phase 2: Memory Efficiency (1024 connections)
echo -e "${YELLOW}=== PHASE 2: Memory Efficiency (1024 connections) ===${NC}"
run_benchmark "2g" 1024 8192
run_benchmark "4g" 1024 8192
run_benchmark "8g" 1024 8192

# Phase 3: High Concurrency
echo -e "${YELLOW}=== PHASE 3: High Concurrency (8GB heap) ===${NC}"
run_benchmark "8g" 16384 32768

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
