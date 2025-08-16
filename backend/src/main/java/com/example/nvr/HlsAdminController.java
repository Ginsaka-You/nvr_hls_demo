package com.example.nvr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

@RestController
@RequestMapping("/api/hls")
public class HlsAdminController {

    @Value("${nvr.hlsRoot:/var/www/streams}")
    private String hlsRoot;

    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear(@RequestParam(value = "id", required = false) String id) {
        Map<String, Object> out = new HashMap<>();
        int deleted = 0;
        int errors = 0;
        List<String> affected = new ArrayList<>();

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
                    Files.deleteIfExists(f.toPath());
                    deleted++;
                } catch (IOException e) {
                    errors++;
                }
            }
            affected.add(dir.getName());
        }

        out.put("ok", true);
        out.put("deleted", deleted);
        out.put("errors", errors);
        out.put("affected", affected);
        out.put("root", root.getAbsolutePath());
        return ResponseEntity.ok(out);
    }
}

