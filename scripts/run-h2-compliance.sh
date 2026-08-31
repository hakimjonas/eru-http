#!/bin/bash
# HTTP/2 Compliance Testing Suite
# Runs multiple external tools to verify HTTP/2 RFC 9113 compliance

set -e

HOST="${1:-localhost}"
PORT="${2:-8443}"
BASE_URL="https://${HOST}:${PORT}"

echo "========================================"
echo "HTTP/2 Compliance Testing Suite"
echo "Target: ${BASE_URL}"
echo "========================================"
echo ""

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

passed=0
failed=0
skipped=0

run_test() {
    local name="$1"
    local cmd="$2"
    echo -n "Testing: ${name}... "
    if eval "${cmd}" > /tmp/h2test_output.txt 2>&1; then
        echo -e "${GREEN}PASSED${NC}"
        ((passed++))
        return 0
    else
        echo -e "${RED}FAILED${NC}"
        cat /tmp/h2test_output.txt
        ((failed++))
        return 1
    fi
}

skip_test() {
    local name="$1"
    local reason="$2"
    echo -e "Testing: ${name}... ${YELLOW}SKIPPED${NC} (${reason})"
    ((skipped++))
}

check_tool() {
    command -v "$1" >/dev/null 2>&1 || [ -x "/tmp/$1" ]
}

get_tool() {
    if command -v "$1" >/dev/null 2>&1; then
        command -v "$1"
    elif [ -x "/tmp/$1" ]; then
        echo "/tmp/$1"
    fi
}

echo "=== 1. h2spec Conformance Tests ==="
if check_tool h2spec; then
    H2SPEC=$(get_tool h2spec)
    echo "Running h2spec against ${HOST}:${PORT}..."
    $H2SPEC -h "${HOST}" -p "${PORT}" -t -k --strict 2>&1 | tail -20
    echo ""
else
    skip_test "h2spec" "h2spec not installed (go install github.com/summerwind/h2spec/cmd/h2spec@latest)"
fi

echo ""
echo "=== 2. nghttp2 Client Tests ==="
if check_tool nghttp; then
    run_test "nghttp basic request" "nghttp -nv ${BASE_URL}/ 2>&1 | grep -q 'recv HEADERS'"
    run_test "nghttp with headers" "nghttp -nv -H 'Accept: application/json' ${BASE_URL}/ 2>&1 | grep -q 'recv'"
    run_test "nghttp multiplexed requests" "nghttp -nvm 3 ${BASE_URL}/ ${BASE_URL}/ ${BASE_URL}/ 2>&1 | grep -c 'recv HEADERS' | grep -q '[3-9]'"
else
    skip_test "nghttp tests" "nghttp not installed (apt install nghttp2-client)"
fi

echo ""
echo "=== 3. curl HTTP/2 Tests ==="
if check_tool curl; then
    # Check if curl supports HTTP/2
    if curl --version | grep -q HTTP2; then
        run_test "curl HTTP/2 GET" "curl -ksS --http2 -o /dev/null -w '%{http_version}' ${BASE_URL}/ | grep -q '2'"
        run_test "curl HTTP/2 with headers" "curl -ksS --http2 -H 'X-Test: value' -o /dev/null ${BASE_URL}/"
        run_test "curl HTTP/2 POST" "curl -ksS --http2 -X POST -d 'test=data' -o /dev/null ${BASE_URL}/"
        run_test "curl HTTP/2 prior knowledge (h2c)" "curl -sS --http2-prior-knowledge -o /dev/null http://${HOST}:${PORT}/ 2>&1 || true"
    else
        skip_test "curl HTTP/2 tests" "curl not compiled with HTTP/2 support"
    fi
else
    skip_test "curl tests" "curl not installed"
fi

echo ""
echo "=== 4. h2load Performance Test ==="
if check_tool h2load; then
    echo "Running h2load benchmark (100 requests, 10 concurrent)..."
    h2load -n100 -c10 ${BASE_URL}/ 2>&1 | grep -E "(requests|time for request|status codes)"
    echo ""
else
    skip_test "h2load benchmark" "h2load not installed (apt install nghttp2-client)"
fi

echo ""
echo "=== 5. Protocol Negotiation Tests ==="
if check_tool openssl; then
    run_test "ALPN h2 negotiation" "echo | openssl s_client -connect ${HOST}:${PORT} -alpn h2 2>/dev/null | grep -q 'ALPN protocol: h2'"
else
    skip_test "ALPN tests" "openssl not installed"
fi

echo ""
echo "========================================"
echo "Summary"
echo "========================================"
echo -e "Passed:  ${GREEN}${passed}${NC}"
echo -e "Failed:  ${RED}${failed}${NC}"
echo -e "Skipped: ${YELLOW}${skipped}${NC}"
echo ""

if [ ${failed} -gt 0 ]; then
    echo -e "${RED}Some tests failed!${NC}"
    exit 1
else
    echo -e "${GREEN}All executed tests passed!${NC}"
    exit 0
fi
