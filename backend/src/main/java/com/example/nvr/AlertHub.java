package com.example.nvr;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class AlertHub {
    private static final List<SseEmitter> SUBS = new CopyOnWriteArrayList<>();

    public static SseEmitter subscribe() {
        SseEmitter em = new SseEmitter(0L);
        SUBS.add(em);
        em.onCompletion(() -> SUBS.remove(em));
        em.onTimeout(() -> SUBS.remove(em));
        em.onError((e) -> SUBS.remove(em));
        return em;
    }

    public static void broadcast(Map<String, Object> event) {
        for (SseEmitter em : SUBS) {
            try {
                em.send(event, MediaType.APPLICATION_JSON);
            } catch (IOException e) {
                try { em.complete(); } catch (Exception ignored) {}
                SUBS.remove(em);
            }
        }
    }
}

