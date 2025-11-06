package com.example.nvr;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/nvr/alerts")
public class AlertStreamController {

    private final AlertEventParser alertEventParser;

    public AlertStreamController(AlertEventParser alertEventParser) {
        this.alertEventParser = alertEventParser;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String host,
                             @RequestParam String user,
                             @RequestParam String pass,
                             @RequestParam(defaultValue = "http") String scheme,
                             @RequestParam(name = "httpPort", required = false) Integer httpPort) {
        final SseEmitter emitter = new SseEmitter(0L);

        Thread t = new Thread(() -> {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            String base = scheme + "://" + host + (httpPort != null ? (":" + httpPort) : "");
            String[] paths = new String[] {
                    "/ISAPI/Event/notification/alertStream",
                    "/ISAPI/Event/notification/center/stream",
                    "/ISAPI/Event/notification/alertStream?format=xml",
                    "/ISAPI/Event/notification/alertStream?heartbeat=5&timeout=60"
            };
            try {
                HttpResponse<InputStream> resp = null;
                String usedUrl = null;
                for (String p : paths) {
                    String u = base + p;
                    resp = openAlertStream(client, u, user, pass);
                    if (resp != null && resp.statusCode() == 200) { usedUrl = u; break; }
                }
                if (resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    Map<String, Object> err = new HashMap<>();
                    err.put("type", "error");
                    err.put("status", resp == null ? -1 : resp.statusCode());
                    err.put("message", "Failed to open alert stream (path/auth?)");
                    try { emitter.send(err); } catch (Exception ignored) {}
                    emitter.complete();
                    return;
                }

                try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                    String line;
                    StringBuilder buf = new StringBuilder();
                    while ((line = br.readLine()) != null) {
                        // Accumulate and extract EventNotificationAlert blocks
                        buf.append(line).append('\n');
                        int start;
                        while ((start = buf.indexOf("<EventNotificationAlert")) >= 0) {
                            int end = buf.indexOf("</EventNotificationAlert>", start);
                            if (end < 0) break; // wait for more
                            String xml = buf.substring(start, end + "</EventNotificationAlert>".length());
                            buf.delete(0, end + "</EventNotificationAlert>".length());

                            Map<String, Object> ev = alertEventParser.parse(xml);
                            if (CameraChannelBlocklist.shouldIgnore(ev)) {
                                continue;
                            }
                            try { emitter.send(ev); } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception e) {
                Map<String, Object> err = new HashMap<>();
                err.put("type", "error");
                err.put("message", e.getMessage());
                try { emitter.send(err); } catch (Exception ignored) {}
            } finally {
                emitter.complete();
            }
        }, "hik-alert-stream");
        t.setDaemon(true);
        t.start();

        return emitter;
    }

    private HttpResponse<InputStream> openAlertStream(HttpClient client, String url, String user, String pass) throws Exception {
        // Try Basic first
        String basic = java.util.Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        HttpRequest reqBasic = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Basic " + basic)
                .header("Accept", "*/*")
                .GET().build();
        HttpResponse<InputStream> resp = client.send(reqBasic, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() == 200) return resp;

        // If 401 with Digest
        HttpRequest reqProbe = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "*/*")
                .GET().build();
        HttpResponse<InputStream> probe = client.send(reqProbe, HttpResponse.BodyHandlers.ofInputStream());
        String wa = null;
        if (probe.headers().firstValue("WWW-Authenticate").isPresent()) {
            wa = probe.headers().firstValue("WWW-Authenticate").get();
        }
        if (wa != null && wa.toLowerCase().startsWith("digest")) {
            String auth = buildDigestAuthHeader(wa, user, pass, "GET", URI.create(url).getRawPath());
            HttpRequest reqDigest = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", auth)
                    .header("Accept", "*/*")
                    .GET().build();
            HttpResponse<InputStream> rd = client.send(reqDigest, HttpResponse.BodyHandlers.ofInputStream());
            if (rd.statusCode() == 200) return rd;
            return rd;
        }
        return resp;
    }

    private String buildDigestAuthHeader(String wwwAuth, String user, String pass, String method, String uri) throws Exception {
        // Parse directives
        Map<String, String> params = new HashMap<>();
        String s = wwwAuth.substring(wwwAuth.indexOf(' ')+1);
        for (String part : s.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String val = kv[1].trim();
                if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length()-1);
                params.put(key.toLowerCase(), val);
            }
        }
        String realm = params.getOrDefault("realm", "");
        String nonce = params.getOrDefault("nonce", "");
        String qop = params.getOrDefault("qop", "auth");
        String algorithm = params.getOrDefault("algorithm", "MD5");
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
        if (opaque != null && !opaque.isEmpty()) sb.append(",opaque=\"").append(opaque).append("\"");
        return sb.toString();
    }

    private String md5(String s) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] dig = md.digest(s.getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder sb = new StringBuilder();
        for (byte b : dig) sb.append(String.format("%02x", b));
        return sb.toString();
    }

}
