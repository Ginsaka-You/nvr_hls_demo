package com.example.nvr;

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
}

