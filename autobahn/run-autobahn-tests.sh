#!/bin/bash
# Autobahn WebSocket Test Suite runner for eru-http
#
# This script:
# 1. Starts the eru-http WebSocket echo server in the background
# 2. Runs the Autobahn fuzzing client against it
# 3. Generates HTML reports in ./autobahn/reports/server/
#
# Prerequisites:
# - Docker installed and running
# - sbt installed
#
# Usage: ./autobahn/run-autobahn-tests.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PORT="${1:-9002}"

cd "$PROJECT_DIR"

echo "=== Autobahn WebSocket Test Suite for eru-http ==="
echo ""

# Clean previous reports
rm -rf "$SCRIPT_DIR/reports/server"
mkdir -p "$SCRIPT_DIR/reports/server"

# Start the echo server in the background
echo "Starting eru-http WebSocket echo server on port $PORT..."
sbt "examples/runMain net.ghoula.eru.http.autobahn.AutobahnEchoServer $PORT" &
SERVER_PID=$!

# Give the server time to start
echo "Waiting for server to start..."
sleep 8

# Check if server is running
if ! kill -0 $SERVER_PID 2>/dev/null; then
    echo "ERROR: Server failed to start"
    exit 1
fi

echo "Server started (PID: $SERVER_PID)"
echo ""

# Run Autobahn tests
echo "Running Autobahn fuzzing client..."
echo ""

docker run --rm \
    --network=host \
    -v "$SCRIPT_DIR/config:/config" \
    -v "$SCRIPT_DIR/reports:/reports" \
    crossbario/autobahn-testsuite \
    wstest --mode fuzzingclient --spec /config/fuzzingclient.json

# Stop the server
echo ""
echo "Stopping echo server..."
kill $SERVER_PID 2>/dev/null || true
wait $SERVER_PID 2>/dev/null || true

echo ""
echo "=== Autobahn tests complete ==="
echo ""
echo "Reports available at: $SCRIPT_DIR/reports/server/index.html"
echo ""
echo "Open in browser:"
echo "  xdg-open $SCRIPT_DIR/reports/server/index.html"
