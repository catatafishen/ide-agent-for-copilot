#!/bin/bash
# Restart IntelliJ sandbox IDE cleanly
# Usage: ./restart-sandbox.sh [--clean]

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# Persistent config lives outside build/ so it survives clean builds
PERSISTENT_CONFIG="$PROJECT_DIR/.sandbox-config"

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

# Save current sandbox config before build (if it exists)
SANDBOX_BASE="$PROJECT_DIR/plugin-core/build/idea-sandbox"
LIVE_CONFIG=$(find "$SANDBOX_BASE" -maxdepth 2 -name "config" -type d 2>/dev/null | head -1)
if [ -n "$LIVE_CONFIG" ] && [ -d "$LIVE_CONFIG/options" ]; then
    echo "Saving sandbox config..."
    mkdir -p "$PERSISTENT_CONFIG"
    rsync -a --delete "$LIVE_CONFIG/" "$PERSISTENT_CONFIG/"
fi

# Clean build if requested
if [ "$1" = "--clean" ]; then
    echo "Performing clean build..."
    ./gradlew clean :plugin-core:prepareSandbox
else
    echo "Performing incremental build..."
    ./gradlew :plugin-core:prepareSandbox
fi

# Restore persisted config after build
LIVE_CONFIG=$(find "$SANDBOX_BASE" -maxdepth 2 -name "config" -type d 2>/dev/null | head -1)
if [ -n "$LIVE_CONFIG" ] && [ -d "$PERSISTENT_CONFIG/options" ]; then
    echo "Restoring sandbox config..."
    rsync -a "$PERSISTENT_CONFIG/" "$LIVE_CONFIG/"
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
echo "Tip: Settings, plugins, and trusted projects are preserved between restarts"
echo "     Config stored in: .sandbox-config/"
