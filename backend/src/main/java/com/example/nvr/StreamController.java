package com.example.nvr;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final FfmpegService ffmpeg;

    public StreamController(FfmpegService ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> map = new HashMap<>();
        map.put("status", "ok");
        return map;
    }

    @GetMapping
    public List<String> list() {
        return ffmpeg.listStreams();
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@PathVariable String id,
            @RequestParam String rtspUrl,
            @RequestParam(defaultValue = "false") boolean copy) throws IOException {
        String hls = ffmpeg.start(id, rtspUrl, copy);
        return ResponseEntity.ok(Map.of("id", id, "hls", hls));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> stop(@PathVariable String id) {
        ffmpeg.stop(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable String id) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("running", ffmpeg.isRunning(id));
        map.put("hls", ffmpeg.getHlsUrl(id));
        map.put("probe", ffmpeg.probe(id));
        return map;
    }

    @GetMapping("/{id}/logs")
    public Map<String, Object> logs(@PathVariable String id, @RequestParam(defaultValue = "200") int lines) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("lines", lines);
        map.put("logs", ffmpeg.getLogs(id, Math.min(Math.max(lines, 1), 1000)));
        return map;
    }
}
