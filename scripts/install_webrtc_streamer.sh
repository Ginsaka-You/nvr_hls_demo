#!/usr/bin/env bash
# shellcheck disable=SC2155
set -euo pipefail

VERSION=${1:-v0.8.13}
PREFIX=${2:-/opt/webrtc-streamer}
ARCH=$(uname -m)

case "$ARCH" in
  x86_64|amd64)
    ASSET="webrtc-streamer-${VERSION}-Linux-x86_64-Release.tar.gz"
    ;;
  aarch64|arm64)
    ASSET="webrtc-streamer-${VERSION}-Linux-arm64-Release.tar.gz"
    ;;
  armv7l|armv8l)
    ASSET="webrtc-streamer-${VERSION}-Linux-armv7l-Release.tar.gz"
    ;;
  armv6l)
    ASSET="webrtc-streamer-${VERSION}-Linux-armv6l-Release.tar.gz"
    ;;
  *)
    echo "Unsupported architecture: $ARCH" >&2
    echo "Please download an appropriate archive manually from https://github.com/mpromonet/webrtc-streamer/releases" >&2
    exit 1
    ;;
endcase

URL="https://github.com/mpromonet/webrtc-streamer/releases/download/${VERSION}/${ASSET}"
TMPDIR=$(mktemp -d)
TARBALL="${TMPDIR}/${ASSET}"

cleanup() {
  rm -rf "$TMPDIR"
}
trap cleanup EXIT

echo "Downloading ${URL}" >&2
curl -fsSL "$URL" -o "$TARBALL"

echo "Installing into ${PREFIX}" >&2
sudo mkdir -p "$PREFIX"
sudo tar -xzf "$TARBALL" -C "$PREFIX" --strip-components=1

cat <<MSG
Done.
Binary path: ${PREFIX}/webrtc-streamer
Example usage:
  sudo ${PREFIX}/webrtc-streamer -H 0.0.0.0:8000
MSG
