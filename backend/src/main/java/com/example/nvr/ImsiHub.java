package com.example.nvr;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE hub for IMSI updates so that the frontend can refresh immediately after FTP import.
 */
public final class ImsiHub {

    private static final List<SseEmitter> SUBSCRIBERS = new CopyOnWriteArrayList<>();

    private ImsiHub() {
    }

    public static SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        SUBSCRIBERS.add(emitter);
        emitter.onCompletion(() -> SUBSCRIBERS.remove(emitter));
        emitter.onTimeout(() -> SUBSCRIBERS.remove(emitter));
        emitter.onError((e) -> SUBSCRIBERS.remove(emitter));
        try {
            emitter.send(Map.of(
                    "type", "imsi",
                    "event", "hello",
                    "timestamp", Instant.now().toString()
            ), MediaType.APPLICATION_JSON);
        } catch (IOException ignored) {
        }
        return emitter;
    }

    public static void broadcast(Map<String, Object> payload) {
        for (SseEmitter emitter : SUBSCRIBERS) {
            try {
                emitter.send(payload, MediaType.APPLICATION_JSON);
            } catch (IOException ex) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
                SUBSCRIBERS.remove(emitter);
            }
        }
    }
}

