#!/bin/bash
# -----------------------------------------------------------------------------
# Z-GW Gateway Restart Script
# -----------------------------------------------------------------------------

set -e

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Restarting Z-GW Gateway..."

# Stop the gateway
"$SCRIPT_DIR/stop.sh"

# Wait a moment
sleep 1

# Start the gateway
"$SCRIPT_DIR/start.sh"

echo "Z-GW Gateway restarted successfully"