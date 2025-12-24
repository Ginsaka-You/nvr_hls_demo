package com.example.nvr;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/nvr")
public class NvrController {

    @GetMapping("/channels")
    public ResponseEntity<Map<String, Object>> channels(
            @RequestParam String host,
            @RequestParam String user,
            @RequestParam String pass,
            @RequestParam(defaultValue = "http") String scheme,
            @RequestParam(name = "httpPort", required = false) Integer httpPort
    ) {
        Map<String, Object> out = new HashMap<>();
        String base = scheme + "://" + host + (httpPort != null ? (":" + httpPort) : "");
        String url = base + "/ISAPI/Streaming/channels?format=json";
        String basic = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Basic " + basic)
                .GET().build();
        String body = null;
        int status = -1;
        try {
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            status = resp.statusCode();
            if (status >= 200 && status < 300) {
                body = new String(resp.body(), StandardCharsets.UTF_8);
            } else {
                // fallback to XML without ?format=json
                String urlXml = base + "/ISAPI/Streaming/channels";
                HttpRequest req2 = HttpRequest.newBuilder(URI.create(urlXml))
                        .header("Authorization", "Basic " + basic)
                        .GET().build();
                HttpResponse<byte[]> resp2 = client.send(req2, HttpResponse.BodyHandlers.ofByteArray());
                status = resp2.statusCode();
                if (status >= 200 && status < 300) {
                    body = new String(resp2.body(), StandardCharsets.UTF_8);
                    url = urlXml;
                }
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return ResponseEntity.ok(out);
        }

        if (body == null) {
            out.put("ok", false);
            out.put("status", status);
            out.put("error", "No response body");
            return ResponseEntity.ok(out);
        }

        Set<Integer> channelIds = new TreeSet<>();
        // Try JSON: "id" : 401
        Pattern pj = Pattern.compile("\\\"id\\\"\\s*:\\s*([0-9]{3,4})");
        Matcher mj = pj.matcher(body);
        while (mj.find()) {
            try { channelIds.add(Integer.parseInt(mj.group(1))); } catch (NumberFormatException ignored) {}
        }
        // Try XML: <id>401</id>
        Pattern px = Pattern.compile("<id>\\s*([0-9]{3,4})\\s*</id>");
        Matcher mx = px.matcher(body);
        while (mx.find()) {
            try { channelIds.add(Integer.parseInt(mx.group(1))); } catch (NumberFormatException ignored) {}
        }

        Set<Integer> ports = new TreeSet<>();
        for (Integer id : channelIds) {
            ports.add(id / 100);
        }

        int portCount = ports.isEmpty() ? 0 : Collections.max(ports);
        Map<String, Object> data = new HashMap<>();
        data.put("ok", true);
        data.put("url", url);
        data.put("channels", channelIds);
        data.put("ports", ports);
        data.put("portCount", portCount);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/snapshot")
    public ResponseEntity<byte[]> snapshot(
            @RequestParam String host,
            @RequestParam String user,
            @RequestParam String pass,
            @RequestParam String channel,
            @RequestParam(defaultValue = "http") String scheme,
            @RequestParam(name = "httpPort", required = false) Integer httpPort
    ) {
        String normalizedChannel = channel == null ? "" : channel.replaceAll("[^0-9]", "").trim();
        if (normalizedChannel.isEmpty()) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8");
            String err = "channel 参数必填";
            return new ResponseEntity<>(err.getBytes(StandardCharsets.UTF_8), headers, HttpStatus.BAD_REQUEST);
        }

        String base = scheme + "://" + host + (httpPort != null ? (":" + httpPort) : "");
        String url = base + "/ISAPI/Streaming/channels/" + normalizedChannel + "/picture";
        String basic = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Basic " + basic)
                .header("Accept", "image/jpeg")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = resp.statusCode();
            byte[] body = resp.body();
            if (status >= 200 && status < 300 && body != null && body.length > 0) {
                HttpHeaders headers = new HttpHeaders();
                String contentType = resp.headers().firstValue("Content-Type").orElse(MediaType.IMAGE_JPEG_VALUE);
                headers.set(HttpHeaders.CONTENT_TYPE, contentType);
                headers.setContentLength(body.length);
                return new ResponseEntity<>(body, headers, HttpStatus.OK);
            }
            String responseText = body != null && body.length > 0
                    ? new String(body, StandardCharsets.UTF_8)
                    : ("HTTP " + status);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8");
            String err = "抓拍失败：" + responseText;
            return new ResponseEntity<>(err.getBytes(StandardCharsets.UTF_8), headers, HttpStatus.BAD_GATEWAY);
        } catch (Exception ex) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8");
            String err = "抓拍失败：" + ex.getMessage();
            return new ResponseEntity<>(err.getBytes(StandardCharsets.UTF_8), headers, HttpStatus.BAD_GATEWAY);
        }
    }

    @GetMapping("/input-proxy-status")
    public ResponseEntity<Map<String, Object>> inputProxyStatus(
            @RequestParam String host,
            @RequestParam String user,
            @RequestParam String pass,
            @RequestParam(defaultValue = "http") String scheme,
            @RequestParam(name = "httpPort", required = false) Integer httpPort
    ) {
        Map<String, Object> out = new HashMap<>();
        String base = scheme + "://" + host + (httpPort != null ? (":" + httpPort) : "");
        String url = base + "/ISAPI/ContentMgmt/InputProxy/channels/status";
        HttpClient client = HttpClient.newBuilder().build();
        HttpResponse<byte[]> resp = null;
        try {
            String basic = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Basic " + basic)
                    .header("Accept", "application/xml")
                    .GET()
                    .build();
            resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 401) {
                String wa = resp.headers().firstValue("WWW-Authenticate").orElse(null);
                if (wa == null || !wa.toLowerCase(Locale.ROOT).startsWith("digest")) {
                    HttpRequest probe = HttpRequest.newBuilder(URI.create(url))
                            .header("Accept", "application/xml")
                            .GET()
                            .build();
                    HttpResponse<byte[]> probeResp = client.send(probe, HttpResponse.BodyHandlers.ofByteArray());
                    wa = probeResp.headers().firstValue("WWW-Authenticate").orElse(wa);
                }
                if (wa != null && wa.toLowerCase(Locale.ROOT).startsWith("digest")) {
                    String path = buildDigestPath(url);
                    String auth = buildDigestAuthHeader(wa, user, pass, "GET", path, null);
                    HttpRequest digestReq = HttpRequest.newBuilder(URI.create(url))
                            .header("Authorization", auth)
                            .header("Accept", "application/xml")
                            .GET()
                            .build();
                    resp = client.send(digestReq, HttpResponse.BodyHandlers.ofByteArray());
                }
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return ResponseEntity.ok(out);
        }

        if (resp == null || resp.body() == null || resp.body().length == 0) {
            out.put("ok", false);
            out.put("error", "No response body");
            return ResponseEntity.ok(out);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            out.put("ok", false);
            out.put("status", resp.statusCode());
            out.put("error", "HTTP " + resp.statusCode());
            return ResponseEntity.ok(out);
        }

        String body = new String(resp.body(), StandardCharsets.UTF_8);
        List<Map<String, Object>> statuses = new ArrayList<>();
        int onlineCount = 0;
        Pattern blockPattern = Pattern.compile("<InputProxyChannelStatus\\b[^>]*>(.*?)</InputProxyChannelStatus>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher blockMatcher = blockPattern.matcher(body);
        while (blockMatcher.find()) {
            String chunk = blockMatcher.group(1);
            String id = extractTag(chunk, "id");
            String onlineText = extractTag(chunk, "online");
            boolean online = "true".equalsIgnoreCase(onlineText);
            if (online) onlineCount++;
            List<String> streamIds = extractMultiTag(chunk, "streamingProxyChannelId");
            Map<String, Object> item = new HashMap<>();
            item.put("id", id);
            item.put("online", online);
            item.put("streamingProxyChannelIds", streamIds);
            statuses.add(item);
        }
        int totalCount = statuses.size();
        if (totalCount == 0) {
            Matcher onlineMatcher = Pattern.compile("<online>\\s*(true|false)\\s*</online>", Pattern.CASE_INSENSITIVE)
                    .matcher(body);
            while (onlineMatcher.find()) {
                totalCount++;
                if ("true".equalsIgnoreCase(onlineMatcher.group(1))) {
                    onlineCount++;
                }
            }
        }

        out.put("ok", true);
        out.put("onlineCount", onlineCount);
        out.put("totalCount", totalCount);
        out.put("items", statuses);
        return ResponseEntity.ok(out);
    }

    private String buildDigestPath(String url) {
        URI uri = URI.create(url);
        String path = uri.getRawPath();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            path += "?" + uri.getRawQuery();
        }
        return path;
    }

    private String extractTag(String xml, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + ">\\s*([^<]+)\\s*</" + tag + ">", Pattern.CASE_INSENSITIVE)
                .matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private List<String> extractMultiTag(String xml, String tag) {
        List<String> items = new ArrayList<>();
        Matcher matcher = Pattern.compile("<" + tag + ">\\s*([^<]+)\\s*</" + tag + ">", Pattern.CASE_INSENSITIVE)
                .matcher(xml);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!value.isEmpty()) items.add(value);
        }
        return items;
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
                params.put(key.toLowerCase(Locale.ROOT), val);
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

        String cnonce = UUID.randomUUID().toString().replaceAll("-", "");
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
}
