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

    @Value("#{'${nvr.ffmpegFlags:-rtsp_transport tcp -rtsp_flags prefer_tcp -analyzeduration 10000000 -probesize 20000000 -fflags nobuffer+genpts}'.split(' ')}")
    private List<String> extraFlags;

    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> logBuffers = new ConcurrentHashMap<>();
    private final Map<String, String> lastOutDir = new ConcurrentHashMap<>();
    private final Map<String, String> lastCmd = new ConcurrentHashMap<>();

    public synchronized String start(String id, String rtspUrl, boolean copy) throws IOException {
        Process existing = processes.get(id);
        if (existing != null) {
            if (existing.isAlive()) {
                return getHlsUrl(id);
            } else {
                processes.remove(id);
            }
        }
        File outDir = new File(hlsRoot, id);
        System.out.println("[ffmpeg " + id + "] hlsRoot=" + new File(hlsRoot).getAbsolutePath());
        if (!outDir.exists()) {
            if (!outDir.mkdirs()) {
                throw new IOException("Failed to create HLS directory: " + outDir.getAbsolutePath());
            }
        }
        if (!outDir.canWrite()) {
            throw new IOException("HLS directory not writable: " + outDir.getAbsolutePath());
        }
        lastOutDir.put(id, outDir.getAbsolutePath());
        // Clean old playlist/segments to avoid stale indices
        File[] old = outDir.listFiles((dir, name) -> name.endsWith(".m3u8") || name.endsWith(".ts"));
        if (old != null) {
            for (File f : old) {
                try { Files.deleteIfExists(f.toPath()); } catch (IOException ignored) {}
            }
        }

        // 构建 ffmpeg 命令
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y"); // overwrite outputs
        cmd.addAll(extraFlags);
        cmd.add("-i");
        cmd.add(rtspUrl);

        if (copy) {
            cmd.addAll(Arrays.asList("-c:v", "copy"));
        } else {
            int gop = Math.max(1, hlsTime * 25); // assume ~25fps for GOP sizing
            cmd.addAll(Arrays.asList(
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-tune", "zerolatency",
                    "-profile:v", "baseline",
                    "-level", "3.1",
                    "-pix_fmt", "yuv420p",
                    "-bf", "0",
                    "-g", String.valueOf(gop),
                    "-keyint_min", String.valueOf(gop),
                    "-x264-params", "scenecut=0:open-gop=0:keyint=" + gop + ":min-keyint=" + gop));
            // Force keyframes to align with HLS segment duration to improve continuity
            cmd.addAll(Arrays.asList("-force_key_frames", "expr:gte(t,n_forced*" + hlsTime + ")"));
        }

        // 音频：很多设备是 G.711，浏览器不认，转 AAC 最稳
        cmd.addAll(Arrays.asList("-c:a", "aac", "-ar", "44100", "-b:a", aacBitrate));

        // HLS 输出（低延迟、稳）
        StringBuilder flags = new StringBuilder();
        if (deleteSegments) {
            flags.append("delete_segments+");
        }
        flags.append("program_date_time+independent_segments+temp_file");

        cmd.addAll(Arrays.asList(
                "-f", "hls",
                "-hls_time", String.valueOf(hlsTime),
                "-hls_list_size", String.valueOf(hlsListSize),
                "-hls_allow_cache", "0",
                "-hls_flags", flags.toString(),
                // Use absolute file path for writing segments; playlist will list relative names
                "-hls_segment_filename", new File(outDir, "seg_%05d.ts").getAbsolutePath(),
                new File(outDir, "index.m3u8").getAbsolutePath()));

        String cmdLine = String.join(" ", cmd);
        System.out.println("[ffmpeg " + id + "] cmd=" + cmdLine);
        lastCmd.put(id, cmdLine);
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
                    Deque<String> buf = logBuffers.computeIfAbsent(id, k -> new ArrayDeque<>(512));
                    synchronized (buf) {
                        if (buf.size() >= 512) buf.pollFirst();
                        buf.addLast(line);
                    }
                }
            } catch (IOException ignored) {
            }
        });
        logThread.setDaemon(true);
        logThread.start();

        // Watcher to auto-remove process entry on exit
        Thread waitThread = new Thread(() -> {
            try {
                p.waitFor();
            } catch (InterruptedException ignored) {
            } finally {
                processes.remove(id);
                System.out.println("[ffmpeg " + id + "] exited with code " + p.exitValue());
            }
        });
        waitThread.setDaemon(true);
        waitThread.start();

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

    public String getOutDir(String id) {
        String path = lastOutDir.get(id);
        if (path == null) path = new File(hlsRoot, id).getAbsolutePath();
        return path;
    }

    public Map<String, Object> probe(String id) {
        Map<String, Object> m = new HashMap<>();
        String out = getOutDir(id);
        File dir = new File(out);
        File m3u8 = new File(dir, "index.m3u8");
        File[] segs = dir.exists() ? dir.listFiles((d, n) -> n.endsWith(".ts")) : null;
        m.put("outDir", out);
        m.put("exists", dir.exists());
        m.put("writable", dir.exists() && dir.canWrite());
        m.put("m3u8Exists", m3u8.exists());
        m.put("tsCount", segs == null ? 0 : segs.length);
        m.put("running", isRunning(id));
        m.put("cmd", lastCmd.get(id));
        return m;
    }

    public List<String> getLogs(String id, int lines) {
        Deque<String> buf = logBuffers.get(id);
        List<String> out = new ArrayList<>();
        if (buf == null) return out;
        synchronized (buf) {
            int skip = Math.max(0, buf.size() - lines);
            int i = 0;
            for (String s : buf) {
                if (i++ >= skip) out.add(s);
            }
        }
        return out;
    }
}
