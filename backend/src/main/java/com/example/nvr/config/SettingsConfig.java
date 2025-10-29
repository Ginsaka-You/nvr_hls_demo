package com.example.nvr.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SettingsConfig {

    private String nvrHost;
    private String nvrUser;
    private String nvrPass;
    private String nvrScheme;
    private Integer nvrHttpPort;
    private Integer portCount;
    private Boolean detectMain;
    private Boolean detectSub;
    private String streamMode;
    private String hlsOrigin;
    private String webrtcServer;
    private String webrtcOptions;
    private String webrtcPreferCodec;
    private String channelOverrides;

    private String audioPass;
    private Integer audioId;
    private Integer audioHttpPort;

    private String radarHost;
    private Integer radarCtrlPort;
    private Integer radarDataPort;
    private Boolean radarUseTcp;

    private String imsiFtpHost;
    private Integer imsiFtpPort;
    private String imsiFtpUser;
    private String imsiFtpPass;
    private Integer imsiSyncInterval;
    private Integer imsiSyncBatchSize;
    private Integer imsiSyncMaxFiles;
    private String imsiFilenameTemplate;
    private String imsiLineTemplate;
    private String imsiDeviceFilter;

    private String dbType;
    private String dbHost;
    private Integer dbPort;
    private String dbName;
    private String dbUser;
    private String dbPass;

    public static SettingsConfig defaultConfig() {
        SettingsConfig config = new SettingsConfig();
        config.nvrHost = "192.168.50.76";
        config.nvrUser = "admin";
        config.nvrPass = "00000000a";
        config.nvrScheme = "http";
        config.nvrHttpPort = null;
        config.portCount = 8;
        config.detectMain = Boolean.FALSE;
        config.detectSub = Boolean.TRUE;
        config.streamMode = "webrtc";
        config.hlsOrigin = "";
        config.webrtcServer = "http://127.0.0.1:8800";
        config.webrtcOptions = "transportmode=unicast&profile=Profile_1&forceh264=1&videoCodecType=H264&rtptransport=tcp&timeout=60";
        config.webrtcPreferCodec = "video/H264";
        config.channelOverrides = "";

        config.audioPass = "YouloveWill";
        config.audioId = 12;
        config.audioHttpPort = 65007;

        config.radarHost = "192.168.2.40";
        config.radarCtrlPort = 20000;
        config.radarDataPort = 20001;
        config.radarUseTcp = Boolean.FALSE;

        config.imsiFtpHost = "47.98.168.56";
        config.imsiFtpPort = 4721;
        config.imsiFtpUser = "ftpuser";
        config.imsiFtpPass = "ftpPass@47";
        config.imsiSyncInterval = 60;
        config.imsiSyncBatchSize = 500;
        config.imsiSyncMaxFiles = 6;
        config.imsiFilenameTemplate = "CTC_{deviceId}_{dateyymmdd}_{timestamp}.txt";
        config.imsiLineTemplate = "{deviceId}\t{imsi}\t000000000000000\t{operator:1,2,3,4}\t{area}\t{rptTimeyymmdd}\t{rptTimehhmmss}\t";
        config.imsiDeviceFilter = "njtest001";

        config.dbType = "postgres";
        config.dbHost = "127.0.0.1";
        config.dbPort = 5432;
        config.dbName = "nvr_demo";
        config.dbUser = "nvr_app";
        config.dbPass = "nvrdemo";
        return config;
    }

    public SettingsConfig fillDefaults() {
        SettingsConfig defaults = defaultConfig();
        if (nvrHost == null) nvrHost = defaults.nvrHost;
        if (nvrUser == null) nvrUser = defaults.nvrUser;
        if (nvrPass == null) nvrPass = defaults.nvrPass;
        if (nvrScheme == null) nvrScheme = defaults.nvrScheme;
        if (nvrHttpPort == null) nvrHttpPort = defaults.nvrHttpPort;
        if (portCount == null) portCount = defaults.portCount;
        if (detectMain == null) detectMain = defaults.detectMain;
        if (detectSub == null) detectSub = defaults.detectSub;
        if (streamMode == null) streamMode = defaults.streamMode;
        if (hlsOrigin == null) hlsOrigin = defaults.hlsOrigin;
        if (webrtcServer == null) webrtcServer = defaults.webrtcServer;
        if (webrtcOptions == null) webrtcOptions = defaults.webrtcOptions;
        if (webrtcPreferCodec == null) webrtcPreferCodec = defaults.webrtcPreferCodec;
        if (channelOverrides == null) channelOverrides = defaults.channelOverrides;

        if (audioPass == null) audioPass = defaults.audioPass;
        if (audioId == null) audioId = defaults.audioId;
        if (audioHttpPort == null) audioHttpPort = defaults.audioHttpPort;

        if (radarHost == null) radarHost = defaults.radarHost;
        if (radarCtrlPort == null) radarCtrlPort = defaults.radarCtrlPort;
        if (radarDataPort == null) radarDataPort = defaults.radarDataPort;
        if (radarUseTcp == null) radarUseTcp = defaults.radarUseTcp;

        if (imsiFtpHost == null) imsiFtpHost = defaults.imsiFtpHost;
        if (imsiFtpPort == null) imsiFtpPort = defaults.imsiFtpPort;
        if (imsiFtpUser == null) imsiFtpUser = defaults.imsiFtpUser;
        if (imsiFtpPass == null) imsiFtpPass = defaults.imsiFtpPass;
        if (imsiSyncInterval == null) imsiSyncInterval = defaults.imsiSyncInterval;
        if (imsiSyncBatchSize == null) imsiSyncBatchSize = defaults.imsiSyncBatchSize;
        if (imsiSyncMaxFiles == null) imsiSyncMaxFiles = defaults.imsiSyncMaxFiles;
        if (imsiFilenameTemplate == null) imsiFilenameTemplate = defaults.imsiFilenameTemplate;
        if (imsiLineTemplate == null) imsiLineTemplate = defaults.imsiLineTemplate;
        if (imsiDeviceFilter == null) imsiDeviceFilter = defaults.imsiDeviceFilter;

        if (dbType == null) dbType = defaults.dbType;
        if (dbHost == null) dbHost = defaults.dbHost;
        if (dbPort == null) dbPort = defaults.dbPort;
        if (dbName == null) dbName = defaults.dbName;
        if (dbUser == null) dbUser = defaults.dbUser;
        if (dbPass == null) dbPass = defaults.dbPass;
        return this;
    }

    // Getters and setters
    public String getNvrHost() {
        return nvrHost;
    }

    public void setNvrHost(String nvrHost) {
        this.nvrHost = nvrHost;
    }

    public String getNvrUser() {
        return nvrUser;
    }

    public void setNvrUser(String nvrUser) {
        this.nvrUser = nvrUser;
    }

    public String getNvrPass() {
        return nvrPass;
    }

    public void setNvrPass(String nvrPass) {
        this.nvrPass = nvrPass;
    }

    public String getNvrScheme() {
        return nvrScheme;
    }

    public void setNvrScheme(String nvrScheme) {
        this.nvrScheme = nvrScheme;
    }

    public Integer getNvrHttpPort() {
        return nvrHttpPort;
    }

    public void setNvrHttpPort(Integer nvrHttpPort) {
        this.nvrHttpPort = nvrHttpPort;
    }

    public Integer getPortCount() {
        return portCount;
    }

    public void setPortCount(Integer portCount) {
        this.portCount = portCount;
    }

    public Boolean getDetectMain() {
        return detectMain;
    }

    public void setDetectMain(Boolean detectMain) {
        this.detectMain = detectMain;
    }

    public Boolean getDetectSub() {
        return detectSub;
    }

    public void setDetectSub(Boolean detectSub) {
        this.detectSub = detectSub;
    }

    public String getStreamMode() {
        return streamMode;
    }

    public void setStreamMode(String streamMode) {
        this.streamMode = streamMode;
    }

    public String getHlsOrigin() {
        return hlsOrigin;
    }

    public void setHlsOrigin(String hlsOrigin) {
        this.hlsOrigin = hlsOrigin;
    }

    public String getWebrtcServer() {
        return webrtcServer;
    }

    public void setWebrtcServer(String webrtcServer) {
        this.webrtcServer = webrtcServer;
    }

    public String getWebrtcOptions() {
        return webrtcOptions;
    }

    public void setWebrtcOptions(String webrtcOptions) {
        this.webrtcOptions = webrtcOptions;
    }

    public String getWebrtcPreferCodec() {
        return webrtcPreferCodec;
    }

    public void setWebrtcPreferCodec(String webrtcPreferCodec) {
        this.webrtcPreferCodec = webrtcPreferCodec;
    }

    public String getChannelOverrides() {
        return channelOverrides;
    }

    public void setChannelOverrides(String channelOverrides) {
        this.channelOverrides = channelOverrides;
    }

    public String getAudioPass() {
        return audioPass;
    }

    public void setAudioPass(String audioPass) {
        this.audioPass = audioPass;
    }

    public Integer getAudioId() {
        return audioId;
    }

    public void setAudioId(Integer audioId) {
        this.audioId = audioId;
    }

    public Integer getAudioHttpPort() {
        return audioHttpPort;
    }

    public void setAudioHttpPort(Integer audioHttpPort) {
        this.audioHttpPort = audioHttpPort;
    }

    public String getRadarHost() {
        return radarHost;
    }

    public void setRadarHost(String radarHost) {
        this.radarHost = radarHost;
    }

    public Integer getRadarCtrlPort() {
        return radarCtrlPort;
    }

    public void setRadarCtrlPort(Integer radarCtrlPort) {
        this.radarCtrlPort = radarCtrlPort;
    }

    public Integer getRadarDataPort() {
        return radarDataPort;
    }

    public void setRadarDataPort(Integer radarDataPort) {
        this.radarDataPort = radarDataPort;
    }

    public Boolean getRadarUseTcp() {
        return radarUseTcp;
    }

    public void setRadarUseTcp(Boolean radarUseTcp) {
        this.radarUseTcp = radarUseTcp;
    }

    public String getImsiFtpHost() {
        return imsiFtpHost;
    }

    public void setImsiFtpHost(String imsiFtpHost) {
        this.imsiFtpHost = imsiFtpHost;
    }

    public Integer getImsiFtpPort() {
        return imsiFtpPort;
    }

    public void setImsiFtpPort(Integer imsiFtpPort) {
        this.imsiFtpPort = imsiFtpPort;
    }

    public String getImsiFtpUser() {
        return imsiFtpUser;
    }

    public void setImsiFtpUser(String imsiFtpUser) {
        this.imsiFtpUser = imsiFtpUser;
    }

    public String getImsiFtpPass() {
        return imsiFtpPass;
    }

    public void setImsiFtpPass(String imsiFtpPass) {
        this.imsiFtpPass = imsiFtpPass;
    }

    public Integer getImsiSyncInterval() {
        return imsiSyncInterval;
    }

    public void setImsiSyncInterval(Integer imsiSyncInterval) {
        this.imsiSyncInterval = imsiSyncInterval;
    }

    public Integer getImsiSyncBatchSize() {
        return imsiSyncBatchSize;
    }

    public void setImsiSyncBatchSize(Integer imsiSyncBatchSize) {
        this.imsiSyncBatchSize = imsiSyncBatchSize;
    }

    public Integer getImsiSyncMaxFiles() {
        return imsiSyncMaxFiles;
    }

    public void setImsiSyncMaxFiles(Integer imsiSyncMaxFiles) {
        this.imsiSyncMaxFiles = imsiSyncMaxFiles;
    }

    public String getImsiFilenameTemplate() {
        return imsiFilenameTemplate;
    }

    public void setImsiFilenameTemplate(String imsiFilenameTemplate) {
        this.imsiFilenameTemplate = imsiFilenameTemplate;
    }

    public String getImsiLineTemplate() {
        return imsiLineTemplate;
    }

    public void setImsiLineTemplate(String imsiLineTemplate) {
        this.imsiLineTemplate = imsiLineTemplate;
    }

    public String getImsiDeviceFilter() {
        return imsiDeviceFilter;
    }

    public void setImsiDeviceFilter(String imsiDeviceFilter) {
        this.imsiDeviceFilter = imsiDeviceFilter;
    }

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public String getDbHost() {
        return dbHost;
    }

    public void setDbHost(String dbHost) {
        this.dbHost = dbHost;
    }

    public Integer getDbPort() {
        return dbPort;
    }

    public void setDbPort(Integer dbPort) {
        this.dbPort = dbPort;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getDbUser() {
        return dbUser;
    }

    public void setDbUser(String dbUser) {
        this.dbUser = dbUser;
    }

    public String getDbPass() {
        return dbPass;
    }

    public void setDbPass(String dbPass) {
        this.dbPass = dbPass;
    }
}
