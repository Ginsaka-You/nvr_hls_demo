package com.example.nvr;

import com.example.nvr.persistence.EventStorageService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/radar")
public class RadarController {

    private static final byte[] CMD_RESUME_OUTPUT = new byte[] { 0x55, (byte) 0xAA, 0x02, 0x00, 0x00, 0x09, 0x0B };
    private static final byte[] CMD_TARGET_MODE = new byte[] { 0x55, (byte) 0xAA, 0x02, 0x00, 0x00, 0x0B, 0x0D };
    private static final byte[] CMD_VERSION_REQUEST = new byte[] { 0x55, (byte) 0xAA, 0x02, 0x00, 0x00, 0x02, 0x04 };

    private static final int DEFAULT_CTRL_PORT = 20000;
    private static final int DEFAULT_TIMEOUT_MS = 1200;
    private static final int DEFAULT_TARGET_TIMEOUT_MS = 12000;
    private static final int MAX_FRAME_BYTES = 2048;

    private final EventStorageService eventStorageService;

    public RadarController(EventStorageService eventStorageService) {
        this.eventStorageService = eventStorageService;
    }

    @PostMapping("/test")
    public RadarTestResponse test(@RequestBody RadarTestRequest request) {
        String host = request == null ? null : trimToNull(request.getHost());
        if (host == null) {
            return RadarTestResponse.error("必须填写雷达 IP 地址");
        }

        int timeoutMs = request != null && request.getTimeoutMs() != null && request.getTimeoutMs() > 0
                ? request.getTimeoutMs() : DEFAULT_TARGET_TIMEOUT_MS;
        boolean useTcp = request == null || request.getUseTcp() == null ? true : request.getUseTcp();

        int ctrlPort = DEFAULT_CTRL_PORT;
        int dataPort = DEFAULT_CTRL_PORT;

        if (request != null) {
            if (isValidPort(request.getControlPort())) {
                ctrlPort = request.getControlPort();
            }
            if (isValidPort(request.getDataPort())) {
                dataPort = request.getDataPort();
            }
        }

        List<Integer> validRequestedPorts = new ArrayList<>();
        if (request != null && request.getPorts() != null) {
            for (Integer port : request.getPorts()) {
                if (isValidPort(port) && !validRequestedPorts.contains(port)) {
                    validRequestedPorts.add(port);
                }
            }
        }

        if (!validRequestedPorts.isEmpty()) {
            ctrlPort = validRequestedPorts.get(0);
            if (validRequestedPorts.size() > 1) {
                dataPort = validRequestedPorts.get(1);
            }
        }

        if (!isValidPort(dataPort)) {
            dataPort = ctrlPort;
        }

        List<RadarTestAttempt> attempts = new ArrayList<>();
        boolean ok;
        if (useTcp) {
            List<Integer> tcpPorts = new ArrayList<>();
            if (isValidPort(ctrlPort) && !tcpPorts.contains(ctrlPort)) {
                tcpPorts.add(ctrlPort);
            }
            if (isValidPort(dataPort) && dataPort != ctrlPort && !tcpPorts.contains(dataPort)) {
                tcpPorts.add(dataPort);
            }
            for (Integer port : validRequestedPorts) {
                if (isValidPort(port) && !tcpPorts.contains(port)) {
                    tcpPorts.add(port);
                }
            }
            if (tcpPorts.isEmpty()) {
                tcpPorts.add(DEFAULT_CTRL_PORT);
                if (!tcpPorts.contains(20001)) {
                    tcpPorts.add(20001);
                }
            }

            ok = false;
            for (Integer port : tcpPorts) {
                RadarTestAttempt attempt = attemptTcp(host, port, timeoutMs);
                attempts.add(attempt);
                if (attempt.isOk()) {
                    ok = true;
                }
            }
        } else {
            RadarTestAttempt controlAttempt = attemptUdpControl(host, ctrlPort, timeoutMs);
            attempts.add(controlAttempt);
            RadarTestAttempt dataAttempt = attemptUdpData(host, ctrlPort, dataPort, timeoutMs);
            attempts.add(dataAttempt);
            ok = controlAttempt.isOk() && dataAttempt.isOk();
        }

        if (attempts.isEmpty()) {
            return RadarTestResponse.error("没有可用端口进行测试");
        }
        return new RadarTestResponse(ok, host, timeoutMs, attempts, null);
    }

    @PostMapping("/targets")
    public RadarTargetsResponse targets(@RequestBody RadarTargetsRequest request) {
        int ctrlPort = request != null && request.getPort() != null && request.getPort() > 0 ? request.getPort() : DEFAULT_CTRL_PORT;
        int dataPort = request != null && request.getDataPort() != null && request.getDataPort() >= 0 ? request.getDataPort() : ctrlPort;
        boolean useTcp = request != null && Boolean.TRUE.equals(request.getUseTcp());
        String host = request == null ? null : trimToNull(request.getHost());
        if (host == null) {
            return RadarTargetsResponse.error(null, ctrlPort, dataPort, "必须填写雷达 IP 地址", useTcp);
        }
        int timeoutMs = request != null && request.getTimeoutMs() != null && request.getTimeoutMs() > 0
                ? request.getTimeoutMs() : DEFAULT_TARGET_TIMEOUT_MS;
        int maxFrames = request != null && request.getMaxFrames() != null && request.getMaxFrames() > 0
                ? Math.min(request.getMaxFrames(), 10) : 3;

        try {
            InetAddress address = InetAddress.getByName(host);
            if (useTcp) {
                sendControlCommands(address, ctrlPort, DEFAULT_TIMEOUT_MS);
                sendControlCommandsTcp(address, ctrlPort, DEFAULT_TIMEOUT_MS);

                List<Integer> candidatePorts = new ArrayList<>();
                if (dataPort > 0) {
                    candidatePorts.add(dataPort);
                }
                if (candidatePorts.isEmpty()) {
                    candidatePorts.add(ctrlPort);
                } else if (!candidatePorts.contains(ctrlPort)) {
                    candidatePorts.add(ctrlPort);
                }

                List<String> errors = new ArrayList<>();
                for (Integer candidate : candidatePorts) {
                    try {
                        RadarTargetsResponse resp = readTargetsViaTcp(host, address, ctrlPort, candidate, timeoutMs, maxFrames);
                        if (resp != null && resp.isOk()) {
                            eventStorageService.recordRadarTargets(resp);
                            return resp;
                        }
                        if (resp != null && resp.getError() != null) {
                            errors.add("端口 " + candidate + ": " + resp.getError());
                        } else {
                            errors.add("端口 " + candidate + ": 未收到目标数据");
                        }
                    } catch (Exception tcpEx) {
                        errors.add("端口 " + candidate + ": " + tcpEx.getMessage());
                    }
                }
                String message = errors.isEmpty() ? "未收到雷达目标数据" : String.join("; ", errors);
                return RadarTargetsResponse.error(host, ctrlPort, dataPort, message, true);
            } else {
                long start = System.currentTimeMillis();
                int listenPort = dataPort > 0 ? dataPort : 0;
                try (DatagramSocket socket = new DatagramSocket(listenPort)) {
                    socket.setSoTimeout(timeoutMs);
                    sendCommand(socket, address, ctrlPort, CMD_RESUME_OUTPUT);
                    sendCommand(socket, address, ctrlPort, CMD_TARGET_MODE);

                    for (int i = 0; i < maxFrames; i++) {
                        DatagramPacket packet = new DatagramPacket(new byte[MAX_FRAME_BYTES], MAX_FRAME_BYTES);
                        socket.receive(packet);
                        if (!packet.getAddress().equals(address)) {
                            continue;
                        }
                        int sourcePort = packet.getPort();
                        ParsedFrame frame = parseTargetFrame(packet.getData(), packet.getOffset(), packet.getLength(), sourcePort);
                        if (frame != null) {
                            long elapsed = System.currentTimeMillis() - start;
                            int localPort = socket.getLocalPort();
                            int selectedDataPort = localPort > 0 ? localPort : dataPort;
                            RadarTargetsResponse response = RadarTargetsResponse.success(host, ctrlPort, selectedDataPort, timeoutMs, elapsed, frame, false, sourcePort);
                            eventStorageService.recordRadarTargets(response);
                            return response;
                        }
                    }
                }
            }
        } catch (Exception e) {
            return RadarTargetsResponse.error(host, ctrlPort, dataPort, "雷达连接失败: " + e.getMessage(), useTcp);
        }
        return RadarTargetsResponse.error(host, ctrlPort, dataPort, "未收到雷达目标数据", useTcp);
    }

    private void sendControlCommands(InetAddress address, int ctrlPort, int timeoutMs) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            sendCommand(socket, address, ctrlPort, CMD_RESUME_OUTPUT);
            sendCommand(socket, address, ctrlPort, CMD_TARGET_MODE);
        } catch (Exception ignored) {
        }
    }

    private void sendControlCommandsTcp(InetAddress address, int ctrlPort, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, ctrlPort), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(CMD_RESUME_OUTPUT);
            out.flush();
            out.write(CMD_TARGET_MODE);
            out.flush();
        } catch (Exception ignored) {
            // Ignore failures; some devices may expect UDP only
        }
    }

    private RadarTargetsResponse readTargetsViaTcp(String host, InetAddress address, int ctrlPort, int port,
                                                   int timeoutMs, int maxFrames) throws IOException {
        long start = System.currentTimeMillis();
        try (Socket tcp = new Socket()) {
            tcp.connect(new InetSocketAddress(address, port), timeoutMs);
            tcp.setSoTimeout(timeoutMs);
            OutputStream out = tcp.getOutputStream();
            out.write(CMD_VERSION_REQUEST);
            out.flush();

            for (int i = 0; i < maxFrames; i++) {
                ParsedFrame frame = readFrameFromTcp(tcp);
                if (frame != null) {
                    long elapsed = System.currentTimeMillis() - start;
                    RadarTargetsResponse response = RadarTargetsResponse.success(host, ctrlPort, port, timeoutMs, elapsed, frame, true, frame.sourcePort);
                    eventStorageService.recordRadarTargets(response);
                    return response;
                }
            }
        }
        return null;
    }

    private void sendCommand(DatagramSocket socket, InetAddress address, int port, byte[] payload) {
        try {
            DatagramPacket packet = new DatagramPacket(payload, payload.length, address, port);
            socket.send(packet);
        } catch (Exception ignored) {
        }
    }

    private ParsedFrame parseTargetFrame(byte[] data, int offset, int length, int sourcePort) {
        if (length < 6) return null;
        if ((data[offset] & 0xFF) != 0x55 || (data[offset + 1] & 0xFF) != 0xAA) return null;
        int len = ((data[offset + 3] & 0xFF) << 8) | (data[offset + 2] & 0xFF);
        int expectedTotal = 4 + len + 1;
        if (len < 2 || length < expectedTotal) return null;
        int checksumIndex = offset + expectedTotal - 1;
        int computed = 0;
        for (int i = offset + 2; i < checksumIndex; i++) {
            computed = (computed + (data[i] & 0xFF)) & 0xFF;
        }
        int checksum = data[checksumIndex] & 0xFF;
        if (computed != checksum) return null;
        int status = data[offset + 4] & 0xFF;
        int targetCount = data[offset + 5] & 0xFF;
        int expectedDataLen = 2 + targetCount * 17;
        if (len != expectedDataLen) return null;
        List<RadarTargetDto> targets = new ArrayList<>();
        int cursor = offset + 6;
        for (int i = 0; i < targetCount; i++) {
            if (cursor + 17 > offset + 4 + len) {
                return null;
            }
            int id = data[cursor] & 0xFF;
            short rawLongitudinal = (short) ((data[cursor + 1] & 0xFF) | ((data[cursor + 2] & 0xFF) << 8));
            short rawLateral = (short) ((data[cursor + 3] & 0xFF) | ((data[cursor + 4] & 0xFF) << 8));
            short rawSpeed = (short) ((data[cursor + 5] & 0xFF) | ((data[cursor + 6] & 0xFF) << 8));
            int amplitude = data[cursor + 7] & 0xFF;
            int snr = data[cursor + 8] & 0xFF;
            int rcsRaw = (data[cursor + 9] & 0xFF) | ((data[cursor + 10] & 0xFF) << 8);
            int elementCount = data[cursor + 11] & 0xFF;
            int targetLength = data[cursor + 12] & 0xFF;
            int detectionFrames = data[cursor + 13] & 0xFF;
            int trackState = data[cursor + 14] & 0xFF;
            int reserve1 = data[cursor + 15] & 0xFF;
            int reserve2 = data[cursor + 16] & 0xFF;
            cursor += 17;

            double longitudinal = rawLongitudinal / 10.0;
            double lateral = rawLateral / 10.0;
            double speed = rawSpeed / 10.0;
            double range = Math.sqrt(longitudinal * longitudinal + lateral * lateral);
            double angle = Math.toDegrees(Math.atan2(lateral, longitudinal));
            double rcs = rcsRaw / 10.0;

            RadarTargetDto dto = new RadarTargetDto(id, longitudinal, lateral, speed, range, angle,
                    amplitude, snr, rcs, elementCount, targetLength, detectionFrames, trackState, reserve1, reserve2);
            targets.add(dto);
        }
        return new ParsedFrame(status, targetCount, targets, len, sourcePort);
    }

    private ParsedFrame readFrameFromTcp(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            byte[] header = new byte[4];
            int state = 0;
            int read;
            while (true) {
                read = in.read();
                if (read < 0) {
                    return null;
                }
                byte b = (byte) read;
                switch (state) {
                    case 0:
                        if ((b & 0xFF) == 0x55) {
                            header[0] = b;
                            state = 1;
                        }
                        break;
                    case 1:
                        if ((b & 0xFF) == 0xAA) {
                            header[1] = b;
                            state = 2;
                        } else {
                            if ((b & 0xFF) == 0x55) {
                                header[0] = b;
                                state = 1;
                            } else {
                                state = 0;
                            }
                        }
                        break;
                    case 2:
                        header[2] = b;
                        state = 3;
                        break;
                    case 3:
                        header[3] = b;
                        int len = ((header[3] & 0xFF) << 8) | (header[2] & 0xFF);
                        if (len <= 0 || len + 5 > MAX_FRAME_BYTES) {
                            state = 0;
                            skipBytes(in, len + 1);
                            break;
                        }
                        byte[] payload = new byte[len + 1];
                        if (!readFully(in, payload, 0, len + 1)) {
                            return null;
                        }
                        byte[] frame = new byte[4 + len + 1];
                        System.arraycopy(header, 0, frame, 0, 4);
                        System.arraycopy(payload, 0, frame, 4, len + 1);
                        ParsedFrame parsed = parseTargetFrame(frame, 0, frame.length, socket.getPort());
                        if (parsed != null) {
                            return parsed;
                        }
                        state = 0;
                        break;
                    default:
                        state = 0;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
    }

    private boolean readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(buf, off + total, len - total);
            if (r < 0) return false;
            total += r;
        }
        return true;
    }

    private void skipBytes(InputStream in, int len) throws IOException {
        if (len <= 0) return;
        long remaining = len;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    break;
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private RadarTestAttempt attemptTcp(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(CMD_VERSION_REQUEST);
            out.flush();

            byte[] buf = new byte[16];
            int read = in.read(buf);
            if (read <= 0) {
                throw new IOException("未收到响应数据");
            }

            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("localAddress", socket.getLocalAddress().getHostAddress());
            details.put("localPort", socket.getLocalPort());
            details.put("bytesReceived", read);
            return new RadarTestAttempt(port, true, "连接成功", elapsed, details);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new RadarTestAttempt(port, false, e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed, null);
        }
    }

    private RadarTestAttempt attemptUdpControl(String host, int ctrlPort, int timeoutMs) {
        long start = System.currentTimeMillis();
        if (!isValidPort(ctrlPort)) {
            return new RadarTestAttempt(ctrlPort, false, "控制端口无效", 0, null);
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(timeoutMs);
                sendCommand(socket, address, ctrlPort, CMD_RESUME_OUTPUT);
                sendCommand(socket, address, ctrlPort, CMD_TARGET_MODE);
                sendCommand(socket, address, ctrlPort, CMD_VERSION_REQUEST);
                long elapsed = System.currentTimeMillis() - start;
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("localPort", socket.getLocalPort());
                details.put("commandsSent", 3);
                return new RadarTestAttempt(ctrlPort, true, "已发送启动命令", elapsed, details);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new RadarTestAttempt(ctrlPort, false, e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed, null);
        }
    }

    private RadarTestAttempt attemptUdpData(String host, int ctrlPort, int dataPort, int timeoutMs) {
        long start = System.currentTimeMillis();
        int targetPort = isValidPort(dataPort) ? dataPort : ctrlPort;
        try {
            InetAddress address = InetAddress.getByName(host);
            int listenPort = isValidPort(targetPort) ? targetPort : 0;
            try (DatagramSocket socket = new DatagramSocket(listenPort)) {
                socket.setSoTimeout(timeoutMs);
                sendCommand(socket, address, ctrlPort, CMD_RESUME_OUTPUT);
                sendCommand(socket, address, ctrlPort, CMD_TARGET_MODE);
                sendCommand(socket, address, ctrlPort, CMD_VERSION_REQUEST);

                byte[] buffer = new byte[MAX_FRAME_BYTES];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                while (true) {
                    socket.receive(packet);
                    if (!packet.getAddress().equals(address)) {
                        continue;
                    }
                    long elapsed = System.currentTimeMillis() - start;
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("localPort", socket.getLocalPort());
                    details.put("remotePort", packet.getPort());
                    details.put("bytesReceived", packet.getLength());

                    ParsedFrame frame = parseTargetFrame(packet.getData(), packet.getOffset(), packet.getLength(), packet.getPort());
                    String message;
                    if (frame != null) {
                        details.put("targets", frame.targetCount);
                        details.put("payloadLength", frame.payloadLength);
                        message = frame.targetCount > 0 ? "收到 UDP 数据（目标 " + frame.targetCount + "）" : "收到 UDP 心跳";
                    } else {
                        message = "收到 UDP 数据";
                    }
                    return new RadarTestAttempt(targetPort, true, message, elapsed, details);
                }
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            int reportPort = isValidPort(targetPort) ? targetPort : ctrlPort;
            return new RadarTestAttempt(reportPort, false, e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed, null);
        }
    }

    private String trimToNull(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isValidPort(Integer port) {
        return port != null && port > 0 && port <= 65535;
    }

    private boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    private static class ParsedFrame {
        private final int status;
        private final int targetCount;
        private final List<RadarTargetDto> targets;
        private final int payloadLength;
        private final int sourcePort;

        private ParsedFrame(int status, int targetCount, List<RadarTargetDto> targets, int payloadLength, int sourcePort) {
            this.status = status;
            this.targetCount = targetCount;
            this.targets = targets;
            this.payloadLength = payloadLength;
            this.sourcePort = sourcePort;
        }
    }

    public static class RadarTargetDto {
        private final int id;
        private final double longitudinalDistance;
        private final double lateralDistance;
        private final double speed;
        private final double range;
        private final double angle;
        private final int amplitude;
        private final int snr;
        private final double rcs;
        private final int elementCount;
        private final int targetLength;
        private final int detectionFrames;
        private final int trackState;
        private final int reserve1;
        private final int reserve2;

        public RadarTargetDto(int id, double longitudinalDistance, double lateralDistance, double speed,
                               double range, double angle, int amplitude, int snr, double rcs, int elementCount,
                               int targetLength, int detectionFrames, int trackState, int reserve1, int reserve2) {
            this.id = id;
            this.longitudinalDistance = longitudinalDistance;
            this.lateralDistance = lateralDistance;
            this.speed = speed;
            this.range = range;
            this.angle = angle;
            this.amplitude = amplitude;
            this.snr = snr;
            this.rcs = rcs;
            this.elementCount = elementCount;
            this.targetLength = targetLength;
            this.detectionFrames = detectionFrames;
            this.trackState = trackState;
            this.reserve1 = reserve1;
            this.reserve2 = reserve2;
        }

        public int getId() {
            return id;
        }

        public double getLongitudinalDistance() {
            return longitudinalDistance;
        }

        public double getLateralDistance() {
            return lateralDistance;
        }

        public double getSpeed() {
            return speed;
        }

        public double getRange() {
            return range;
        }

        public double getAngle() {
            return angle;
        }

        public int getAmplitude() {
            return amplitude;
        }

        public int getSnr() {
            return snr;
        }

        public double getRcs() {
            return rcs;
        }

        public int getElementCount() {
            return elementCount;
        }

        public int getTargetLength() {
            return targetLength;
        }

        public int getDetectionFrames() {
            return detectionFrames;
        }

        public int getTrackState() {
            return trackState;
        }

        public int getReserve1() {
            return reserve1;
        }

        public int getReserve2() {
            return reserve2;
        }
    }

    public static class RadarTargetsRequest {
        private String host;
        private Integer port;
        private Integer dataPort;
        private Integer timeoutMs;
        private Integer maxFrames;
        private Boolean useTcp;

        public RadarTargetsRequest() {
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public Integer getDataPort() {
            return dataPort;
        }

        public void setDataPort(Integer dataPort) {
            this.dataPort = dataPort;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Integer getMaxFrames() {
            return maxFrames;
        }

        public void setMaxFrames(Integer maxFrames) {
            this.maxFrames = maxFrames;
        }

        public Boolean getUseTcp() {
            return useTcp;
        }

        public void setUseTcp(Boolean useTcp) {
            this.useTcp = useTcp;
        }
    }

    public static class RadarTargetsResponse {
        private final boolean ok;
        private final String host;
        private final int controlPort;
        private final int dataPort;
        private final int timeoutMs;
        private final long elapsedMs;
        private final Integer status;
        private final Integer targetCount;
        private final List<RadarTargetDto> targets;
        private final String error;
        private final Instant timestamp = Instant.now();
        private final Integer payloadLength;
        private final Integer actualDataPort;
        private final boolean tcp;

        private RadarTargetsResponse(boolean ok, String host, int controlPort, int dataPort, int timeoutMs,
                                     long elapsedMs, Integer status, Integer targetCount, List<RadarTargetDto> targets,
                                     String error, Integer payloadLength, Integer actualDataPort, boolean tcp) {
            this.ok = ok;
            this.host = host;
            this.controlPort = controlPort;
            this.dataPort = dataPort;
            this.timeoutMs = timeoutMs;
            this.elapsedMs = elapsedMs;
            this.status = status;
            this.targetCount = targetCount;
            this.targets = targets == null ? List.of() : targets;
            this.error = error;
            this.payloadLength = payloadLength;
            this.actualDataPort = actualDataPort;
            this.tcp = tcp;
        }

        public static RadarTargetsResponse success(String host, int controlPort, int dataPort, int timeoutMs,
                                                   long elapsedMs, ParsedFrame frame, boolean tcp, int actualPort) {
            return new RadarTargetsResponse(true, host, controlPort, dataPort, timeoutMs, elapsedMs,
                    frame.status, frame.targetCount, frame.targets, null, frame.payloadLength, actualPort, tcp);
        }

        public static RadarTargetsResponse error(String host, int controlPort, int dataPort, String message, boolean tcp) {
            return new RadarTargetsResponse(false, host, controlPort, dataPort, 0, 0,
                    null, null, List.of(), message, null, null, tcp);
        }

        public boolean isOk() {
            return ok;
        }

        public String getHost() {
            return host;
        }

        public int getControlPort() {
            return controlPort;
        }

        public int getDataPort() {
            return dataPort;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public Integer getStatus() {
            return status;
        }

        public Integer getTargetCount() {
            return targetCount;
        }

        public List<RadarTargetDto> getTargets() {
            return targets;
        }

        public String getError() {
            return error;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public Integer getPayloadLength() {
            return payloadLength;
        }

        public Integer getActualDataPort() {
            return actualDataPort;
        }

        public boolean isTcp() {
            return tcp;
        }
    }

    public static class RadarTestRequest {
        private String host;
        private List<Integer> ports;
        private Integer timeoutMs;
        private Integer controlPort;
        private Integer dataPort;
        private Boolean useTcp;

        public RadarTestRequest() {
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public List<Integer> getPorts() {
            return ports;
        }

        public void setPorts(List<Integer> ports) {
            this.ports = ports;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Integer getControlPort() {
            return controlPort;
        }

        public void setControlPort(Integer controlPort) {
            this.controlPort = controlPort;
        }

        public Integer getDataPort() {
            return dataPort;
        }

        public void setDataPort(Integer dataPort) {
            this.dataPort = dataPort;
        }

        public Boolean getUseTcp() {
            return useTcp;
        }

        public void setUseTcp(Boolean useTcp) {
            this.useTcp = useTcp;
        }
    }

    public static class RadarTestAttempt {
        private final int port;
        private final boolean ok;
        private final String message;
        private final long elapsedMs;
        private final Map<String, Object> details;

        public RadarTestAttempt(int port, boolean ok, String message, long elapsedMs, Map<String, Object> details) {
            this.port = port;
            this.ok = ok;
            this.message = message;
            this.elapsedMs = elapsedMs;
            this.details = details;
        }

        public int getPort() {
            return port;
        }

        public boolean isOk() {
            return ok;
        }

        public String getMessage() {
            return message;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public Map<String, Object> getDetails() {
            return details;
        }
    }

    public static class RadarTestResponse {
        private final boolean ok;
        private final String host;
        private final int timeoutMs;
        private final List<RadarTestAttempt> attempts;
        private final String error;
        private final Instant timestamp = Instant.now();

        public RadarTestResponse(boolean ok, String host, int timeoutMs, List<RadarTestAttempt> attempts, String error) {
            this.ok = ok;
            this.host = host;
            this.timeoutMs = timeoutMs;
            this.attempts = attempts == null ? List.of() : attempts;
            this.error = error;
        }

        public static RadarTestResponse error(String message) {
            return new RadarTestResponse(false, null, 0, List.of(), message);
        }

        public boolean isOk() {
            return ok;
        }

        public String getHost() {
            return host;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public List<RadarTestAttempt> getAttempts() {
            return attempts;
        }

        public String getError() {
            return error;
        }

        public Instant getTimestamp() {
            return timestamp;
        }
    }
}
