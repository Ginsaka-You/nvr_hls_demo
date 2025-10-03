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
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WebRtcStreamerService {

    private static final Logger log = LoggerFactory.getLogger(WebRtcStreamerService.class);

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

    @Value("${nvr.webrtc.extraArgs:-o}")
    private String extraArgs;

    private Process process;
    private Thread logThread;
    private final AtomicBoolean starting = new AtomicBoolean(false);

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
                if (!extra.isBlank()) {
                    command.add(extra);
                }
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
}
