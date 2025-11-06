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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AlertStreamIngestService {

    private static final Logger log = LoggerFactory.getLogger(AlertStreamIngestService.class);
    private static final String[] ALERT_PATHS = new String[] {
            "/ISAPI/Event/notification/alertStream",
            "/ISAPI/Event/notification/center/stream",
            "/ISAPI/Event/notification/alertStream?format=xml",
            "/ISAPI/Event/notification/alertStream?heartbeat=5&timeout=60"
    };

    private final SettingsService settingsService;
    private final EventStorageService eventStorageService;
    private final AlertEventParser alertEventParser;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AlertStreamIngestService(SettingsService settingsService,
                                    EventStorageService eventStorageService,
                                    AlertEventParser alertEventParser) {
        this.settingsService = settingsService;
        this.eventStorageService = eventStorageService;
        this.alertEventParser = alertEventParser;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hik-alert-ingest");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::runLoop);
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void runLoop() {
        while (running.get()) {
            try {
                SettingsConfig cfg = settingsService.getCurrentConfig();
                if (cfg == null || cfg.getNvrHost() == null || cfg.getNvrHost().isBlank()) {
                    sleep(5000);
                    continue;
                }
                streamOnce(cfg);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.warn("NVR alert stream ingestion failed: {}", ex.getMessage());
                sleep(3000);
            }
        }
    }

    private void streamOnce(SettingsConfig cfg) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String scheme = cfg.getNvrScheme() == null || cfg.getNvrScheme().isBlank() ? "http" : cfg.getNvrScheme().trim();
        String host = cfg.getNvrHost().trim();
        Integer httpPort = cfg.getNvrHttpPort();
        String base = scheme + "://" + host + (httpPort != null && httpPort > 0 ? (":" + httpPort) : "");

        HttpResponse<InputStream> response = null;
        String usedPath = null;
        for (String path : ALERT_PATHS) {
            String url = base + path;
            response = openAlertStream(client, url, cfg.getNvrUser(), cfg.getNvrPass());
            if (response != null && response.statusCode() == 200) {
                usedPath = url;
                break;
            }
        }

        if (response == null || response.statusCode() != 200) {
            int status = response == null ? -1 : response.statusCode();
            log.warn("Failed to open Hikvision alert stream (status: {}), will retry", status);
            sleep(5000);
            return;
        }

        log.info("Connected to Hikvision alert stream at {}", usedPath);
        try (InputStream body = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder buffer = new StringBuilder();
            String line;
            String signature = signatureOf(cfg);
            while (running.get() && (line = reader.readLine()) != null) {
                buffer.append(line).append('\n');
                int start;
                while ((start = buffer.indexOf("<EventNotificationAlert")) >= 0) {
                    int end = buffer.indexOf("</EventNotificationAlert>", start);
                    if (end < 0) {
                        break;
                    }
                    String xml = buffer.substring(start, end + "</EventNotificationAlert>".length());
                    buffer.delete(0, end + "</EventNotificationAlert>".length());

                    Map<String, Object> event = alertEventParser.parse(xml);
                    boolean alertSaved = false;
                    boolean cameraSaved = false;
                    try {
                        alertSaved = eventStorageService.recordAlertEvent(event, xml);
                        cameraSaved = eventStorageService.recordCameraAlarm(event, xml);
                    } catch (Exception persistEx) {
                        log.warn("Failed to persist alert event: {}", persistEx.getMessage());
                    }
                    if (alertSaved || cameraSaved) {
                        AlertHub.broadcast(event);
                    }
                }

                if (!Objects.equals(signature, signatureOf(settingsService.getCurrentConfig()))) {
                    log.info("NVR alert configuration changed, reconnecting");
                    return;
                }
            }
        } finally {
            log.info("Hikvision alert stream connection closed, will reconnect");
        }
    }

    private HttpResponse<InputStream> openAlertStream(HttpClient client, String url, String user, String pass) throws Exception {
        String basic = java.util.Base64.getEncoder().encodeToString((safe(user) + ":" + safe(pass)).getBytes(StandardCharsets.UTF_8));
        HttpRequest reqBasic = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Basic " + basic)
                .header("Accept", "*/*")
                .GET().build();
        HttpResponse<InputStream> resp = client.send(reqBasic, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() == 200) {
            return resp;
        }

        HttpRequest probe = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "*/*")
                .GET().build();
        HttpResponse<InputStream> probeResp = client.send(probe, HttpResponse.BodyHandlers.ofInputStream());
        String wa = probeResp.headers().firstValue("WWW-Authenticate").orElse(null);
        if (wa != null && wa.toLowerCase().startsWith("digest")) {
            String auth = buildDigestAuthHeader(wa, safe(user), safe(pass), "GET", URI.create(url).getRawPath());
            HttpRequest reqDigest = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", auth)
                    .header("Accept", "*/*")
                    .GET().build();
            HttpResponse<InputStream> digestResp = client.send(reqDigest, HttpResponse.BodyHandlers.ofInputStream());
            if (digestResp.statusCode() == 200) {
                return digestResp;
            }
            return digestResp;
        }
        return resp;
    }

    private String buildDigestAuthHeader(String wwwAuth, String user, String pass, String method, String uri) throws Exception {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        String s = wwwAuth.substring(wwwAuth.indexOf(' ') + 1);
        for (String part : s.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String val = kv[1].trim();
                if (val.startsWith("\"") && val.endsWith("\"")) {
                    val = val.substring(1, val.length() - 1);
                }
                params.put(key.toLowerCase(), val);
            }
        }
        String realm = params.getOrDefault("realm", "");
        String nonce = params.getOrDefault("nonce", "");
        String qop = params.getOrDefault("qop", "auth");
        String opaque = params.get("opaque");

        String cnonce = java.util.UUID.randomUUID().toString().replaceAll("-", "");
        String nc = "00000001";

        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5(method + ":" + uri);
        String response;
        if (qop != null && !qop.isEmpty()) {
            response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + "auth" + ":" + ha2);
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Digest ");
        sb.append("username=\"").append(user).append("\",");
        sb.append("realm=\"").append(realm).append("\",");
        sb.append("nonce=\"").append(nonce).append("\",");
        sb.append("uri=\"").append(uri).append("\",");
        sb.append("response=\"").append(response).append("\",");
        sb.append("qop=auth,");
        sb.append("nc=").append(nc).append(",");
        sb.append("cnonce=\"").append(cnonce).append("\"");
        if (opaque != null && !opaque.isEmpty()) {
            sb.append(",opaque=\"").append(opaque).append("\"");
        }
        return sb.toString();
    }

    private String md5(String s) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] dig = md.digest(s.getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder sb = new StringBuilder();
        for (byte b : dig) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String signatureOf(SettingsConfig cfg) {
        if (cfg == null) {
            return "";
        }
        return (safe(cfg.getNvrScheme()) + "|" +
                safe(cfg.getNvrHost()) + "|" +
                (cfg.getNvrHttpPort() == null ? "" : cfg.getNvrHttpPort()) + "|" +
                safe(cfg.getNvrUser()) + "|" +
                safe(cfg.getNvrPass())).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
