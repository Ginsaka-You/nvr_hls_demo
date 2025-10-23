package com.example.nvr.persistence;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "imsi_records")
public class ImsiRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String deviceId;

    @Column(length = 32)
    private String imsi;

    @Column(length = 16)
    private String operatorCode;

    @Column(length = 128)
    private String area;

    @Column(length = 16)
    private String rptDate;

    @Column(length = 16)
    private String rptTime;

    @Column(length = 255)
    private String sourceFile;

    private Integer lineNumber;

    @Column(length = 128)
    private String host;

    private Integer port;

    @Column(length = 255)
    private String directory;

    @Column(length = 255)
    private String message;

    private Long elapsedMs;

    private Instant fetchedAt;

    public ImsiRecordEntity() {
    }

    public ImsiRecordEntity(String deviceId,
                            String imsi,
                            String operatorCode,
                            String area,
                            String rptDate,
                            String rptTime,
                            String sourceFile,
                            Integer lineNumber,
                            String host,
                            Integer port,
                            String directory,
                            String message,
                            Long elapsedMs,
                            Instant fetchedAt) {
        this.deviceId = deviceId;
        this.imsi = imsi;
        this.operatorCode = operatorCode;
        this.area = area;
        this.rptDate = rptDate;
        this.rptTime = rptTime;
        this.sourceFile = sourceFile;
        this.lineNumber = lineNumber;
        this.host = host;
        this.port = port;
        this.directory = directory;
        this.message = message;
        this.elapsedMs = elapsedMs;
        this.fetchedAt = fetchedAt;
    }

    public Long getId() {
        return id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getImsi() {
        return imsi;
    }

    public String getOperatorCode() {
        return operatorCode;
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

    public Integer getLineNumber() {
        return lineNumber;
    }

    public String getHost() {
        return host;
    }

    public Integer getPort() {
        return port;
    }

    public String getDirectory() {
        return directory;
    }

    public String getMessage() {
        return message;
    }

    public Long getElapsedMs() {
        return elapsedMs;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}

