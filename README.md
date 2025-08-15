# Hikvision NVR (401 H.265) → HLS Demo (Java + Vue)

## 0) Install dependencies (Ubuntu)
```bash
bash scripts/install_deps.sh
```

## 1) Configure Nginx to serve HLS
```bash
sudo cp nginx/nvr_streams.conf /etc/nginx/snippets/nvr_streams.conf
# Edit your server block to include the snippet (see file comment), then:
sudo nginx -t && sudo systemctl reload nginx
```

## 2) Build & run backend
```bash
cd backend
mvn -DskipTests spring-boot:run

**mvn -q -DskipTests package
# run (dev mode)
java -jar target/nvr-hls-backend-0.1.0.jar**
```

## 3) Start pulling RTSP 401 and generating HLS
```bash
curl -X POST "http://127.0.0.1:8080/api/streams/cam402/start" \
  --data-urlencode "rtspUrl=rtsp://admin:00000000a@192.168.50.76:554/Streaming/Channels/402" \
  --data-urlencode "copy=true"# Expect response with: { "hls": "/streams/cam401/index.m3u8" }
```

## 4) Frontend (Vue3 + Vite)
```bash
cd frontend
npm install
# Optional: configure backend and HLS origins for dev
cp .env.local.example .env.local  # then edit if ports/hosts differ
npm run dev
# Open http://<your-server>:5173 , click “启动”
```

If you see the video player fail to load with `index.m3u8` 404 in dev:
- Either configure nginx as in step 1 so `/streams` is served on port 80, or
- Set `VITE_HLS_ORIGIN` to the backend (`http://127.0.0.1:8080`). The dev proxy now defaults to the backend if not set.
- Ensure the backend writes HLS to `nvr.hlsRoot` and that directory exists and is writable.

### Notes
- Since 401 is H.265 on your NVR, we transcode video to H.264 (baseline) and audio to AAC.
- In dev, the Vite server proxies `/api` to `VITE_API_TARGET` and `/streams` to
  `VITE_HLS_ORIGIN`. This lets the player use relative HLS URLs like
  `/streams/<id>/index.m3u8` without CORS issues.
- For production, consider running the backend under systemd:
```bash
sudo mkdir -p /opt/nvr/backend
sudo cp -r backend /opt/nvr/
sudo cp systemd/nvr-api.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now nvr-api.service
```
- Optionally, you can use the instance unit for ffmpeg instead of the Java manager:
```bash
sudo cp systemd/nvr-hls@.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl start nvr-hls@cam401.service
```

## 5) Stop stream
```bash
curl -X DELETE "http://127.0.0.1:8080/api/streams/cam401"
```

## 6) Troubleshooting
- Check ffmpeg logs in backend stdout: `journalctl -u nvr-api.service -f` (if using systemd).
- Ensure `/var/www/streams/cam401/index.m3u8` exists after starting.
- Nginx must expose `/streams/` with correct MIME types and CORS (see snippet).
