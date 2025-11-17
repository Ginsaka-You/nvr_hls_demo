package com.example.nvr;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/alarm/sound-light")
public class SoundLightAlarmController {

    private static final String TARGET_HOST = "192.168.50.200";
    private static final int TARGET_PORT = 1000;
    private static final String ACTIVATE_HEX = "0110001A000101CE18";
    private static final String DEACTIVATE_HEX = "0110001A0001000FD8";
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 1500;

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate() {
        return sendCommand("activate", ACTIVATE_HEX);
    }

    @PostMapping("/deactivate")
    public ResponseEntity<Map<String, Object>> deactivate() {
        return sendCommand("deactivate", DEACTIVATE_HEX);
    }

    private ResponseEntity<Map<String, Object>> sendCommand(String action, String hexCommand) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("host", TARGET_HOST);
        payload.put("port", TARGET_PORT);
        payload.put("commandHex", hexCommand);

        Instant start = Instant.now();
        payload.put("timestamp", start.toString());

        byte[] bytes = hexToBytes(hexCommand);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(TARGET_HOST, TARGET_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            socket.shutdownOutput();

            byte[] response = readResponse(socket);
            if (response.length > 0) {
                payload.put("responseHex", bytesToHex(response));
            }
        } catch (IOException e) {
            payload.put("ok", false);
            payload.put("error", e.getMessage());
            payload.put("elapsedMs", Duration.between(start, Instant.now()).toMillis());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(payload);
        }

        payload.put("ok", true);
        payload.put("elapsedMs", Duration.between(start, Instant.now()).toMillis());
        return ResponseEntity.ok(payload);
    }

    private byte[] readResponse(Socket socket) throws IOException {
        InputStream inputStream = socket.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] temp = new byte[128];
        while (true) {
            try {
                int len = inputStream.read(temp);
                if (len == -1) {
                    break;
                }
                buffer.write(temp, 0, len);
                if (buffer.size() >= 512) {
                    break;
                }
            } catch (SocketTimeoutException e) {
                break;
            }
        }
        return buffer.toByteArray();
    }

    private byte[] hexToBytes(String hex) {
        String clean = hex == null ? "" : hex.trim();
        if ((clean.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex command must have an even length");
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < clean.length(); i += 2) {
            int value = Integer.parseInt(clean.substring(i, i + 2), 16);
            out[i / 2] = (byte) value;
        }
        return out;
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        char[] hexChars = new char[bytes.length * 2];
        char[] hexArray = "0123456789ABCDEF".toCharArray();
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}
