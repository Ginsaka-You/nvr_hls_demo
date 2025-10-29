package com.example.nvr.imsi;

import com.example.nvr.imsi.dto.FtpRecordsResponse;
import com.example.nvr.imsi.dto.ImsiRecordView;
import com.example.nvr.persistence.EventStorageService;
import com.example.nvr.persistence.ImsiSyncConfigEntity;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Service
public class ImsiSyncService {

    private static final Logger log = LoggerFactory.getLogger(ImsiSyncService.class);
    private static final int DEFAULT_TIMEOUT_MS = 8000;

    private final ImsiSyncConfigService configService;
    private final EventStorageService eventStorageService;
    private final ReentrantLock syncLock = new ReentrantLock();

    private Instant lastAttemptAt;

    public ImsiSyncService(ImsiSyncConfigService configService,
                           EventStorageService eventStorageService) {
        this.configService = configService;
        this.eventStorageService = eventStorageService;
    }

    @PostConstruct
    public void runOnStartup() {
        try {
            syncNow(true);
        } catch (Exception ex) {
            log.warn("Initial IMSI sync failed: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void scheduledSync() {
        try {
            syncNow(false);
        } catch (Exception ex) {
            log.warn("Scheduled IMSI sync failed: {}", ex.getMessage());
        }
    }

    public FtpRecordsResponse triggerManualSync() {
        return syncNow(true);
    }

    private FtpRecordsResponse syncNow(boolean forced) {
        ImsiSyncConfigEntity config = configService.getOrCreateConfig();
        if (!isConfigReady(config)) {
            return FtpRecordsResponse.error("IMSI FTP 配置不完整，未执行同步");
        }

        Instant now = Instant.now();
        if (!forced && lastAttemptAt != null) {
            int interval = Math.max(10, Optional.ofNullable(config.getIntervalSeconds()).orElse(300));
            long secondsSince = Duration.between(lastAttemptAt, now).getSeconds();
            if (secondsSince < interval) {
                return FtpRecordsResponse.success(
                        "距离上次同步仅 " + secondsSince + " 秒，暂不重复执行。",
                        Map.of("skipped", true, "secondsSinceLast", secondsSince),
                        List.of(),
                        List.of(),
                        System.currentTimeMillis()
                );
            }
        }

        if (!syncLock.tryLock()) {
            return FtpRecordsResponse.success("已有同步任务在执行，跳过此次请求。",
                    Map.of("skipped", true, "reason", "lock"), List.of(), List.of(), System.currentTimeMillis());
        }
        try {
            lastAttemptAt = Instant.now();
            return executeFtpSync(config);
        } finally {
            syncLock.unlock();
        }
    }

    private FtpRecordsResponse executeFtpSync(ImsiSyncConfigEntity config) {
        String host = trimToNull(config.getFtpHost());
        String user = trimToNull(config.getFtpUser());
        String pass = config.getFtpPass();
        int port = config.getFtpPort() == null ? 21 : config.getFtpPort();
        int limit = config.getBatchSize() == null ? 500 : Math.max(1, Math.min(config.getBatchSize(), 20000));
        int maxFiles = config.getMaxFiles() == null ? 6 : Math.max(1, Math.min(config.getMaxFiles(), 50));
        String directory = trimToNull(config.getFtpDirectory());

        FTPClient client = new FTPClient();
        client.setConnectTimeout(DEFAULT_TIMEOUT_MS);
        client.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
        client.setDataTimeout(DEFAULT_TIMEOUT_MS);

        long start = System.currentTimeMillis();
        Map<String, Object> details = new LinkedHashMap<>();
        List<ImsiRecordView> records = new ArrayList<>();
        List<String> sourceFiles = new ArrayList<>();
        String message = "未执行同步";

        try {
            client.connect(host, port);
            details.put("connectReplyCode", client.getReplyCode());
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                message = "FTP 连接失败，回复码：" + client.getReplyCode();
                return finalizeAndRecord(config, false, message, records, sourceFiles, details, start);
            }
            if (!client.login(user, pass)) {
                details.put("loginReplyCode", client.getReplyCode());
                message = "FTP 登录失败，回复码：" + client.getReplyCode();
                return finalizeAndRecord(config, false, message, records, sourceFiles, details, start);
            }
            details.put("loginReplyCode", client.getReplyCode());
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);

            if (directory != null) {
                if (!client.changeWorkingDirectory(directory)) {
                    details.put("directory", directory);
                    message = "切换目录失败：" + directory;
                    return finalizeAndRecord(config, false, message, records, sourceFiles, details, start);
                }
            }
            String workingDirectory = client.printWorkingDirectory();
            if (workingDirectory != null) {
                details.put("directory", workingDirectory);
            }

            FTPFile[] ftpFiles = client.listFiles();
            if (ftpFiles == null || ftpFiles.length == 0) {
                message = "目录中没有可用文件";
                return finalizeAndRecord(config, true, message, records, sourceFiles, details, start);
            }

            List<FTPFile> sorted = Arrays.stream(ftpFiles)
                    .filter(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted((a, b) -> {
                        long ta = a.getTimestamp() == null ? 0 : a.getTimestamp().getTimeInMillis();
                        long tb = b.getTimestamp() == null ? 0 : b.getTimestamp().getTimeInMillis();
                        return Long.compare(tb, ta);
                    })
                    .collect(Collectors.toList());

            int processedFiles = 0;
            for (FTPFile file : sorted) {
                if (processedFiles >= maxFiles || records.size() >= limit) {
                    break;
                }
                processedFiles++;
                sourceFiles.add(file.getName());

                try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    if (!client.retrieveFile(file.getName(), buffer)) {
                        details.put("lastRetrieveReplyCode", client.getReplyCode());
                        continue;
                    }
                    String content = buffer.toString(StandardCharsets.UTF_8.name());
                    String[] lines = content.split("\\r?\\n");
                    int lineNumber = 0;
                    for (String rawLine : lines) {
                        if (records.size() >= limit) {
                            break;
                        }
                        lineNumber++;
                        String line = rawLine.trim();
                        if (line.isEmpty()) continue;
                        String[] parts = line.split("\\t", -1);
                        if (parts.length < 2) continue;

                        String deviceId = parts.length > 0 ? parts[0].trim() : "";
                        String imsi = parts.length > 1 ? parts[1].trim() : "";
                        String operator = parts.length > 3 ? parts[3].trim() : "";
                        String area = parts.length > 4 ? parts[4].trim() : "";
                        String rptDate = parts.length > 5 ? parts[5].trim() : "";
                        String rptTime = parts.length > 6 ? parts[6].trim() : "";
                        records.add(new ImsiRecordView(deviceId, imsi, operator, area, rptDate, rptTime, file.getName(), lineNumber));
                    }
                } catch (IOException ioEx) {
                    details.put("lastRetrieveError", ioEx.getClass().getSimpleName() + ": " + ioEx.getMessage());
                }
            }

            details.put("filesProcessed", processedFiles);
            details.put("recordsReturned", records.size());
            details.put("limit", limit);
            details.put("maxFiles", maxFiles);
            details.put("host", host);
            details.put("port", port);
            details.put("user", user);

            if (records.isEmpty()) {
                message = "未解析到 IMSI 数据";
                return finalizeAndRecord(config, true, message, records, sourceFiles, details, start);
            }

            List<ImsiRecordPayload> storageRecords = records.stream()
                    .map(rec -> new ImsiRecordPayload(
                            rec.getDeviceId(),
                            rec.getImsi(),
                            rec.getOperator(),
                            rec.getArea(),
                            rec.getRptDate(),
                            rec.getRptTime(),
                            rec.getSourceFile(),
                            rec.getLineNumber()))
                    .collect(Collectors.toList());

            Instant fetchedAt = Instant.now();
            long elapsed = System.currentTimeMillis() - start;
            try {
                eventStorageService.recordImsiRecords(storageRecords, fetchedAt, elapsed, host, port, workingDirectory, "成功获取 IMSI 数据");
            } catch (Exception storageEx) {
                log.warn("Failed to record IMSI records", storageEx);
            }
            message = "成功获取 IMSI 数据";
            return finalizeAndRecord(config, true, message, records, sourceFiles, details, start);
        } catch (IOException ioe) {
            message = ioe.getClass().getSimpleName() + ": " + ioe.getMessage();
            return finalizeAndRecord(config, false, message, records, sourceFiles, details, start);
        } catch (Exception ex) {
            message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            return finalizeAndRecord(config, false, message, records, sourceFiles, details, start);
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

    private FtpRecordsResponse finalizeAndRecord(ImsiSyncConfigEntity config,
                                                 boolean ok,
                                                 String message,
                                                 List<ImsiRecordView> records,
                                                 List<String> sourceFiles,
                                                 Map<String, Object> details,
                                                 long start) {
        long elapsed = Math.max(0, System.currentTimeMillis() - start);
        configService.recordSyncResult(ok, message, records.size(), elapsed, Instant.now());
        if (ok) {
            return FtpRecordsResponse.success(message, details, records, sourceFiles, start);
        }
        return FtpRecordsResponse.error(message, details, records, sourceFiles, start);
    }

    private boolean isConfigReady(ImsiSyncConfigEntity config) {
        return config != null
                && trimToNull(config.getFtpHost()) != null
                && trimToNull(config.getFtpUser()) != null
                && config.getFtpPass() != null && !config.getFtpPass().isEmpty();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
