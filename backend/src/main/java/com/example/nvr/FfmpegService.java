package com.example.nvr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FfmpegService {

    @Value("${nvr.hlsRoot:/var/www/streams}")
    private String hlsRoot;

    @Value("${nvr.ffmpeg:/usr/bin/ffmpeg}")
    private String ffmpegPath;

    @Value("${nvr.hls.time:2}")
    private int hlsTime;

    @Value("${nvr.hls.listSize:6}")
    private int hlsListSize;

    @Value("${nvr.hls.deleteSegments:true}")
    private boolean deleteSegments;

    @Value("${nvr.hls.aacBitrate:96k}")
    private String aacBitrate;

    @Value("#{'${nvr.ffmpegFlags:-fflags nobuffer -flags low_delay -analyzeduration 0 -probesize 32 -rtsp_transport tcp}'.split(' ')}")
    private List<String> extraFlags;

    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    public synchronized String start(String id, String rtspUrl, boolean copy) throws IOException {
        if (processes.containsKey(id)) {
            return getHlsUrl(id);
        }
        File outDir = new File(hlsRoot, id);
        if (!outDir.exists()) {
            if (!outDir.mkdirs()) {
                throw new IOException("Failed to create HLS directory: " + outDir.getAbsolutePath());
            }
        }

        // 构建 ffmpeg 命令
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.addAll(extraFlags);
        cmd.add("-i");
        cmd.add(rtspUrl);

        if (copy) {
            cmd.addAll(Arrays.asList("-c:v", "copy"));
        } else {
            cmd.addAll(Arrays.asList(
                    "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
                    "-profile:v", "baseline", "-level", "3.1", "-pix_fmt", "yuv420p"));
        }

        // 音频：很多设备是 G.711，浏览器不认，转 AAC 最稳
        cmd.addAll(Arrays.asList("-c:a", "aac", "-ar", "44100", "-b:a", aacBitrate));

        // HLS 输出（低延迟、稳）
        cmd.addAll(Arrays.asList(
                "-f", "hls",
                "-hls_time", String.valueOf(hlsTime),
                "-hls_list_size", String.valueOf(hlsListSize),
                "-hls_flags", "delete_segments+program_date_time+independent_segments+temp_file",
                new File(outDir, "index.m3u8").getAbsolutePath()));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        processes.put(id, p);

        // Consume and log ffmpeg output to avoid buffer blocking
        Thread logThread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println("[ffmpeg " + id + "] " + line);
                }
            } catch (IOException ignored) {
            }
        });
        logThread.setDaemon(true);
        logThread.start();

        return getHlsUrl(id);
    }

    public synchronized void stop(String id) {
        Process p = processes.remove(id);
        if (p != null) {
            p.destroy();
        }
    }

    public String getHlsUrl(String id) {
        return "/streams/" + id + "/index.m3u8";
    }

    public boolean isRunning(String id) {
        Process p = processes.get(id);
        return p != null && p.isAlive();
    }

    public List<String> listStreams() {
        return new ArrayList<>(processes.keySet());
    }
}
