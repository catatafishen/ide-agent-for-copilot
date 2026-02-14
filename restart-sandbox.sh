#!/bin/bash
# Restart IntelliJ sandbox IDE cleanly
# Usage: ./restart-sandbox.sh [--clean]

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

echo "=== Sandbox Restart Script ==="

# Stop existing sandbox processes
echo "Stopping existing sandbox processes..."
PIDS=$(ps aux | grep "java.*idea.*sandbox" | grep -v grep | awk '{print $2}' || true)
if [ -n "$PIDS" ]; then
    for PID in $PIDS; do
        echo "  Stopping PID $PID..."
        kill "$PID" || true
    done
    sleep 3
fi

# Check if any still running
REMAINING=$(ps aux | grep "java.*idea.*sandbox" | grep -v grep | wc -l)
if [ "$REMAINING" -gt 0 ]; then
    echo "WARNING: Some sandbox processes still running. Use kill -9 manually if needed."
fi

# Preserve sandbox config between restarts
SANDBOX_CONFIG="build/idea-sandbox/config-test"
if [ ! -d "$SANDBOX_CONFIG" ]; then
    echo "Creating persistent sandbox config directory..."
    mkdir -p "$SANDBOX_CONFIG/options"
fi

# Clean build if requested
if [ "$1" = "--clean" ]; then
    echo "Performing clean build..."
    echo "NOTE: Preserving sandbox config for faster restarts"
    # Only clean build output, not sandbox state
    ./gradlew clean :plugin-core:prepareSandbox
else
    echo "Performing incremental build..."
    ./gradlew :plugin-core:prepareSandbox
fi

echo ""
echo "=== Starting Sandbox IDE ==="
echo "Running in background. Use 'tail -f ide_launch.log' to monitor."
echo ""

# Start sandbox in background
nohup ./gradlew :plugin-core:runIde > ide_launch.log 2>&1 &
SANDBOX_PID=$!

echo "Sandbox starting with PID: $SANDBOX_PID"
echo "Log file: $PROJECT_DIR/ide_launch.log"
echo ""
echo "To stop: kill $SANDBOX_PID"
echo "To monitor: tail -f ide_launch.log"
echo ""
echo "Tip: Settings, plugins, and trusted projects are now preserved between restarts"
