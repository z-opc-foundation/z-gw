#!/bin/bash
# -----------------------------------------------------------------------------
# Z-GW Gateway Stop Script
# -----------------------------------------------------------------------------

set -e

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PID_FILE="$PROJECT_DIR/z-gw.pid"

# Check if PID file exists
if [ ! -f "$PID_FILE" ]; then
    echo "Z-GW Gateway is not running (PID file not found)"
    exit 0
fi

PID=$(cat "$PID_FILE")

# Check if process is running
if ! ps -p "$PID" > /dev/null 2>&1; then
    echo "Z-GW Gateway is not running (PID: $PID)"
    rm -f "$PID_FILE"
    exit 0
fi

# Stop the gateway
echo "Stopping Z-GW Gateway (PID: $PID)..."

# Try graceful shutdown first
kill "$PID"

# Wait for process to stop
COUNTER=0
MAX_WAIT=30  # Maximum wait time in seconds

while ps -p "$PID" > /dev/null 2>&1; do
    sleep 1
    COUNTER=$((COUNTER + 1))

    if [ $COUNTER -ge $MAX_WAIT ]; then
        echo "Graceful shutdown timeout, forcing stop..."
        kill -9 "$PID" 2>/dev/null || true
        break
    fi

    echo "Waiting for shutdown... ($COUNTER/$MAX_WAIT)"
done

# Remove PID file
rm -f "$PID_FILE"

if ps -p "$PID" > /dev/null 2>&1; then
    echo "Failed to stop Z-GW Gateway"
    exit 1
else
    echo "Z-GW Gateway stopped successfully"
fi