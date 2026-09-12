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
KLEIDIAI_VERSION="1.24.0"
KLEIDIAI_MD5="2f02ebe29573d45813e671eb304f2a00"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/llama.cpp"

if [ -d "$DEST/.git" ]; then
  have="$(git -C "$DEST" rev-parse HEAD)"
  if [ "$have" = "$COMMIT" ]; then
    echo "llama.cpp already at $TAG ($COMMIT)"
  else
    echo "third_party/llama.cpp is at $have, expected $COMMIT" >&2
    echo "Remove it and re-run if you want the pinned revision." >&2
    exit 1
  fi
else
  mkdir -p "$ROOT/third_party"
  git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp "$DEST"

  actual="$(git -C "$DEST" rev-parse HEAD)"
  if [ "$actual" != "$COMMIT" ]; then
    echo "Tag $TAG resolved to $actual, not the pinned $COMMIT." >&2
    echo "A moved tag is a supply-chain signal, not a nuisance. Investigate." >&2
    exit 1
  fi

  echo "llama.cpp pinned at $TAG ($COMMIT)"
fi

KLEIDIAI_DIR="$ROOT/third_party/kleidiai-v$KLEIDIAI_VERSION"
KLEIDIAI_ARCHIVE="$ROOT/third_party/kleidiai-v$KLEIDIAI_VERSION-src.tar.gz"
if [ -f "$KLEIDIAI_DIR/CMakeLists.txt" ]; then
  echo "KleidiAI already staged at v$KLEIDIAI_VERSION"
else
  curl -L --fail --retry 3 -o "$KLEIDIAI_ARCHIVE" \
    "https://github.com/ARM-software/kleidiai/releases/download/v$KLEIDIAI_VERSION/kleidiai-v$KLEIDIAI_VERSION-src.tar.gz"
  actual_md5="$(md5sum "$KLEIDIAI_ARCHIVE" | awk '{print $1}')"
  if [ "$actual_md5" != "$KLEIDIAI_MD5" ]; then
    echo "KleidiAI archive has MD5 $actual_md5, expected $KLEIDIAI_MD5." >&2
    exit 1
  fi
  tar -xzf "$KLEIDIAI_ARCHIVE" -C "$ROOT/third_party"
  echo "KleidiAI staged at v$KLEIDIAI_VERSION ($KLEIDIAI_MD5)"
fi
