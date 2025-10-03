#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT_DIR/third_party/webrtc-streamer/bin/webrtc-streamer"
WEBROOT="$ROOT_DIR/third_party/webrtc-streamer/share/webrtc-streamer/html"
DEFAULT_CONFIG="$ROOT_DIR/third_party/webrtc-streamer/share/webrtc-streamer/config.json"

if [[ ! -x "$BIN" ]]; then
  echo "webrtc-streamer binary not found at $BIN" >&2
  exit 1
fi

HOST_PORT="0.0.0.0:8800"

cmd=("$BIN" "-H" "$HOST_PORT" "-w" "$WEBROOT" "-o" "-v")
if [[ -f "$DEFAULT_CONFIG" ]]; then
  cmd+=("-C" "$DEFAULT_CONFIG")
fi

cmd+=("$@")
exec "${cmd[@]}"
