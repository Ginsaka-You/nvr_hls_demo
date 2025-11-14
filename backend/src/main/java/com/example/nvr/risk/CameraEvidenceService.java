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
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
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
    private final Map<String, Deque<SnapshotRecord>> snapshotHistory = new ConcurrentHashMap<>();

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
        return submitSnapshotInternal(channel, anchorTime, trigger, description, ruleId, score, false);
    }

    public boolean submitSnapshotImmediate(String channel,
                                           Instant anchorTime,
                                           String trigger,
                                           String description,
                                           String ruleId,
                                           double score) {
        return submitSnapshotInternal(channel, anchorTime, trigger, description, ruleId, score, true);
    }

    private boolean submitSnapshotInternal(String channel,
                                           Instant anchorTime,
                                           String trigger,
                                           String description,
                                           String ruleId,
                                           double score,
                                           boolean bypassThrottle) {
        String normalizedChannel = normalizeChannel(channel);
        if (normalizedChannel == null) {
            log.debug("Skip snapshot trigger {} because channel is empty", trigger);
            return false;
        }
        Instant effectiveTime = anchorTime != null ? anchorTime : Instant.now();
        Object lock = lockFor(normalizedChannel);
        synchronized (lock) {
            Instant last = lastSnapshotByChannel.get(normalizedChannel);
            boolean throttled = last != null && Duration.between(last, effectiveTime).compareTo(snapshotThrottle) < 0;
            if (!bypassThrottle && throttled) {
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
            SnapshotLocation location = resolveLocation(channel, anchorTime, "snapshot", trigger, "jpg");
            Files.createDirectories(location.absolutePath().getParent());
            Files.write(location.absolutePath(), body);
            writeMetadata(location.absolutePath(), Map.of(
                    "type", "snapshot",
                    "channel", channel,
                    "trigger", trigger,
                    "description", description,
                    "rule", ruleId,
                    "score", score,
                    "capturedAt", Instant.now(),
                    "anchorTime", anchorTime
            ));
            rememberSnapshot(channel, location.timestamp(), location.relativePath(), body);
            log.info("Saved snapshot {} for channel {} at {}", trigger, channel, location.absolutePath());
        } catch (IOException | InterruptedException ex) {
            log.warn("Snapshot {} failed: {}", trigger, ex.getMessage());
            releaseSnapshotSlot(channel, anchorTime);
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Optional<String> findSnapshotPath(String channel, Instant referenceTime) {
        return findSnapshotRecord(channel, referenceTime)
                .map(SnapshotRecord::relativePath);
    }

    public Optional<String> findLatestSnapshotPath(String channel) {
        return findSnapshotRecord(channel, null)
                .map(SnapshotRecord::relativePath);
    }

    public Optional<byte[]> loadSnapshotBytes(String channel, Instant referenceTime) {
        return loadSnapshotBytes(channel, referenceTime, Duration.ofSeconds(3));
    }

    public Optional<byte[]> captureAndLoadSnapshotBytes(String channel,
                                                        Instant anchorTime,
                                                        String trigger,
                                                        String description,
                                                        String ruleId,
                                                        double score,
                                                        Duration waitTimeout) {
        submitSnapshotImmediate(channel, anchorTime, trigger, description, ruleId, score);
        return loadSnapshotBytes(channel, anchorTime, waitTimeout == null ? Duration.ofSeconds(3) : waitTimeout);
    }

    public Optional<byte[]> loadSnapshotBytes(String channel,
                                              Instant referenceTime,
                                              Duration waitTimeout) {
        Duration effective = waitTimeout == null ? Duration.ZERO : waitTimeout;
        if (effective.isNegative()) {
            effective = Duration.ZERO;
        }
        long timeoutNanos = effective.toNanos();
        long start = System.nanoTime();
        Optional<byte[]> result = tryLoadSnapshotBytes(channel, referenceTime);
        while (result.isEmpty() && (System.nanoTime() - start) < timeoutNanos) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            result = tryLoadSnapshotBytes(channel, referenceTime);
        }
        return result;
    }

    private Optional<byte[]> tryLoadSnapshotBytes(String channel, Instant referenceTime) {
        return findSnapshotRecord(channel, referenceTime)
                .flatMap(this::readSnapshotBytes);
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

    private SnapshotLocation resolveLocation(String channel,
                                             Instant anchorTime,
                                             String type,
                                             String trigger,
                                             String extension) {
        Instant ts = anchorTime != null ? anchorTime : Instant.now();
        String safeType = (type == null || type.isBlank()) ? "snapshot" : type.toLowerCase(Locale.ROOT);
        String safeTrigger = (trigger == null || trigger.isBlank()) ? safeType : trigger.toLowerCase(Locale.ROOT);
        String filename = String.format(Locale.ROOT, "%s_%s_%s.%s",
                TS_FORMAT.format(ts), safeType, safeTrigger, extension);
        Path relative = Paths.get(channel, DATE_FOLDER.format(ts), filename);
        Path absolute = evidenceRoot.resolve(relative).normalize();
        String relativePath = channel + "/" + DATE_FOLDER.format(ts) + "/" + filename;
        return new SnapshotLocation(absolute, relativePath, ts);
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

    private void rememberSnapshot(String channel, Instant capturedAt, String relativePath, byte[] bytes) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        Deque<SnapshotRecord> deque = snapshotHistory.computeIfAbsent(channel, key -> new ArrayDeque<>());
        synchronized (deque) {
            byte[] cached = (bytes == null || bytes.length == 0) ? null : bytes.clone();
            deque.addLast(new SnapshotRecord(capturedAt, relativePath, cached));
            while (deque.size() > 12) {
                deque.removeFirst();
            }
        }
    }

    private Optional<byte[]> readSnapshotBytes(SnapshotRecord record) {
        if (record == null) {
            return Optional.empty();
        }
        if (record.bytes != null && record.bytes.length > 0) {
            return Optional.of(record.bytes.clone());
        }
        return readSnapshotBytesFromDisk(record.relativePath);
    }

    private Optional<byte[]> readSnapshotBytesFromDisk(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return Optional.empty();
        }
        Path relative = Paths.get(relativePath.replace("\\", "/"));
        Path file = evidenceRoot.resolve(relative).normalize();
        if (!file.startsWith(evidenceRoot) || !Files.exists(file) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException ex) {
            log.debug("Failed to read snapshot {}: {}", relativePath, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<SnapshotRecord> findSnapshotRecord(String channel, Instant referenceTime) {
        String normalized = normalizeChannel(channel);
        if (normalized == null) {
            return Optional.empty();
        }
        Deque<SnapshotRecord> deque = snapshotHistory.get(normalized);
        Instant reference = referenceTime != null ? referenceTime : Instant.now();
        SnapshotRecord best = null;
        long bestDiff = Long.MAX_VALUE;
        if (deque != null && !deque.isEmpty()) {
            synchronized (deque) {
                for (SnapshotRecord record : deque) {
                    if (record == null || record.relativePath == null || record.capturedAt == null) {
                        continue;
                    }
                    long diff = Math.abs(Duration.between(record.capturedAt, reference).toMillis());
                    if (best == null || diff < bestDiff) {
                        best = record;
                        bestDiff = diff;
                    }
                }
            }
        }
        if (best != null) {
            return Optional.of(best);
        }
        return findSnapshotPathOnDisk(normalized, referenceTime)
                .map(path -> new SnapshotRecord(reference, path, null));
    }

    private Optional<String> findSnapshotPathOnDisk(String channel, Instant referenceTime) {
        Path channelDir = evidenceRoot.resolve(channel);
        if (!Files.isDirectory(channelDir)) {
            return Optional.empty();
        }
        try {
            if (referenceTime != null) {
                Path dateDir = channelDir.resolve(DATE_FOLDER.format(referenceTime));
                return findLatestFileUnder(dateDir);
            }
            return findLatestFileUnder(channelDir);
        } catch (IOException ex) {
            log.debug("Failed to scan evidence directory {}: {}", channelDir, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> findLatestFileUnder(Path root) throws IOException {
        if (!Files.exists(root)) {
            return Optional.empty();
        }
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        if (Files.isDirectory(root)) {
            try (var stream = Files.list(root)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    if (Files.isDirectory(child)) {
                        Optional<String> nested = findLatestFileUnder(child);
                        if (nested.isPresent()) {
                            return nested;
                        }
                    } else if (Files.isRegularFile(child)) {
                        long time = Files.getLastModifiedTime(child).toMillis();
                        if (time > bestTime) {
                            bestTime = time;
                            best = child;
                        }
                    }
                }
            }
        } else if (Files.isRegularFile(root)) {
            best = root;
        }
        if (best == null) {
            return Optional.empty();
        }
        Path relative = evidenceRoot.relativize(best);
        return Optional.of(relative.toString().replace("\\", "/"));
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

    private static final class SnapshotLocation {
        private final Path absolutePath;
        private final String relativePath;
        private final Instant timestamp;

        private SnapshotLocation(Path absolutePath, String relativePath, Instant timestamp) {
            this.absolutePath = absolutePath;
            this.relativePath = relativePath;
            this.timestamp = timestamp;
        }

        Path absolutePath() {
            return absolutePath;
        }

        String relativePath() {
            return relativePath;
        }

        Instant timestamp() {
            return timestamp;
        }
    }

    private static final class SnapshotRecord {
        private final Instant capturedAt;
        private final String relativePath;
        private final byte[] bytes;

        private SnapshotRecord(Instant capturedAt, String relativePath, byte[] bytes) {
            this.capturedAt = capturedAt;
            this.relativePath = relativePath;
            this.bytes = bytes;
        }

        Instant capturedAt() {
            return capturedAt;
        }

        String relativePath() {
            return relativePath;
        }

        byte[] bytes() {
            return bytes;
        }
    }
}
