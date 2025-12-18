package com.example.nvr;

import com.example.nvr.config.SettingsConfig;
import com.example.nvr.config.SettingsService;
import com.example.nvr.persistence.EventStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NvrAlertStreamService {

    private static final Logger log = LoggerFactory.getLogger(NvrAlertStreamService.class);
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(3);
    private static final Duration MISSING_CONFIG_DELAY = Duration.ofSeconds(5);
    private static final Pattern BOUNDARY_PATTERN = Pattern.compile("boundary=([^;]+)");

    private final SettingsService settingsService;
    private final AlertEventParser alertEventParser;
    private final EventStorageService eventStorageService;
    private final ExecutorService executor;
    private final AtomicReference<AlertStreamConfig> authConfig = new AtomicReference<>();
    private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

    private volatile boolean running = true;

    public NvrAlertStreamService(SettingsService settingsService,
                                 AlertEventParser alertEventParser,
                                 EventStorageService eventStorageService) {
        this.settingsService = settingsService;
        this.alertEventParser = alertEventParser;
        this.eventStorageService = eventStorageService;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nvr-alert-stream");
            t.setDaemon(true);
            return t;
        });
        Authenticator.setDefault(new NvrAuthenticator(authConfig));
    }

    @PostConstruct
    public void start() {
        executor.submit(this::runLoop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        HttpURLConnection conn = activeConnection.getAndSet(null);
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
            }
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void runLoop() {
        String lastConfigKey = null;
        while (running) {
            AlertStreamConfig config = resolveConfig();
            if (config == null) {
                sleep(MISSING_CONFIG_DELAY);
                continue;
            }
            authConfig.set(config);
            if (!Objects.equals(lastConfigKey, config.key())) {
                log.info("NVR alert stream configured for {}", config.describe());
                lastConfigKey = config.key();
            }
            try {
                streamAlerts(config);
            } catch (Exception ex) {
                if (running) {
                    log.warn("NVR alert stream disconnected: {}", ex.getMessage());
                }
            } finally {
                HttpURLConnection conn = activeConnection.getAndSet(null);
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
            sleep(RECONNECT_DELAY);
        }
    }

    private AlertStreamConfig resolveConfig() {
        SettingsConfig config = settingsService.getCurrentConfig();
        if (config == null) {
            return null;
        }
        String host = trim(config.getNvrHost());
        String user = trim(config.getNvrUser());
        String pass = trim(config.getNvrPass());
        String scheme = trim(config.getNvrScheme());
        Integer port = config.getNvrHttpPort();
        if (host == null || user == null || pass == null) {
            return null;
        }
        if (scheme == null || scheme.isEmpty()) {
            scheme = "http";
        }
        AlertStreamConfig resolved = AlertStreamConfig.from(host, user, pass, scheme, port);
        return resolved.isReady() ? resolved : null;
    }

    private void streamAlerts(AlertStreamConfig config) throws IOException {
        String url = config.alertStreamUrl();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        activeConnection.set(connection);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(0);
        connection.setRequestProperty("Accept", "multipart/mixed");
        connection.setRequestProperty("Connection", "Keep-Alive");
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("Unexpected response " + code);
        }
        String boundary = extractBoundary(connection.getHeaderField("Content-Type"));
        log.info("Connected to NVR alert stream {}", url);
        try (InputStream in = connection.getInputStream()) {
            consumeStream(in, boundary);
        }
    }

    private void consumeStream(InputStream in, String boundary) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String activeBoundary = boundary;
        String line;
        StringBuilder payload = null;
        boolean inPayload = false;
        while (running && (line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (activeBoundary == null && trimmed.startsWith("--")) {
                activeBoundary = trimmed;
            }
            if (activeBoundary != null && trimmed.startsWith(activeBoundary)) {
                if (payload != null && payload.length() > 0) {
                    handlePayload(payload.toString());
                }
                payload = new StringBuilder();
                inPayload = false;
                continue;
            }
            if (payload == null) {
                continue;
            }
            if (!inPayload) {
                if (trimmed.isEmpty()) {
                    inPayload = true;
                }
                continue;
            }
            payload.append(line).append('\n');
        }
        if (payload != null && payload.length() > 0) {
            handlePayload(payload.toString());
        }
    }

    private void handlePayload(String xml) {
        if (xml == null || xml.isBlank()) {
            return;
        }
        Map<String, Object> event = alertEventParser.parse(xml);
        boolean alertSaved = false;
        boolean cameraSaved = false;
        try {
            alertSaved = eventStorageService.recordAlertEvent(event, xml);
            cameraSaved = eventStorageService.recordCameraAlarm(event, xml);
        } catch (Exception ex) {
            log.debug("Failed to store NVR alert event: {}", ex.getMessage());
        }
        if (alertSaved || cameraSaved) {
            AlertHub.broadcast(event);
        }
    }

    private String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher matcher = BOUNDARY_PATTERN.matcher(contentType);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.startsWith("--")) {
            value = "--" + value;
        }
        return value;
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void sleep(Duration duration) {
        if (duration == null) {
            return;
        }
        try {
            Thread.sleep(Math.max(0L, duration.toMillis()));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class NvrAuthenticator extends Authenticator {
        private final AtomicReference<AlertStreamConfig> configRef;

        private NvrAuthenticator(AtomicReference<AlertStreamConfig> configRef) {
            this.configRef = configRef;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            AlertStreamConfig config = configRef.get();
            if (config == null) {
                return null;
            }
            String host = getRequestingHost();
            if (host != null && !host.equalsIgnoreCase(config.host)) {
                return null;
            }
            String scheme = getRequestingProtocol();
            if (scheme != null && !scheme.equalsIgnoreCase(config.scheme)) {
                return null;
            }
            int requestPort = getRequestingPort();
            int expectedPort = config.resolvePort();
            if (requestPort > 0 && expectedPort > 0 && requestPort != expectedPort) {
                return null;
            }
            return new PasswordAuthentication(config.user, config.pass.toCharArray());
        }
    }

    private static final class AlertStreamConfig {
        private final String host;
        private final String user;
        private final String pass;
        private final String scheme;
        private final Integer port;

        private AlertStreamConfig(String host, String user, String pass, String scheme, Integer port) {
            this.host = host;
            this.user = user;
            this.pass = pass;
            this.scheme = scheme;
            this.port = port;
        }

        static AlertStreamConfig from(String host, String user, String pass, String scheme, Integer port) {
            String normalizedHost = host;
            String normalizedScheme = scheme;
            Integer normalizedPort = port;
            if (host != null && host.contains("://")) {
                try {
                    java.net.URI uri = java.net.URI.create(host);
                    if (uri.getHost() != null) {
                        normalizedHost = uri.getHost();
                    }
                    if (normalizedScheme == null && uri.getScheme() != null) {
                        normalizedScheme = uri.getScheme();
                    }
                    if (normalizedPort == null && uri.getPort() > 0) {
                        normalizedPort = uri.getPort();
                    }
                } catch (Exception ignored) {
                }
            }
            if (normalizedScheme == null || normalizedScheme.isEmpty()) {
                normalizedScheme = "http";
            }
            return new AlertStreamConfig(normalizedHost, user, pass, normalizedScheme, normalizedPort);
        }

        boolean isReady() {
            return host != null && user != null && pass != null;
        }

        String alertStreamUrl() {
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(host);
            if (port != null && port > 0) {
                sb.append(':').append(port);
            }
            sb.append("/ISAPI/Event/notification/alertStream");
            return sb.toString();
        }

        int resolvePort() {
            if (port != null && port > 0) {
                return port;
            }
            return "https".equalsIgnoreCase(scheme) ? 443 : 80;
        }

        String key() {
            return String.join("|",
                    host == null ? "" : host.toLowerCase(Locale.ROOT),
                    user == null ? "" : user,
                    pass == null ? "" : pass,
                    scheme == null ? "" : scheme.toLowerCase(Locale.ROOT),
                    port == null ? "" : String.valueOf(port));
        }

        String describe() {
            String base = scheme + "://" + host + (port != null && port > 0 ? ":" + port : "");
            return base + " as " + user;
        }
    }
}
