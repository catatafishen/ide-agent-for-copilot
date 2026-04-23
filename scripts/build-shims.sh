#!/usr/bin/env bash
# Build the cross-platform shim binary for every (os, arch) we ship and place
# the artifacts under plugin-core/src/main/resources/agentbridge/shim/bin/.
#
# Run locally when the Go source under plugin-core/shim-src/ changes. The
# resulting binaries are checked into the repository so contributors without
# a Go toolchain can still build the plugin. CI re-runs this script and fails
# the build when the committed binaries are stale.
#
# Requires: go >= 1.21. On a fresh box use:
#   curl -sSL https://go.dev/dl/go1.23.4.linux-amd64.tar.gz | tar -xz -C ~/.local/
#   export PATH=$HOME/.local/go/bin:$PATH

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
src_dir="$repo_root/plugin-core/shim-src"
out_root="$repo_root/plugin-core/src/main/resources/agentbridge/shim/bin"

if ! command -v go >/dev/null 2>&1; then
  echo "build-shims.sh: 'go' not found on PATH" >&2
  exit 1
fi

echo "Go version: $(go version)"
echo "Output dir: $out_root"

# CGO_ENABLED=0 produces fully static binaries with no glibc/libSystem
# dependency, so the same artifact works across distros / macOS versions.
export CGO_ENABLED=0

mkdir -p "$out_root"

targets=(
  "linux/amd64"
  "linux/arm64"
  "darwin/amd64"
  "darwin/arm64"
  "windows/amd64"
  "windows/arm64"
)

for tgt in "${targets[@]}"; do
  goos="${tgt%/*}"
  goarch="${tgt#*/}"
  ext=""
  if [ "$goos" = "windows" ]; then ext=".exe"; fi

  out_dir="$out_root/$goos-$goarch"
  out_file="$out_dir/agentbridge-shim$ext"
  mkdir -p "$out_dir"

  echo "Building $goos/$goarch -> $out_file"
  ( cd "$src_dir" && \
    GOOS="$goos" GOARCH="$goarch" \
      go build -trimpath -ldflags="-s -w" -o "$out_file" . )
done

echo
echo "All shim binaries built:"
ls -lh "$out_root"/*/*
