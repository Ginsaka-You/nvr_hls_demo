package com.example.nvr.imsi.dto;

public class ImsiRecordView {
    private final String deviceId;
    private final String imsi;
    private final String operator;
    private final String area;
    private final String rptDate;
    private final String rptTime;
    private final String sourceFile;
    private final int lineNumber;

    public ImsiRecordView(String deviceId, String imsi, String operator, String area,
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
