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
# Find sandbox IDE processes (look for gradle runIde and the actual IDE process)
GRADLE_PIDS=$(pgrep -f "gradlew.*runIde" || true)
IDE_PIDS=$(pgrep -f "idea-sandbox" || true)

for PID in $GRADLE_PIDS $IDE_PIDS; do
    if [ -n "$PID" ]; then
        echo "  Stopping PID $PID..."
        kill "$PID" 2>/dev/null || true
    fi
done

if [ -n "$GRADLE_PIDS" ] || [ -n "$IDE_PIDS" ]; then
    echo "  Waiting for processes to stop..."
    sleep 3
fi

# Check if any still running
REMAINING=$(pgrep -f "gradlew.*runIde|idea-sandbox" | wc -l)
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

# Save marketplace-installed plugins (they live in system/plugins/ as zips)
LIVE_SYSTEM_PLUGINS=$(find "$SANDBOX_BASE" -maxdepth 2 -name "plugins" -path "*/system/*" -type d 2>/dev/null | head -1)
PERSISTENT_PLUGINS="$PROJECT_DIR/.sandbox-plugins"
if [ -n "$LIVE_SYSTEM_PLUGINS" ] && [ -d "$LIVE_SYSTEM_PLUGINS" ]; then
    # Only save non-built plugins (exclude our own plugin-core)
    PLUGIN_FILES=$(find "$LIVE_SYSTEM_PLUGINS" -maxdepth 1 \( -name "*.zip" -o -name "*.jar" \) 2>/dev/null)
    if [ -n "$PLUGIN_FILES" ]; then
        echo "Saving marketplace plugins..."
        mkdir -p "$PERSISTENT_PLUGINS"
        for f in $PLUGIN_FILES; do
            cp -p "$f" "$PERSISTENT_PLUGINS/"
        done
    fi
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

# Restore marketplace plugins after build
LIVE_SYSTEM_PLUGINS=$(find "$SANDBOX_BASE" -maxdepth 2 -name "plugins" -path "*/system/*" -type d 2>/dev/null | head -1)
if [ -d "$PERSISTENT_PLUGINS" ] && [ -n "$LIVE_SYSTEM_PLUGINS" ]; then
    PLUGIN_FILES=$(find "$PERSISTENT_PLUGINS" -maxdepth 1 \( -name "*.zip" -o -name "*.jar" \) 2>/dev/null)
    if [ -n "$PLUGIN_FILES" ]; then
        echo "Restoring marketplace plugins..."
        mkdir -p "$LIVE_SYSTEM_PLUGINS"
        for f in $PLUGIN_FILES; do
            cp -p "$f" "$LIVE_SYSTEM_PLUGINS/"
        done
    fi
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
