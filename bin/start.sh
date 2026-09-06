#!/bin/bash
# -----------------------------------------------------------------------------
# Z-GW Gateway Startup Script
# -----------------------------------------------------------------------------

set -e

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Configuration
JAVA_OPTS="${JAVA_OPTS:- -Xms512m -Xmx1024m}"
CONFIG_PATH="${CONFIG_PATH:- conf/gateway.yaml}"

# Find Java
if [ -n "$JAVA_HOME" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    JAVA_CMD="java"
fi

# Check Java version
JAVA_VERSION=$($JAVA_CMD -version 2>&1 | head -1 | cut -d'"' -f2 | sed 's/\..*//')
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "Error: Java 17 or higher is required"
    exit 1
fi

# Build if JAR not found
JAR_FILE="$PROJECT_DIR/z-gw-starter/target/z-gw-starter-1.0.0-SNAPSHOT.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "Building Z-GW Gateway..."
    cd "$PROJECT_DIR"
    ./mvnw clean package -DskipTests
fi

# Check if already running
PID_FILE="$PROJECT_DIR/z-gw.pid"
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "Z-GW Gateway is already running (PID: $PID)"
        exit 1
    else
        rm -f "$PID_FILE"
    fi
fi

# Create logs directory
mkdir -p "$PROJECT_DIR/logs"

# Start gateway
echo "Starting Z-GW Gateway..."
echo "  Config: $CONFIG_PATH"
echo "  Java: $JAVA_CMD"
echo "  Java Options: $JAVA_OPTS"

nohup "$JAVA_CMD" $JAVA_OPTS \
    -Dgateway.config="$CONFIG_PATH" \
    -Dlogging.file.path="$PROJECT_DIR/logs" \
    -jar "$JAR_FILE" \
    > "$PROJECT_DIR/logs/console.log" 2>&1 &

PID=$!
echo $PID > "$PID_FILE"

# Wait a moment and check if process is running
sleep 2
if ps -p "$PID" > /dev/null 2>&1; then
    echo "Z-GW Gateway started successfully (PID: $PID)"
    echo "  Health check: http://localhost:8080/health"
else
    echo "Failed to start Z-GW Gateway"
    rm -f "$PID_FILE"
    exit 1
fi