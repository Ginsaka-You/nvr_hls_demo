# nvr-hls-backend

Java 11+, Spring Boot REST API to spawn FFmpeg for RTSP -> HLS (m3u8) and serve status.

## Build
```
cd backend
./mvnw -v || mvn -v
mvn -q -DskipTests package
```

The jar will be at `target/nvr-hls-backend-0.1.0.jar`.

## Run
```
sudo mkdir -p /var/www/streams
sudo chown -R $USER:$USER /var/www/streams  # or www-data: adjust for your nginx user
java -jar target/nvr-hls-backend-0.1.0.jar
```

## Start a stream (example for channel 401)
```
curl -X POST "http://127.0.0.1:8080/api/streams/cam401/start"   --data-urlencode "rtspUrl=rtsp://admin:密码@192.168.50.76:554/Streaming/Channels/401"
```

It returns `{"id":"cam401","hls":"/streams/cam401/index.m3u8"}`.

## Stop the stream
```
curl -X DELETE "http://127.0.0.1:8080/api/streams/cam401"
```
