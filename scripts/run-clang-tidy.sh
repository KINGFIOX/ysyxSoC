#!/usr/bin/env bash
# Run clang-tidy on NPC C++ sources using C++ Core Guidelines.
# Requires: build first (make build) to generate compile_commands.json
# Usage: ./scripts/run-clang-tidy.sh [files...]
#        If no files given, runs on all src/cxx/**/*.cc

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="${NPC_HOME}/build/meson"
COMPILE_DB="${BUILD_DIR}/compile_commands.json"

if [[ ! -f "$COMPILE_DB" ]]; then
  echo "Error: compile_commands.json not found. Run 'make build' first." >&2
  exit 1
fi

if ! command -v clang-tidy &>/dev/null; then
  echo "Error: clang-tidy not found. Install clang-tools." >&2
  exit 1
fi

cd "$NPC_HOME"

if [[ $# -gt 0 ]]; then
  files=("$@")
else
  mapfile -t files < <(find src/cxx -name "*.cc" -type f | sort)
fi

echo "Running clang-tidy on ${#files[@]} file(s) (C++ Core Guidelines)..."
for f in "${files[@]}"; do
  if [[ -f "$f" ]]; then
    clang-tidy -p "$BUILD_DIR" "$f" --quiet 2>/dev/null || true
  fi
done
