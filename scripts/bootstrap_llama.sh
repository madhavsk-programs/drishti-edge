#!/usr/bin/env bash
# Fetch the pinned llama.cpp revision that Scene Mode builds against.
#
# The tree is not vendored: it is large, this repository is public, and a
# revision we can re-fetch and verify is better provenance than a snapshot
# someone may have edited in place. third_party/ is gitignored.
#
#   ./scripts/bootstrap_llama.sh
#
# Then ./gradlew :app:assembleDebug builds it through the app's CMakeLists.
set -euo pipefail

# Pinned 12 September 2026. Chosen because it carries PROJECTOR_TYPE_QWEN3VL and
# a working LFM2.5-VL path in tools/mtmd. Bump this deliberately, never
# incidentally: the build flags in app/src/main/cpp/CMakeLists.txt were measured
# against this revision.
TAG="b10926"
COMMIT="2a3005c23f60cb38dab70b8ea2ddbd969bcf3e87"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/llama.cpp"

if [ -d "$DEST/.git" ]; then
  have="$(git -C "$DEST" rev-parse HEAD)"
  if [ "$have" = "$COMMIT" ]; then
    echo "llama.cpp already at $TAG ($COMMIT)"
    exit 0
  fi
  echo "third_party/llama.cpp is at $have, expected $COMMIT" >&2
  echo "Remove it and re-run if you want the pinned revision." >&2
  exit 1
fi

mkdir -p "$ROOT/third_party"
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp "$DEST"

actual="$(git -C "$DEST" rev-parse HEAD)"
if [ "$actual" != "$COMMIT" ]; then
  echo "Tag $TAG resolved to $actual, not the pinned $COMMIT." >&2
  echo "A moved tag is a supply-chain signal, not a nuisance. Investigate." >&2
  exit 1
fi

echo "llama.cpp pinned at $TAG ($COMMIT)"
