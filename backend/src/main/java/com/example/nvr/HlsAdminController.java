package com.example.nvr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@RestController
@RequestMapping("/api/hls")
public class HlsAdminController {

    @Value("${nvr.hlsRoot:/var/www/streams}")
    private String hlsRoot;

    @Value("${nvr.webrtc.cacheDirs:/tmp/webrtc-streamer,/tmp/webrtc-streamer-html}")
    private String webrtcCacheDirs;

    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear(@RequestParam(value = "id", required = false) String id) {
        Map<String, Object> out = new HashMap<>();
        int deleted = 0;
        int errors = 0;
        List<String> affected = new ArrayList<>();
        long bytesFreed = 0L;

        File root = new File(hlsRoot);
        if (!root.exists() || !root.isDirectory()) {
            out.put("ok", false);
            out.put("error", "hlsRoot not accessible: " + root.getAbsolutePath());
            return ResponseEntity.ok(out);
        }

        List<File> targets = new ArrayList<>();
        if (id != null && !id.isBlank()) {
            targets.add(new File(root, id));
        } else {
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs != null) targets.addAll(Arrays.asList(dirs));
        }

        for (File dir : targets) {
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(".m3u8") || name.endsWith(".ts"));
            if (files == null) continue;
            for (File f : files) {
                try {
                    Path path = f.toPath();
                    if (Files.exists(path)) {
                        try {
                            bytesFreed += Files.size(path);
                        } catch (IOException ignored) {
                        }
                    }
                    Files.deleteIfExists(path);
                    deleted++;
                } catch (IOException e) {
                    errors++;
                }
            }
            affected.add(dir.getName());
        }

        List<String> clearedWebrtc = new ArrayList<>();
        int webrtcErrors = 0;
        for (String entry : parseCacheDirs(webrtcCacheDirs)) {
            if (entry.isBlank()) continue;
            Path cache = Paths.get(entry.trim());
            if (!Files.exists(cache)) {
                continue;
            }
            try {
                bytesFreed += deleteDirectoryRecursively(cache);
                clearedWebrtc.add(cache.toAbsolutePath().toString());
            } catch (IOException e) {
                webrtcErrors++;
            }
        }

        out.put("ok", true);
        out.put("deleted", deleted);
        out.put("errors", errors);
        out.put("affected", affected);
        out.put("root", root.getAbsolutePath());
        out.put("bytesFreed", bytesFreed);
        out.put("webrtcDirs", clearedWebrtc);
        out.put("webrtcErrors", webrtcErrors);
        return ResponseEntity.ok(out);
    }

    private List<String> parseCacheDirs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        String[] tokens = raw.split(",");
        List<String> result = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (token != null && !token.trim().isEmpty()) {
                result.add(token.trim());
            }
        }
        return result;
    }

    private long deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0L;
        }
        final long[] bytes = {0L};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs != null) {
                    bytes[0] += attrs.size();
                } else {
                    try {
                        bytes[0] += Files.size(file);
                    } catch (IOException ignored) {
                    }
                }
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes[0];
    }
}
