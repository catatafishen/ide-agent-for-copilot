#!/bin/bash
# Format all source files using IntelliJ MCP tools

set -e

BRIDGE_CONFIG="$HOME/.copilot/psi-bridge.json"
if [ ! -f "$BRIDGE_CONFIG" ]; then
    echo "Error: PSI Bridge not running. Start sandbox IDE first."
    exit 1
fi

PORT=$(grep -oP '"port":\s*\K\d+' "$BRIDGE_CONFIG")
BASE_URL="http://localhost:$PORT/tools/call"

# Function to call MCP tool
call_tool() {
    local tool_name="$1"
    local file_path="$2"
    
    curl -s -X POST "$BASE_URL" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"$tool_name\",\"arguments\":{\"path\":\"$file_path\"}}" \
        | grep -oP '"result":"\K[^"]*'
}

echo "Formatting and optimizing imports for all source files..."
echo "PSI Bridge: http://localhost:$PORT"
echo ""

# Find all Java and Kotlin source files
find . -type f \( -name "*.java" -o -name "*.kt" \) \
    | grep -E "^\./(plugin-core|mcp-server|integration-tests)/src" \
    | while read -r file; do
        # Remove leading ./
        file="${file#./}"
        
        echo -n "Processing: $file ... "
        
        # Optimize imports first
        result=$(call_tool "optimize_imports" "$file")
        
        # Then format code
        result=$(call_tool "format_code" "$file")
        
        echo "✓"
    done

echo ""
echo "Done! All files formatted and imports optimized."
