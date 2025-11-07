package com.example.nvr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "wx")
public class WxProperties {

    @NotBlank
    private String appId;

    @NotBlank
    private String appSecret;

    @NotBlank
    private String templateId;

    private String alertPage = "pages/alerts/index";

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getAlertPage() {
        return alertPage;
    }

    public void setAlertPage(String alertPage) {
        this.alertPage = alertPage;
    }
}

