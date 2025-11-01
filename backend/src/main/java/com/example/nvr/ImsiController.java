package com.example.nvr;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import com.example.nvr.imsi.ImsiSyncService;
import com.example.nvr.persistence.EventStorageService;
import com.example.nvr.persistence.ImsiRecordEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/imsi")
public class ImsiController {

    private static final Logger log = LoggerFactory.getLogger(ImsiController.class);

    private static final int DEFAULT_TIMEOUT_MS = 8000;
    private final EventStorageService eventStorageService;
    private final ImsiSyncService imsiSyncService;

    public ImsiController(EventStorageService eventStorageService,
                          ImsiSyncService imsiSyncService) {
        this.eventStorageService = eventStorageService;
        this.imsiSyncService = imsiSyncService;
    }

    @GetMapping(path = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object subscribe() {
        return ImsiHub.subscribe();
    }


    @PostMapping("/test-ftp")
    public ResponseEntity<FtpTestResponse> testFtp(@RequestBody FtpTestRequest request) {
        if (request == null) {
            return ResponseEntity.ok(FtpTestResponse.error("缺少请求参数"));
        }
        String host = trimToNull(request.getHost());
        if (host == null) {
            return ResponseEntity.ok(FtpTestResponse.error("请填写 FTP IP 地址"));
        }
        String user = trimToNull(request.getUser());
        String pass = trimToNull(request.getPass());
        if (user == null || pass == null) {
            return ResponseEntity.ok(FtpTestResponse.error("请填写 FTP 用户名与密码"));
        }
        int port = request.getPort() != null && request.getPort() > 0 && request.getPort() <= 65535 ? request.getPort() : 21;
        int timeout = request.getTimeoutMs() != null && request.getTimeoutMs() > 0 ? request.getTimeoutMs() : DEFAULT_TIMEOUT_MS;

        FTPClient client = new FTPClient();
        client.setConnectTimeout(timeout);
        client.setDefaultTimeout(timeout);
        client.setDataTimeout(timeout);

        long start = System.currentTimeMillis();
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            client.connect(host, port);
            details.put("connectReplyCode", client.getReplyCode());
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                return ResponseEntity.ok(FtpTestResponse.error("FTP 连接失败，回复码：" + client.getReplyCode(), details, start));
            }
            if (!client.login(user, pass)) {
                details.put("loginReplyCode", client.getReplyCode());
                return ResponseEntity.ok(FtpTestResponse.error("FTP 登录失败，回复码：" + client.getReplyCode(), details, start));
            }
            details.put("loginReplyCode", client.getReplyCode());
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            String workingDir = client.printWorkingDirectory();
            if (workingDir != null) {
                details.put("workingDirectory", workingDir);
            }
            String maskedPass = maskPassword(pass);
            details.put("host", host);
            details.put("port", port);
            details.put("user", user);
            details.put("pass", maskedPass);
            details.put("passive", true);
            details.put("fileType", "BINARY");
            return ResponseEntity.ok(FtpTestResponse.success("FTP 连接与登录成功", details, start));
        } catch (IOException ioe) {
            log.warn("Failed to connect to FTP {}:{}", host, port, ioe);
            return ResponseEntity.ok(FtpTestResponse.error(ioe.getClass().getSimpleName() + ": " + ioe.getMessage(), details, start));
        } finally {
            if (client.isConnected()) {
                try {
                    client.logout();
                } catch (IOException ignored) {
                }
                try {
                    client.disconnect();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @GetMapping("/records")
    public ResponseEntity<FtpRecordsResponse> listRecords(@RequestParam(name = "limit", defaultValue = "500") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 2000));
        try {
            List<ImsiRecordEntity> entities = eventStorageService.findRecentImsiRecords(safeLimit);
            if (entities.isEmpty()) {
                Map<String, Object> details = Map.of("limit", safeLimit);
                return ResponseEntity.ok(FtpRecordsResponse.success("数据库尚无 IMSI 数据", details, List.of(), List.of(), System.currentTimeMillis()));
            }

            List<ImsiRecord> recordDtos = new ArrayList<>();
            Set<String> sourceFiles = new LinkedHashSet<>();
            String host = null;
            Integer port = null;
            String directory = null;
            String message = null;
            Long lastElapsed = null;
            Instant lastFetchedAt = null;

            for (ImsiRecordEntity entity : entities) {
                if (host == null && entity.getHost() != null) host = entity.getHost();
                if (port == null && entity.getPort() != null) port = entity.getPort();
                if (directory == null && entity.getDirectory() != null) directory = entity.getDirectory();
                if (message == null && entity.getMessage() != null) message = entity.getMessage();
                if (lastElapsed == null && entity.getElapsedMs() != null) lastElapsed = entity.getElapsedMs();
                if (lastFetchedAt == null && entity.getFetchedAt() != null) lastFetchedAt = entity.getFetchedAt();

                String sourceFile = entity.getSourceFile();
                if (sourceFile != null && !sourceFile.isBlank()) {
                    sourceFiles.add(sourceFile);
                }

                if (entity.getDeviceId() != null || entity.getImsi() != null) {
                    recordDtos.add(new ImsiRecord(
                            entity.getDeviceId() != null ? entity.getDeviceId() : "",
                            entity.getImsi() != null ? entity.getImsi() : "",
                            entity.getOperatorCode() != null ? entity.getOperatorCode() : "",
                            entity.getArea() != null ? entity.getArea() : "",
                            entity.getRptDate() != null ? entity.getRptDate() : "",
                            entity.getRptTime() != null ? entity.getRptTime() : "",
                            entity.getSourceFile() != null ? entity.getSourceFile() : "",
                            entity.getLineNumber() != null ? entity.getLineNumber() : 0
                    ));
                }
            }

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("limit", safeLimit);
            details.put("recordsFound", recordDtos.size());
            details.put("recordsStored", entities.size());
            if (host != null) details.put("host", host);
            if (port != null) details.put("port", port);
            if (directory != null) details.put("directory", directory);
            if (lastElapsed != null) details.put("lastElapsedMs", lastElapsed);
            if (lastFetchedAt != null) details.put("lastFetchedAt", lastFetchedAt);

            String summary = message != null
                    ? message
                    : (recordDtos.isEmpty() ? "数据库尚无 IMSI 数据" : "已从数据库加载 " + recordDtos.size() + " 条 IMSI 记录");
            long start = System.currentTimeMillis();
            if (lastElapsed != null) {
                start = Math.max(0, System.currentTimeMillis() - lastElapsed);
            }

            return ResponseEntity.ok(FtpRecordsResponse.success(summary, details, recordDtos, new ArrayList<>(sourceFiles), start));
        } catch (Exception ex) {
            log.error("Failed to load IMSI records from database", ex);
            return ResponseEntity.ok(FtpRecordsResponse.error("数据库读取失败: " + ex.getMessage()));
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<FtpRecordsResponse> syncRecords() {
        com.example.nvr.imsi.dto.FtpRecordsResponse response = imsiSyncService.triggerManualSync();
        return ResponseEntity.ok(FtpRecordsResponse.fromServiceResponse(response));
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String maskPassword(String pass) {
        if (pass == null || pass.isEmpty()) return "";
        int len = pass.length();
        if (len <= 2) {
            return "*".repeat(len);
        }
        return pass.charAt(0) + "*".repeat(len - 2) + pass.charAt(len - 1);
    }

    private static Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                cleaned.put(entry.getKey(), entry.getValue());
            }
        }
        return cleaned.isEmpty() ? Map.of() : Collections.unmodifiableMap(cleaned);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FtpTestRequest {
        private String host;
        private Integer port;
        private String user;
        private String pass;
        private Integer timeoutMs;
        private Integer intervalSeconds;
        private Integer batchSize;
        private String filenameTemplate;
        private String lineTemplate;

        public FtpTestRequest() {
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

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPass() {
            return pass;
        }

        public void setPass(String pass) {
            this.pass = pass;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Integer getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(Integer intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public Integer getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(Integer batchSize) {
            this.batchSize = batchSize;
        }

        public String getFilenameTemplate() {
            return filenameTemplate;
        }

        public void setFilenameTemplate(String filenameTemplate) {
            this.filenameTemplate = filenameTemplate;
        }

        public String getLineTemplate() {
            return lineTemplate;
        }

        public void setLineTemplate(String lineTemplate) {
            this.lineTemplate = lineTemplate;
        }
    }

    public static class FtpTestResponse {
        private final boolean ok;
        private final String message;
        private final Map<String, Object> details;
        private final long elapsedMs;
        private final Instant timestamp = Instant.now();

        private FtpTestResponse(boolean ok, String message, Map<String, Object> details, long elapsedMs) {
            this.ok = ok;
            this.message = message;
            this.details = sanitizeDetails(details);
            this.elapsedMs = elapsedMs;
        }

        public static FtpTestResponse success(String message, Map<String, Object> details, long start) {
            long elapsed = System.currentTimeMillis() - start;
            return new FtpTestResponse(true, message, details, elapsed);
        }

        public static FtpTestResponse error(String message) {
            return new FtpTestResponse(false, message, Map.of(), 0);
        }

        public static FtpTestResponse error(String message, Map<String, Object> details, long start) {
            long elapsed = System.currentTimeMillis() - start;
            return new FtpTestResponse(false, message, details, elapsed);
        }

        public boolean isOk() {
            return ok;
        }

        public String getMessage() {
            return message;
        }

        public Map<String, Object> getDetails() {
            return details;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public Instant getTimestamp() {
            return timestamp;
        }
    }

    public static class ImsiRecord {
        private final String deviceId;
        private final String imsi;
        private final String operator;
        private final String area;
        private final String rptDate;
        private final String rptTime;
        private final String sourceFile;
        private final int lineNumber;

        public ImsiRecord(String deviceId, String imsi, String operator, String area,
                          String rptDate, String rptTime, String sourceFile, int lineNumber) {
            this.deviceId = deviceId;
            this.imsi = imsi;
            this.operator = operator;
            this.area = area;
            this.rptDate = rptDate;
            this.rptTime = rptTime;
            this.sourceFile = sourceFile;
            this.lineNumber = lineNumber;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getImsi() {
            return imsi;
        }

        public String getOperator() {
            return operator;
        }

        public String getArea() {
            return area;
        }

        public String getRptDate() {
            return rptDate;
        }

        public String getRptTime() {
            return rptTime;
        }

        public String getSourceFile() {
            return sourceFile;
        }

        public int getLineNumber() {
            return lineNumber;
        }
    }

    public static class FtpRecordsResponse {
        private final boolean ok;
        private final String message;
        private final List<ImsiRecord> records;
        private final List<String> sourceFiles;
        private final Map<String, Object> details;
        private final long elapsedMs;
        private final Instant timestamp;

        private FtpRecordsResponse(boolean ok,
                                   String message,
                                   List<ImsiRecord> records,
                                   List<String> sourceFiles,
                                   Map<String, Object> details,
                                   long elapsedMs,
                                   Instant timestamp) {
            this.ok = ok;
            this.message = message;
            this.records = records == null ? Collections.emptyList() : List.copyOf(records);
            this.sourceFiles = sourceFiles == null ? Collections.emptyList() : List.copyOf(sourceFiles);
            this.details = sanitizeDetails(details);
            this.elapsedMs = elapsedMs;
            this.timestamp = timestamp == null ? Instant.now() : timestamp;
        }

        public static FtpRecordsResponse success(String message,
                                                 Map<String, Object> details,
                                                 List<ImsiRecord> records,
                                                 List<String> sourceFiles,
                                                 long start) {
            long elapsed = System.currentTimeMillis() - start;
            return new FtpRecordsResponse(true, message, records, sourceFiles, details, elapsed, Instant.now());
        }

        public static FtpRecordsResponse error(String message) {
            return new FtpRecordsResponse(false, message, Collections.emptyList(), Collections.emptyList(), Map.of(), 0, Instant.now());
        }

        public static FtpRecordsResponse error(String message,
                                               Map<String, Object> details,
                                               List<ImsiRecord> records,
                                               List<String> sourceFiles,
                                               long start) {
            long elapsed = System.currentTimeMillis() - start;
            return new FtpRecordsResponse(false, message, records, sourceFiles, details, elapsed, Instant.now());
        }

        public static FtpRecordsResponse fromServiceResponse(com.example.nvr.imsi.dto.FtpRecordsResponse response) {
            if (response == null) {
                return error("IMSI 同步结果为空");
            }
            List<ImsiRecord> mappedRecords = response.getRecords().stream()
                    .map(r -> new ImsiRecord(
                            r.getDeviceId(),
                            r.getImsi(),
                            r.getOperator(),
                            r.getArea(),
                            r.getRptDate(),
                            r.getRptTime(),
                            r.getSourceFile(),
                            r.getLineNumber()))
                    .collect(Collectors.toList());
            return new FtpRecordsResponse(
                    response.isOk(),
                    response.getMessage(),
                    mappedRecords,
                    response.getSourceFiles(),
                    response.getDetails(),
                    response.getElapsedMs(),
                    response.getTimestamp()
            );
        }

        public boolean isOk() {
            return ok;
        }

        public String getMessage() {
            return message;
        }

        public List<ImsiRecord> getRecords() {
            return records;
        }

        public List<String> getSourceFiles() {
            return sourceFiles;
        }

        public Map<String, Object> getDetails() {
            return details;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public Instant getTimestamp() {
            return timestamp;
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<FtpRecordsResponse> handleUnexpected(Exception ex) {
        FtpRecordsResponse resp = FtpRecordsResponse.error(
                "IMSI 接口内部错误: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                Map.of(),
                List.of(),
                List.of(),
                System.currentTimeMillis()
        );
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }

}
