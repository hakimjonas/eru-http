#!/bin/bash
# Run h2spec HTTP/2 conformance tests against eru-http server
#
# Usage: ./scripts/run-h2spec.sh [OPTIONS]
#
# Options:
#   --verbose    Show verbose h2spec output
#   --strict     Show all tests (including passed)
#   --section N  Run only section N (e.g., 3, 5.1, 6.5)
#   --port PORT  Use specified port (default: 8443)

set -e

# Parse arguments
VERBOSE=""
STRICT=""
SECTION=""
PORT=8443

while [[ $# -gt 0 ]]; do
    case $1 in
        --verbose|-v)
            VERBOSE="-v"
            shift
            ;;
        --strict)
            STRICT="--strict"
            shift
            ;;
        --section|-s)
            SECTION="-s $2"
            shift 2
            ;;
        --port|-p)
            PORT="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Check if h2spec is available; otherwise download the PINNED release and verify its checksum.
# The upstream release publishes no digests, so this SHA-256 was recorded from the tarball fetched
# on 2026-08-28 straight from the GitHub release URL below. A mismatch fails the script.
H2SPEC_VERSION="v2.6.0"
H2SPEC_SHA256="157ee0de702e01ad40e752dbf074b366027e550c8e7504f9450da2809e279318"
H2SPEC="/tmp/h2spec"
if [ ! -x "$H2SPEC" ]; then
    echo "h2spec not found at $H2SPEC, downloading pinned release $H2SPEC_VERSION..."
    cd /tmp
    curl -sL -o h2spec.tar.gz "https://github.com/summerwind/h2spec/releases/download/${H2SPEC_VERSION}/h2spec_linux_amd64.tar.gz"
    echo "${H2SPEC_SHA256}  h2spec.tar.gz" | sha256sum -c -
    tar xzf h2spec.tar.gz
    chmod +x h2spec
    rm h2spec.tar.gz
    cd - > /dev/null
fi

echo "=== HTTP/2 Conformance Testing with h2spec ==="
echo ""

# Start the server in background
echo "Starting eru-http HTTP/2 server on port $PORT..."
cd "$(dirname "$0")/.."
sbt "examples/runMain net.ghoula.eru.http.h2spec.H2SpecServer $PORT" &
SERVER_PID=$!

# Cleanup function
cleanup() {
    echo ""
    echo "Stopping server (PID $SERVER_PID)..."
    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
}
trap cleanup EXIT

# Wait for the server's TLS port to actually accept connections. A cold CI
# runner compiles the examples module inside this background sbt invocation —
# minutes, not seconds — so poll rather than sleep, and fail fast if the
# server process dies mid-compile. Poll IPv4 explicitly: h2spec resolves
# 'localhost' to ::1 first, and a refused ::1 used to read as 'server down'
# even when the server was listening on the v4 wildcard.
echo "Waiting for server to start..."
READY=0
for i in $(seq 1 120); do
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        echo "Server process exited before becoming ready — see sbt output above."
        exit 1
    fi
    if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
        READY=1
        echo "Server accepting connections on 127.0.0.1:$PORT (check #$i)."
        break
    fi
    sleep 5
done
if [ "$READY" -ne 1 ]; then
    echo "Server did not accept connections on 127.0.0.1:$PORT within 10 minutes."
    exit 1
fi

echo ""
echo "=== Running h2spec ==="
echo ""

# Run h2spec
# -t: Use TLS
# -k: Skip certificate verification (self-signed cert)
# 127.0.0.1 explicitly: 'localhost' resolves to ::1 first and fails on
# v4-bound servers regardless of readiness.
$H2SPEC -h 127.0.0.1 -p $PORT -t -k $VERBOSE $STRICT $SECTION

EXIT_CODE=$?

echo ""
echo "=== h2spec completed with exit code $EXIT_CODE ==="

exit $EXIT_CODE
