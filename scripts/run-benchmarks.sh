#!/bin/bash

# eru-http Automated Benchmark Runner
# Runs comprehensive performance benchmarks and stores results

set -e

# Configuration
WRK=${WRK:-/tmp/wrk/wrk}
HOST=${HOST:-localhost}
PORT=${PORT:-8080}
RESULTS_DIR=${RESULTS_DIR:-./benchmark-results}
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULT_FILE="$RESULTS_DIR/benchmark-$TIMESTAMP.txt"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Create results directory if it doesn't exist
mkdir -p "$RESULTS_DIR"

echo "========================================"
echo "eru-http Performance Benchmark Suite"
echo "========================================"
echo ""
echo "Timestamp: $TIMESTAMP"
echo "Target: http://$HOST:$PORT"
echo "Results: $RESULT_FILE"
echo ""

# Check if wrk is available
if [ ! -f "$WRK" ]; then
    echo -e "${RED}Error: wrk not found at $WRK${NC}"
    echo "Please install wrk or set WRK environment variable"
    exit 1
fi

# Check if server is running
if ! curl -s http://$HOST:$PORT/ > /dev/null 2>&1; then
    echo -e "${RED}Error: Server not responding at http://$HOST:$PORT${NC}"
    echo "Please start the benchmark server first:"
    echo "  sbt 'server/Test/runMain net.ghoula.eru.http.server.BenchmarkServer $PORT'"
    exit 1
fi

echo -e "${GREEN}✓ Server is running${NC}"
echo ""

# Function to run a benchmark and capture results
run_benchmark() {
    local name=$1
    local url=$2
    local threads=$3
    local connections=$4
    local duration=$5
    local method=${6:-GET}
    local body=${7:-}

    echo -e "${YELLOW}Running: $name${NC}"
    echo "  URL: $url"
    echo "  Config: $threads threads, $connections connections, ${duration}s"

    # Build wrk command
    local wrk_cmd="$WRK -t$threads -c$connections -d${duration}s"

    if [ "$method" = "POST" ] && [ -n "$body" ]; then
        wrk_cmd="$wrk_cmd -s /dev/stdin"
        echo "wrk.method = 'POST'" | $wrk_cmd "$url" 2>&1
    else
        $wrk_cmd "$url" 2>&1
    fi

    echo ""
}

# Write header to result file
{
    echo "========================================"
    echo "eru-http Performance Benchmark Results"
    echo "========================================"
    echo ""
    echo "Date: $(date)"
    echo "Timestamp: $TIMESTAMP"
    echo "Target: http://$HOST:$PORT"
    echo "WRK: $($WRK --version 2>&1 | head -1)"
    echo ""
    echo "System Info:"
    echo "  OS: $(uname -s) $(uname -r)"
    echo "  CPU: $(nproc) cores"
    echo "  Memory: $(free -h | awk '/^Mem:/ {print $2}')"
    echo ""
    echo "========================================"
    echo ""
} > "$RESULT_FILE"

# Scenario 1: Plaintext (baseline)
echo "## Scenario 1: Plaintext Response (Baseline)" | tee -a "$RESULT_FILE"
run_benchmark \
    "Plaintext - Medium Load" \
    "http://$HOST:$PORT/plaintext" \
    4 100 10 | tee -a "$RESULT_FILE"

run_benchmark \
    "Plaintext - High Load" \
    "http://$HOST:$PORT/plaintext" \
    12 400 30 | tee -a "$RESULT_FILE"

# Scenario 2: JSON Response
echo "## Scenario 2: JSON Response" | tee -a "$RESULT_FILE"
run_benchmark \
    "JSON - Medium Load" \
    "http://$HOST:$PORT/json" \
    4 100 10 | tee -a "$RESULT_FILE"

# Scenario 3: Large JSON (1KB)
echo "## Scenario 3: Large JSON Response (1KB)" | tee -a "$RESULT_FILE"
run_benchmark \
    "Large JSON (1KB) - Medium Load" \
    "http://$HOST:$PORT/json-large" \
    4 100 10 | tee -a "$RESULT_FILE"

# Scenario 4: Large Text (10KB)
echo "## Scenario 4: Large Text Response (10KB)" | tee -a "$RESULT_FILE"
run_benchmark \
    "Large Text (10KB) - Medium Load" \
    "http://$HOST:$PORT/large-10kb" \
    4 100 10 | tee -a "$RESULT_FILE"

# Scenario 5: Very Large Text (100KB)
echo "## Scenario 5: Very Large Text Response (100KB)" | tee -a "$RESULT_FILE"
run_benchmark \
    "Large Text (100KB) - Low Load" \
    "http://$HOST:$PORT/large-100kb" \
    4 50 10 | tee -a "$RESULT_FILE"

# Scenario 6: Stateful Counter
echo "## Scenario 6: Stateful Counter" | tee -a "$RESULT_FILE"
run_benchmark \
    "Stateful Counter - Medium Load" \
    "http://$HOST:$PORT/counter" \
    4 100 10 | tee -a "$RESULT_FILE"

# Scenario 7: Error Responses
echo "## Scenario 7: Error Responses" | tee -a "$RESULT_FILE"
run_benchmark \
    "400 Error - Medium Load" \
    "http://$HOST:$PORT/error-400" \
    4 100 10 | tee -a "$RESULT_FILE"

# Scenario 8: Slow Endpoint
echo "## Scenario 8: Slow Endpoint (10ms delay)" | tee -a "$RESULT_FILE"
run_benchmark \
    "Slow Endpoint - Low Load" \
    "http://$HOST:$PORT/slow" \
    4 50 10 | tee -a "$RESULT_FILE"

# Summary
echo "" | tee -a "$RESULT_FILE"
echo "========================================"  | tee -a "$RESULT_FILE"
echo "Benchmark Complete!" | tee -a "$RESULT_FILE"
echo "========================================"  | tee -a "$RESULT_FILE"
echo "" | tee -a "$RESULT_FILE"
echo "Results saved to: $RESULT_FILE" | tee -a "$RESULT_FILE"
echo ""

# Extract key metrics for quick comparison
echo "Quick Summary:" | tee -a "$RESULT_FILE"
echo "-------------" | tee -a "$RESULT_FILE"
grep -A 1 "Requests/sec" "$RESULT_FILE" | grep -E "Requests/sec|Scenario" | tee -a "$RESULT_FILE.summary"

echo ""
echo -e "${GREEN}✓ Benchmark complete!${NC}"
echo ""
echo "Full results: $RESULT_FILE"
echo "Summary: $RESULT_FILE.summary"
echo ""

# If previous benchmark exists, offer comparison
LATEST=$(ls -t "$RESULTS_DIR"/benchmark-*.txt 2>/dev/null | head -2 | tail -1)
if [ -n "$LATEST" ] && [ "$LATEST" != "$RESULT_FILE" ]; then
    echo "To compare with previous run:"
    echo "  diff -u $LATEST $RESULT_FILE"
fi
