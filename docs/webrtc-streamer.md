# WebRTC Streamer Integration

The default demo used FFmpeg to transcode RTSP → HLS. Hikvision streams can
handle sub-second latency better through WebRTC. This document summarises how to
install [mpromonet/webrtc-streamer](https://github.com/mpromonet/webrtc-streamer)
and wire it into this project.

## 1. Get the binary

### Option A: Download a release (recommended)

```bash
# VERSION defaults to v0.8.13, PREFIX defaults to /opt/webrtc-streamer
scripts/install_webrtc_streamer.sh v0.8.13 /opt/webrtc-streamer
```

The script detects the host architecture and pulls the matching `Release`
archive, extracting it under `PREFIX`. Copy (or symlink) the installed tree to
`third_party/webrtc-streamer` if you prefer the repository layout used by the
auto-start integration (see below).

### Option B: Build from source

```bash
# 1. depot_tools is required by WebRTC
cd ~
git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
export PATH="$PWD/depot_tools:$PATH"

# 2. Fetch WebRTC (grab a coffee – this is large)
mkdir -p ~/webrtc
cd ~/webrtc
fetch webrtc

# 3. Build WebRTC-Streamer
cd /path/to/webrtc-streamer
cmake -S . -B build -DWEBRTCROOT=~/webrtc
cmake --build build -j$(nproc)

# The binary is at build/webrtc-streamer
```

On Arm SBCs you may need extra packages (`build-essential`, `cmake`, `libssl-dev`,
`libasound2-dev`, …). Refer to the upstream README for the full list.

## 2. Run it with Hikvision cameras

```bash
./scripts/run_webrtc_streamer.sh
```

This wrapper starts the binary from `third_party/webrtc-streamer/bin`, sets the
web root/config, and passes `-o` to avoid transcoding Hikvision H.264. The
Spring Boot backend also launches the same binary automatically on startup when
`nvr.webrtc.enabled=true` (default). The only manual step is ensuring the
binary/config paths exist.

If you prefer to keep the streamer elsewhere, edit `application.yml` or export:

```bash
NVR_WEBRTC_BINARY=/opt/webrtc-streamer/webrtc-streamer \
  java -jar backend/target/nvr-hls-backend-0.1.0.jar
```

The frontend passes full RTSP URLs, so no config file is strictly required. If
you prefer aliases, create `/opt/webrtc-streamer/config.json`:

```json
{
  "urls": {
    "cam401": {
      "video": "rtsp://admin:密码@192.168.50.76:554/Streaming/Channels/401",
      "options": "rtptransport=tcp&timeout=60"
    }
  }
}
```

Start the binary with `-C /opt/webrtc-streamer/config.json` to register them.

### Sample systemd unit

```ini
[Unit]
Description=WebRTC Streamer
After=network.target

[Service]
ExecStart=/opt/webrtc-streamer/webrtc-streamer -H 0.0.0.0:8000 -o -C /opt/webrtc-streamer/config.json
Restart=on-failure
User=www-data
Group=www-data

[Install]
WantedBy=multi-user.target
```

Reload systemd and enable the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now webrtc-streamer.service
```

## 3. Hook up the Vue dashboard

1. Open **设置 → 多摄像头**.
2. Switch *播放模式* to **WebRTC（低延迟）**.
3. Fill *WebRTC 服务* with the streamer URL (e.g. `http://192.168.50.20:8800`).
4. Optional: adjust the query string (`rtptransport=tcp&timeout=60`) or preferred
   codec (`video/H264`).
5. Return to the multi-camera page and click “启动”. Each tile now negotiates a
   WebRTC session directly with webrtc-streamer.

Backend configuration keys (override in `application.yml` or environment vars):

- `nvr.webrtc.enabled` – set to `false` to skip auto-start.
- `nvr.webrtc.binary` – absolute or relative path to the executable.
- `nvr.webrtc.hostPort` – value for `-H` (defaults to `0.0.0.0:8000`).
- `nvr.webrtc.webRoot` – served static files (optional but keeps built-in UI handy).
- `nvr.webrtc.config` – JSON config passed with `-C`.
- `nvr.webrtc.extraArgs` – whitespace-delimited string appended to the command.

If you need to keep the old HLS workflow, flip the mode back to **HLS**. All
previous API endpoints remain available.

## 4. Troubleshooting checklist

- `curl http://localhost:8000/api/version` should return the version string.
- Browser DevTools → Network → XHR must show `call`, `addIceCandidate`, etc.
- If the stream freezes after a few seconds on Hikvision cameras, confirm the
  `options` include `rtptransport=tcp` so TCP is used for RTSP.
- When running behind NAT, configure STUN/TURN (`-s`, `-S`, `-t`, or `-T` flags).
- For high concurrency, consider running the binary with `-N <threads>` so the
  CivetWeb HTTP server uses more worker threads.
