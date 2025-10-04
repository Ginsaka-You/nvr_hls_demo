package com.example.nvr;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WebRtcStreamerService {

    private static final Logger log = LoggerFactory.getLogger(WebRtcStreamerService.class);
    private static final int FAILURE_HISTORY_LIMIT = 200;

    @Value("${nvr.webrtc.enabled:true}")
    private boolean enabled;

    @Value("${nvr.webrtc.binary:third_party/webrtc-streamer/bin/webrtc-streamer}")
    private String binaryPath;

    @Value("${nvr.webrtc.hostPort:0.0.0.0:8000}")
    private String hostPort;

    @Value("${nvr.webrtc.webRoot:third_party/webrtc-streamer/share/webrtc-streamer/html}")
    private String webRoot;

    @Value("${nvr.webrtc.config:third_party/webrtc-streamer/share/webrtc-streamer/config.json}")
    private String configPath;

    @Value("${nvr.webrtc.extraArgs:}")
    private String extraArgs;

    private Process process;
    private Thread logThread;
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final Object failureLock = new Object();
    private final Deque<FailureRecord> failureHistory = new ArrayDeque<>();
    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("WebRTC streamer auto-start disabled (nvr.webrtc.enabled=false)");
            return;
        }
        try {
            start();
        } catch (Exception e) {
            log.error("Failed to start WebRTC streamer", e);
            recordFailure("system", "startup", Map.of(
                "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            ));
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        stop();
    }

    public synchronized void start() throws IOException {
        if (!enabled) {
            log.debug("WebRTC streamer start skipped (disabled)");
            return;
        }
        if (process != null && process.isAlive()) {
            log.debug("WebRTC streamer already running (pid={})", process.pid());
            return;
        }
        if (!starting.compareAndSet(false, true)) {
            log.debug("WebRTC streamer start already in progress");
            return;
        }

        try {
            Path binary = resolve(binaryPath);
            if (binary == null) {
                throw new IOException("WebRTC streamer binary not found for path " + binaryPath);
            }
            if (!Files.isExecutable(binary)) {
                log.warn("WebRTC streamer binary is not executable: {}", binary);
            }

            List<String> command = new ArrayList<>();
            command.add(binary.toAbsolutePath().toString());

            if (!hostPort.isBlank()) {
                command.add("-H");
                command.add(hostPort);
            }
            Path resolvedWebRoot = resolve(webRoot);
            if (resolvedWebRoot != null && Files.isDirectory(resolvedWebRoot)) {
                command.add("-w");
                command.add(resolvedWebRoot.toAbsolutePath().toString());
            }
            Path resolvedConfig = resolve(configPath);
            if (resolvedConfig != null && Files.isRegularFile(resolvedConfig)) {
                command.add("-C");
                command.add(resolvedConfig.toAbsolutePath().toString());
            }
            for (String extra : tokenize(extraArgs)) {
                if (extra.isBlank()) {
                    continue;
                }
                if ("-o".equals(extra)) {
                    log.warn("Skipping '-o' flag for WebRTC streamer to avoid null-codec instability");
                    continue;
                }
                command.add(extra);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(binary.toAbsolutePath().getParent().toFile());

            log.info("Starting WebRTC streamer: {}", String.join(" ", command));
            process = pb.start();

            logThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[webrtc-streamer] {}", line);
                        handleStreamerLogLine(line);
                    }
                } catch (IOException ioe) {
                    log.debug("WebRTC streamer log reader stopped", ioe);
                }
            }, "webrtc-streamer-log");
            logThread.setDaemon(true);
            logThread.start();

            Thread waitThread = new Thread(() -> {
                try {
                    int exit = process.waitFor();
                    log.warn("WebRTC streamer exited with code {}", exit);
                    if (exit != 0) {
                        recordFailure("system", "exit", Map.of(
                            "exitCode", exit
                        ));
                        scheduleRestart(exit);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    synchronized (WebRtcStreamerService.this) {
                        process = null;
                    }
                }
            }, "webrtc-streamer-wait");
            waitThread.setDaemon(true);
            waitThread.start();
        } catch (IOException | RuntimeException e) {
            recordFailure("system", "launch", Map.of(
                "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            ));
            throw e;
        } finally {
            starting.set(false);
        }
    }

    public synchronized void stop() {
        if (process != null) {
            log.info("Stopping WebRTC streamer (pid={})", process.pid());
            process.destroy();
            process = null;
        }
    }

    private Path resolve(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Path attempt = Paths.get(value).normalize();
        if (Files.exists(attempt)) {
            return attempt.toAbsolutePath();
        }
        Path parent = Paths.get("..", value).normalize();
        if (Files.exists(parent)) {
            return parent.toAbsolutePath();
        }
        Path backendSibling = Paths.get("backend").resolve(value).normalize();
        if (Files.exists(backendSibling)) {
            return backendSibling.toAbsolutePath();
        }
        log.warn("WebRTC streamer path not found: {} (checked {} and {})", value, attempt, parent);
        return null;
    }

    private List<String> tokenize(String args) {
        List<String> tokens = new ArrayList<>();
        if (args == null || args.isBlank()) {
            return tokens;
        }
        StringTokenizer tokenizer = new StringTokenizer(args);
        while (tokenizer.hasMoreTokens()) {
            tokens.add(tokenizer.nextToken());
        }
        return tokens;
    }

    public List<Map<String, Object>> listRecentFailures(long windowMs) {
        long effectiveWindow = Math.max(windowMs, 0L);
        long cutoff = System.currentTimeMillis() - effectiveWindow;
        List<Map<String, Object>> results = new ArrayList<>();
        synchronized (failureLock) {
            for (FailureRecord record : failureHistory) {
                if (record.timestamp < cutoff) {
                    break;
                }
                results.add(recordAsMap(record));
            }
        }
        return results;
    }

    public Map<String, Object> describeFailure(String channel, long windowMs) {
        if (channel == null) {
            return null;
        }
        String normalized = channel.trim();
        if (normalized.isEmpty()) {
            normalized = "unknown";
        }
        long effectiveWindow = Math.max(windowMs, 0L);
        long cutoff = System.currentTimeMillis() - effectiveWindow;
        synchronized (failureLock) {
            for (FailureRecord record : failureHistory) {
                if (record.timestamp < cutoff) {
                    break;
                }
                if (record.channel.equalsIgnoreCase(normalized)) {
                    return recordAsMap(record);
                }
            }
        }
        return null;
    }

    public void clearFailures() {
        synchronized (failureLock) {
            failureHistory.clear();
        }
    }

    public void recordFailure(String channel, String reason) {
        Map<String, Object> details = new HashMap<>();
        if (reason != null && !reason.isBlank()) {
            details.put("message", reason);
        }
        recordFailure(channel, "error", details);
    }

    public void recordFailure(String channel, String code, Map<String, Object> details) {
        String normalizedChannel = (channel == null || channel.trim().isEmpty()) ? "unknown" : channel.trim();
        Map<String, Object> payload = new HashMap<>();
        if (details != null) {
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    payload.put(entry.getKey(), entry.getValue());
                }
            }
        }
        String normalizedCode = (code == null || code.isBlank()) ? "error" : code.trim();
        payload.put("code", normalizedCode);
        FailureRecord record = new FailureRecord(normalizedChannel, System.currentTimeMillis(), payload);
        synchronized (failureLock) {
            failureHistory.addFirst(record);
            while (failureHistory.size() > FAILURE_HISTORY_LIMIT) {
                failureHistory.removeLast();
            }
        }
    }

    private Map<String, Object> recordAsMap(FailureRecord record) {
        Map<String, Object> view = new HashMap<>(record.details);
        view.putIfAbsent("code", "error");
        view.put("timestamp", record.timestamp);
        view.put("channel", record.channel);
        return view;
    }

    private void handleStreamerLogLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (!line.contains("404")) {
            return;
        }
        String url = extractRtspUrl(line);
        if (url == null) {
            return;
        }
        String channelSuffix = extractChannelSuffix(url);
        if (channelSuffix == null) {
            return;
        }
        Map<String, Object> details = new HashMap<>();
        details.put("message", "RTSP 404 Not Found");
        details.put("rtspUrl", url);
        details.put("log", line);
        recordFailure("cam" + channelSuffix, "rtsp-404", details);
    }

    private String extractRtspUrl(String line) {
        int idx = line.indexOf("rtsp://");
        if (idx == -1) {
            return null;
        }
        int end = line.indexOf(' ', idx);
        if (end == -1) {
            end = line.length();
        }
        return line.substring(idx, end).trim();
    }

    private String extractChannelSuffix(String url) {
        final String marker = "/Streaming/Channels/";
        int idx = url.indexOf(marker);
        if (idx == -1) {
            return null;
        }
        int start = idx + marker.length();
        int end = start;
        while (end < url.length() && Character.isDigit(url.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        return url.substring(start, end);
    }

    private void scheduleRestart(int exitCode) {
        if (!enabled) {
            return;
        }
        log.warn("WebRTC streamer exit code {} – auto restart disabled; awaiting manual recovery", exitCode);
    }

    private static final class FailureRecord {
        private final String channel;
        private final long timestamp;
        private final Map<String, Object> details;

        private FailureRecord(String channel, long timestamp, Map<String, Object> details) {
            this.channel = channel;
            this.timestamp = timestamp;
            this.details = Collections.unmodifiableMap(new HashMap<>(details));
        }
    }
}
