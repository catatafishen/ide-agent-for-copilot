#!/usr/bin/env bash
# Build the shim binary for every (os, arch) we ship and place the artifacts
# under plugin-core/src/main/resources/agentbridge/shim/bin/.
#
# Currently bundled targets: windows/amd64 only. Unix (Linux + macOS) uses the
# bundled bash script (agentbridge-shim.sh, ~3 KB) which depends on curl —
# universally present on dev machines. Adding a new target = add a line to the
# `targets` array below and rerun.
#
# Run locally when the Go source under plugin-core/shim-src/ changes. The
# resulting binaries are checked into the repository so contributors without
# a Go toolchain can still build the plugin.
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
  "windows/amd64"
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
