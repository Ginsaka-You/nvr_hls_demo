package com.example.nvr;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/webrtc")
public class WebRtcDiagnosticsController {

    private final WebRtcStreamerService streamerService;

    public WebRtcDiagnosticsController(WebRtcStreamerService streamerService) {
        this.streamerService = streamerService;
    }

    @GetMapping("/failures")
    public Map<String, Object> listFailures(@RequestParam(name = "since", defaultValue = "15000") long sinceMs) {
        long window = sanitizeWindow(sinceMs);
        List<Map<String, Object>> failures = streamerService.listRecentFailures(window);
        return Map.of(
            "since", window,
            "failures", failures
        );
    }

    @GetMapping("/failures/{channel}")
    public Map<String, Object> failureForChannel(@PathVariable String channel,
                                                 @RequestParam(name = "since", defaultValue = "15000") long sinceMs) {
        long window = sanitizeWindow(sinceMs);
        Map<String, Object> details = streamerService.describeFailure(channel, window);
        if (details == null) {
            Map<String, Object> none = new HashMap<>();
            none.put("channel", channel);
            none.put("status", "none");
            none.put("since", window);
            return none;
        }
        details.putIfAbsent("channel", channel);
        details.put("status", "failure");
        details.put("since", window);
        return details;
    }

    @DeleteMapping("/failures")
    public ResponseEntity<Void> clearFailures() {
        streamerService.clearFailures();
        return ResponseEntity.ok().build();
    }

    private long sanitizeWindow(long requested) {
        if (requested <= 0) {
            return 1_000L;
        }
        return Math.min(requested, 60_000L);
    }
}
