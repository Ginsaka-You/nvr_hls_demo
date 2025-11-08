package com.example.nvr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "tcb")
public class TcbProperties {

    @NotBlank
    private String envId;

    @NotBlank
    private String ingestFunction;

    @Min(1)
    private int snapshotUploadThresholdKB = 256;

    public String getEnvId() {
        return envId;
    }

    public void setEnvId(String envId) {
        this.envId = envId;
    }

    public String getIngestFunction() {
        return ingestFunction;
    }

    public void setIngestFunction(String ingestFunction) {
        this.ingestFunction = ingestFunction;
    }

    public int getSnapshotUploadThresholdKB() {
        return snapshotUploadThresholdKB;
    }

    public void setSnapshotUploadThresholdKB(int snapshotUploadThresholdKB) {
        this.snapshotUploadThresholdKB = snapshotUploadThresholdKB;
    }
}

