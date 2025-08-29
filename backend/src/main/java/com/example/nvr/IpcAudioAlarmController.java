package com.example.nvr;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/nvr/ipc/audioAlarm")
public class IpcAudioAlarmController {

    @PostMapping(value = "/test", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> test(
            @RequestParam String host,
            @RequestParam String user,
            @RequestParam String pass,
            @RequestParam(defaultValue = "http") String scheme,
            @RequestParam(name = "httpPort", required = false) Integer httpPort,
            @RequestParam(name = "id") int id
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        String base = scheme + "://" + host + (httpPort != null ? (":" + httpPort) : "");
        String basic = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        List<Map<String, Object>> attempts = new ArrayList<>();
        // Try several common ISAPI endpoints across device firmwares
        List<RequestSpec> specs = new ArrayList<>();
        // Direct AudioAlarm test endpoint observed on device UI
        specs.add(new RequestSpec("PUT", base + "/ISAPI/Event/triggers/notifications/AudioAlarm/" + id + "/test?format=json", null));
        // Some variants may omit notifications
        specs.add(new RequestSpec("PUT", base + "/ISAPI/Event/triggers/AudioAlarm/" + id + "/test?format=json", null));

        // Digital output fallback endpoints
        specs.add(new RequestSpec("PUT", base + "/ISAPI/System/IO/outputs/" + id + "/status",
                xml("IOPortData", Map.of(
                        "id", String.valueOf(id),
                        "outputState", "high",
                        "pulseDuration", "1000"
                ))));
        specs.add(new RequestSpec("PUT", base + "/ISAPI/IO/outputs/" + id + "/status",
                xml("IOPortData", Map.of(
                        "id", String.valueOf(id),
                        "outputState", "high",
                        "pulseDuration", "1000"
                ))));
        specs.add(new RequestSpec("POST", base + "/ISAPI/System/IO/outputs/" + id + "/trigger",
                xml("TriggerData", Map.of(
                        "id", String.valueOf(id),
                        "triggerState", "start",
                        "duration", "1"
                ))));
        specs.add(new RequestSpec("POST", base + "/ISAPI/IO/outputs/" + id + "/trigger",
                xml("TriggerData", Map.of(
                        "id", String.valueOf(id),
                        "triggerState", "start",
                        "duration", "1"
                ))));

        Integer successStatus = null;
        String successUrl = null;
        for (RequestSpec spec : specs) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("method", spec.method);
            rec.put("url", spec.url);
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(spec.url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Basic " + basic)
                        .header("Accept", "*/*");
                if (spec.body != null) {
                    b.header("Content-Type", "application/xml");
                }
                if ("PUT".equalsIgnoreCase(spec.method)) b = b.PUT(HttpRequest.BodyPublishers.ofString(spec.body == null ? "" : spec.body));
                else if ("POST".equalsIgnoreCase(spec.method)) b = b.POST(HttpRequest.BodyPublishers.ofString(spec.body == null ? "" : spec.body));
                else b = b.method(spec.method, HttpRequest.BodyPublishers.noBody());

                HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                int sc = resp.statusCode();
                rec.put("status", sc);
                // treat 2xx as success
                if (sc >= 200 && sc < 300) {
                    successStatus = sc;
                    successUrl = spec.url;
                    attempts.add(rec);
                    break;
                }

                // If 401, try digest: get challenge from response or via probe
                String wa = resp.headers().firstValue("WWW-Authenticate").orElse(null);
                if (sc == 401) {
                    if (wa == null || !wa.toLowerCase().startsWith("digest")) {
                        // probe same url without Authorization to retrieve digest challenge
                        try {
                            HttpRequest probe = HttpRequest.newBuilder(URI.create(spec.url))
                                    .timeout(Duration.ofSeconds(5))
                                    .header("Accept", "*/*")
                                    .GET().build();
                            HttpResponse<byte[]> pr = client.send(probe, HttpResponse.BodyHandlers.ofByteArray());
                            wa = pr.headers().firstValue("WWW-Authenticate").orElse(wa);
                        } catch (Exception ignored) { }
                        // if still missing, probe common info endpoint
                        if (wa == null) {
                            try {
                                String infoUrl = base + "/ISAPI/System/deviceInfo";
                                HttpRequest probe2 = HttpRequest.newBuilder(URI.create(infoUrl))
                                        .timeout(Duration.ofSeconds(5))
                                        .header("Accept", "*/*")
                                        .GET().build();
                                HttpResponse<byte[]> pr2 = client.send(probe2, HttpResponse.BodyHandlers.ofByteArray());
                                wa = pr2.headers().firstValue("WWW-Authenticate").orElse(null);
                            } catch (Exception ignored) { }
                        }
                    }
                }
                if (sc == 401 && wa != null && wa.toLowerCase().startsWith("digest")) {
                    Map<String, Object> rec2 = new LinkedHashMap<>();
                    rec2.put("method", spec.method);
                    rec2.put("url", spec.url);
                    rec2.put("auth", "Digest");
                    try {
                        String path = URI.create(spec.url).getRawPath();
                        String auth = buildDigestAuthHeader(wa, user, pass, spec.method, path, spec.body);
                        HttpRequest.Builder bd = HttpRequest.newBuilder(URI.create(spec.url))
                                .timeout(Duration.ofSeconds(5))
                                .header("Authorization", auth)
                                .header("Accept", "*/*");
                        if (spec.body != null) bd.header("Content-Type", "application/xml");
                        if ("PUT".equalsIgnoreCase(spec.method)) bd = bd.PUT(HttpRequest.BodyPublishers.ofString(spec.body == null ? "" : spec.body));
                        else if ("POST".equalsIgnoreCase(spec.method)) bd = bd.POST(HttpRequest.BodyPublishers.ofString(spec.body == null ? "" : spec.body));
                        else bd = bd.method(spec.method, HttpRequest.BodyPublishers.noBody());
                        HttpResponse<byte[]> r2 = client.send(bd.build(), HttpResponse.BodyHandlers.ofByteArray());
                        rec2.put("status", r2.statusCode());
                        String alg = parseDigestDirective(wa, "algorithm");
                        if (alg != null) rec2.put("algorithm", alg);
                        attempts.add(rec2);
                        if (r2.statusCode() >= 200 && r2.statusCode() < 300) {
                            successStatus = r2.statusCode();
                            successUrl = spec.url;
                            break;
                        }
                    } catch (Exception e2) {
                        rec2.put("error", e2.getMessage());
                        attempts.add(rec2);
                    }
                }
            } catch (Exception e) {
                rec.put("error", e.getMessage());
            }
            attempts.add(rec);
        }

        out.put("ok", successStatus != null);
        out.put("attempts", attempts);
        if (successStatus != null) {
            out.put("status", successStatus);
            out.put("used", successUrl);
        } else {
            out.put("error", "No audio trigger endpoint accepted request");
        }
        return ResponseEntity.ok(out);
    }

    private static String xml(String root, Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append('<').append(root).append('>');
        for (Map.Entry<String, String> e : kv.entrySet()) {
            sb.append('<').append(e.getKey()).append('>').append(escapeXml(e.getValue())).append("</").append(e.getKey()).append('>');
        }
        sb.append("</").append(root).append('>');
        return sb.toString();
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static class RequestSpec {
        final String method;
        final String url;
        final String body;
        RequestSpec(String method, String url, String body) {
            this.method = method;
            this.url = url;
            this.body = body;
        }
    }

    private String buildDigestAuthHeader(String wwwAuth, String user, String pass, String method, String uri, String body) throws Exception {
        Map<String, String> params = new HashMap<>();
        String s = wwwAuth.substring(wwwAuth.indexOf(' ') + 1);
        for (String part : s.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String val = kv[1].trim();
                if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
                params.put(key.toLowerCase(), val);
            }
        }
        String realm = params.getOrDefault("realm", "");
        String nonce = params.getOrDefault("nonce", "");
        String qopStr = params.getOrDefault("qop", "auth");
        String qop = qopStr;
        if (qopStr.contains("auth")) qop = "auth";
        else if (qopStr.contains("auth-int")) qop = "auth-int";
        String algorithm = params.getOrDefault("algorithm", "MD5");
        String opaque = params.get("opaque");

        String cnonce = java.util.UUID.randomUUID().toString().replaceAll("-", "");
        String nc = "00000001";

        String ha1;
        String ha2;
        String algoBase = algorithm.toUpperCase(Locale.ROOT);
        boolean sess = algoBase.endsWith("-SESS");
        String algoName = algoBase.replace("-SESS", "");
        if ("MD5".equals(algoName)) {
            String base = md("MD5", user + ":" + realm + ":" + pass);
            ha1 = sess ? md("MD5", base + ":" + nonce + ":" + cnonce) : base;
        } else if ("SHA-256".equals(algoName) || "SHA256".equals(algoName)) {
            String base = md("SHA-256", user + ":" + realm + ":" + pass);
            ha1 = sess ? md("SHA-256", base + ":" + nonce + ":" + cnonce) : base;
        } else {
            // fallback to MD5
            String base = md("MD5", user + ":" + realm + ":" + pass);
            ha1 = sess ? md("MD5", base + ":" + nonce + ":" + cnonce) : base;
        }

        if ("auth-int".equalsIgnoreCase(qop)) {
            String bodyHash = md(algoName.startsWith("SHA") ? "SHA-256" : "MD5", body == null ? "" : body);
            ha2 = md(algoName.startsWith("SHA") ? "SHA-256" : "MD5", method + ":" + uri + ":" + bodyHash);
        } else {
            ha2 = md(algoName.startsWith("SHA") ? "SHA-256" : "MD5", method + ":" + uri);
        }

        String response;
        if (qop != null && !qop.isEmpty()) {
            response = md(algoName.startsWith("SHA") ? "SHA-256" : "MD5", ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
        } else {
            response = md(algoName.startsWith("SHA") ? "SHA-256" : "MD5", ha1 + ":" + nonce + ":" + ha2);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Digest ");
        sb.append("username=\"").append(user).append("\",");
        sb.append("realm=\"").append(realm).append("\",");
        sb.append("nonce=\"").append(nonce).append("\",");
        sb.append("uri=\"").append(uri).append("\",");
        sb.append("response=\"").append(response).append("\",");
        if (qop != null && !qop.isEmpty()) sb.append("qop=").append(qop).append(",");
        sb.append("nc=").append(nc).append(",");
        sb.append("cnonce=\"").append(cnonce).append("\"");
        if (opaque != null && !opaque.isEmpty()) sb.append(",opaque=\"").append(opaque).append("\"");
        return sb.toString();
    }

    private String md(String algorithm, String s) throws Exception {
        String alg = algorithm;
        if (alg == null || alg.isEmpty()) alg = "MD5";
        if (!alg.equalsIgnoreCase("MD5") && !alg.equalsIgnoreCase("SHA-256")) alg = "MD5";
        java.security.MessageDigest md = java.security.MessageDigest.getInstance(alg.toUpperCase(Locale.ROOT));
        byte[] dig = md.digest(s.getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder sb = new StringBuilder();
        for (byte b : dig) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String parseDigestDirective(String wwwAuth, String key) {
        try {
            String s = wwwAuth.substring(wwwAuth.indexOf(' ') + 1);
            for (String part : s.split(",")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].trim().equalsIgnoreCase(key)) {
                    String val = kv[1].trim();
                    if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
                    return val;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
