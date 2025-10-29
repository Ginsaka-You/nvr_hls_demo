package com.example.nvr.imsi;

public class ImsiRecordPayload {
    private final String deviceId;
    private final String imsi;
    private final String operator;
    private final String area;
    private final String rptDate;
    private final String rptTime;
    private final String sourceFile;
    private final Integer lineNumber;

    public ImsiRecordPayload(String deviceId,
                             String imsi,
                             String operator,
                             String area,
                             String rptDate,
                             String rptTime,
                             String sourceFile,
                             Integer lineNumber) {
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

    public Integer getLineNumber() {
        return lineNumber;
    }
}
