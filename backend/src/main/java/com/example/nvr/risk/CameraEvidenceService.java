package com.example.nvr.risk;

import com.example.nvr.config.SettingsConfig;
import com.example.nvr.config.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CameraEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(CameraEvidenceService.class);
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneId.systemDefault());

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Path evidenceRoot;
    private final Duration snapshotThrottle;
    private final ExecutorService executor;

    private final Map<String, Instant> lastSnapshotByChannel = new ConcurrentHashMap<>();
    private final Map<String, Object> channelLocks = new ConcurrentHashMap<>();

    public CameraEvidenceService(SettingsService settingsService,
                                 ObjectMapper objectMapper,
                                 @Value("${nvr.evidence.root:../evidence}") String evidenceRoot,
                                 @Value("${nvr.evidence.snapshotThrottleSeconds:30}") long snapshotThrottleSeconds) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.evidenceRoot = Paths.get(evidenceRoot).toAbsolutePath();
        this.snapshotThrottle = Duration.ofSeconds(Math.max(snapshotThrottleSeconds, 1));
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "camera-evidence-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    public boolean submitSnapshot(String channel,
                                  Instant anchorTime,
                                  String trigger,
                                  String description,
                                  String ruleId,
                                  double score) {
        String normalizedChannel = normalizeChannel(channel);
        if (normalizedChannel == null) {
            log.debug("Skip snapshot trigger {} because channel is empty", trigger);
            return false;
        }
        Instant effectiveTime = anchorTime != null ? anchorTime : Instant.now();
        Object lock = lockFor(normalizedChannel);
        synchronized (lock) {
            Instant last = lastSnapshotByChannel.get(normalizedChannel);
            if (last != null && Duration.between(last, effectiveTime).compareTo(snapshotThrottle) < 0) {
                log.debug("Skip snapshot for channel {} due to throttle", normalizedChannel);
                return false;
            }
            lastSnapshotByChannel.put(normalizedChannel, effectiveTime);
        }
        executor.submit(() -> captureSnapshotInternal(normalizedChannel, effectiveTime, trigger, description, ruleId, score));
        return true;
    }

    private void captureSnapshotInternal(String channel,
                                         Instant anchorTime,
                                         String trigger,
                                         String description,
                                         String ruleId,
                                         double score) {
        Optional<CameraCredentials> credentials = resolveCredentials();
        if (credentials.isEmpty()) {
            releaseSnapshotSlot(channel, anchorTime);
            return;
        }
        CameraCredentials creds = credentials.get();
        String url = buildSnapshotUrl(creds, channel);
        String authorization = buildBasicAuthorization(creds.user(), creds.password());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authorization)
                .header("Accept", "image/jpeg")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Snapshot {} failed with HTTP {}", trigger, response.statusCode());
                releaseSnapshotSlot(channel, anchorTime);
                return;
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                log.warn("Snapshot {} returned empty body", trigger);
                releaseSnapshotSlot(channel, anchorTime);
                return;
            }
            Path file = buildEvidencePath(channel, anchorTime, trigger, "snapshot", "jpg");
            Files.createDirectories(file.getParent());
            Files.write(file, body);
            writeMetadata(file, Map.of(
                    "type", "snapshot",
                    "channel", channel,
                    "trigger", trigger,
                    "description", description,
                    "rule", ruleId,
                    "score", score,
                    "capturedAt", Instant.now(),
                    "anchorTime", anchorTime
            ));
            log.info("Saved snapshot {} for channel {} at {}", trigger, channel, file);
        } catch (IOException | InterruptedException ex) {
            log.warn("Snapshot {} failed: {}", trigger, ex.getMessage());
            releaseSnapshotSlot(channel, anchorTime);
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void writeMetadata(Path file, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        Path metaFile = file.resolveSibling(replaceExtension(file.getFileName().toString(), "json"));
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), metadata);
        } catch (IOException ex) {
            log.debug("Failed to write metadata {}: {}", metaFile, ex.getMessage());
        }
    }

    private String replaceExtension(String filename, String ext) {
        int dot = filename.lastIndexOf('.');
        if (dot == -1) {
            return filename + '.' + ext;
        }
        return filename.substring(0, dot + 1) + ext;
    }

    private Path buildEvidencePath(String channel,
                                   Instant anchorTime,
                                   String trigger,
                                   String type,
                                   String extension) {
        Instant ts = anchorTime != null ? anchorTime : Instant.now();
        ZonedDateTime zoned = ZonedDateTime.ofInstant(ts, ZoneId.systemDefault());
        Path base = evidenceRoot
                .resolve(channel)
                .resolve(DATE_FOLDER.format(ts));
        String safeType = (type == null || type.isBlank()) ? "snapshot" : type.toLowerCase(Locale.ROOT);
        String safeTrigger = (trigger == null || trigger.isBlank()) ? safeType : trigger.toLowerCase(Locale.ROOT);
        String filename = String.format(Locale.ROOT, "%s_%s_%s.%s",
                TS_FORMAT.format(ts), safeType, safeTrigger, extension);
        return base.resolve(filename);
    }

    private Optional<CameraCredentials> resolveCredentials() {
        SettingsConfig config = settingsService.getCurrentConfig();
        if (config == null) {
            log.warn("Camera settings not available; skip evidence capture");
            return Optional.empty();
        }
        String host = trim(config.getNvrHost());
        String user = trim(config.getNvrUser());
        String pass = trim(config.getNvrPass());
        String scheme = trim(config.getNvrScheme());
        Integer httpPort = config.getNvrHttpPort();
        if (host == null || user == null || pass == null) {
            log.warn("Incomplete NVR credentials (host={}, user={}); skip evidence capture", host, user);
            return Optional.empty();
        }
        if (scheme == null || scheme.isEmpty()) {
            scheme = "http";
        }
        return Optional.of(new CameraCredentials(host, user, pass, scheme, httpPort));
    }

    private String buildSnapshotUrl(CameraCredentials creds, String channel) {
        StringBuilder sb = new StringBuilder();
        sb.append(creds.scheme()).append("://").append(creds.host());
        if (creds.httpPort() != null && creds.httpPort() > 0) {
            sb.append(':').append(creds.httpPort());
        }
        sb.append("/ISAPI/Streaming/channels/").append(channel).append("/picture");
        return sb.toString();
    }

    private String buildBasicAuthorization(String user, String pass) {
        String token = user + ":" + pass;
        String encoded = java.util.Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String normalizeChannel(String channel) {
        if (channel == null) {
            return null;
        }
        String trimmed = channel.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() >= 3) {
            return digits.substring(0, 3);
        }
        return digits.isEmpty() ? trimmed : digits;
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Object lockFor(String channel) {
        return channelLocks.computeIfAbsent(channel, key -> new Object());
    }

    private void releaseSnapshotSlot(String channel, Instant expected) {
        Object lock = lockFor(channel);
        synchronized (lock) {
            Instant current = lastSnapshotByChannel.get(channel);
            if (Objects.equals(current, expected)) {
                lastSnapshotByChannel.remove(channel);
            }
        }
    }

    private static final class CameraCredentials {
        private final String host;
        private final String user;
        private final String password;
        private final String scheme;
        private final Integer httpPort;

        private CameraCredentials(String host, String user, String password, String scheme, Integer httpPort) {
            this.host = host;
            this.user = user;
            this.password = password;
            this.scheme = scheme;
            this.httpPort = httpPort;
        }

        String host() {
            return host;
        }

        String user() {
            return user;
        }

        String password() {
            return password;
        }

        String scheme() {
            return scheme;
        }

        Integer httpPort() {
            return httpPort;
        }
    }
}
