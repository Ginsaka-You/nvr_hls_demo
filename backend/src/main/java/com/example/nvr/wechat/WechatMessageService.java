package com.example.nvr.wechat;

import com.example.nvr.config.WxProperties;
import com.example.nvr.persistence.AlertEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class WechatMessageService {

    private static final Logger log = LoggerFactory.getLogger(WechatMessageService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final RestTemplate restTemplate;
    private final WechatTokenService tokenService;
    private final WxProperties wxProperties;

    public WechatMessageService(RestTemplate restTemplate,
                                WechatTokenService tokenService,
                                WxProperties wxProperties) {
        this.restTemplate = restTemplate;
        this.tokenService = tokenService;
        this.wxProperties = wxProperties;
    }

    public SendResult sendAlert(AlertEventEntity alert, String openId) {
        Map<String, Object> data = buildAlertPayload(alert);
        return sendTemplate(openId, data);
    }

    private SendResult sendTemplate(String openId, Map<String, Object> data) {
        if (!StringUtils.hasText(openId)) {
            return SendResult.failed("INVALID_OPENID", "openId missing");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("touser", openId);
        payload.put("template_id", wxProperties.getTemplateId());
        payload.put("data", data);
        if (StringUtils.hasText(wxProperties.getAlertPage())) {
            payload.put("page", wxProperties.getAlertPage());
        }
        return postToWechat(payload, true);
    }

    private SendResult postToWechat(Map<String, Object> payload, boolean retryOnTokenExpire) {
        String token = tokenService.getToken();
        String url = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=" + token;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> resp = restTemplate.postForObject(url, new HttpEntity<>(payload, headers), Map.class);
            if (resp == null) {
                return SendResult.failed("EMPTY_RESPONSE", "wechat api returned empty body");
            }
            int errCode = ((Number) resp.getOrDefault("errcode", 0)).intValue();
            if (errCode == 0) {
                return SendResult.success();
            }
            // Token expired -> refresh once
            if (retryOnTokenExpire && (errCode == 40001 || errCode == 42001)) {
                tokenService.forceRefresh();
                return postToWechat(payload, false);
            }
            return SendResult.failed(String.valueOf(errCode), (String) resp.get("errmsg"));
        } catch (RestClientException ex) {
            log.warn("Failed to call wechat subscribe API: {}", ex.getMessage());
            return SendResult.failed("HTTP_ERROR", ex.getMessage());
        }
    }

    private Map<String, Object> buildAlertPayload(AlertEventEntity alert) {
        Map<String, Object> data = new HashMap<>();
        data.put("thing1", Map.of("value", safeText(alert.getEventType(), "入侵告警")));
        data.put("time2", Map.of("value", safeText(alert.getEventTime(), TIME_FORMATTER.format(Instant.now()))));
        data.put("thing3", Map.of("value", safeText(alert.getCamChannel(), "摄像头")));
        data.put("phrase4", Map.of("value", safeText(alert.getStatus(), "未处理")));
        return data;
    }

    private String safeText(String value, String fallback) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        return fallback;
    }

    public static class SendResult {
        private final boolean success;
        private final String errorCode;
        private final String errorMessage;

        private SendResult(boolean success, String errorCode, String errorMessage) {
            this.success = success;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static SendResult success() {
            return new SendResult(true, null, null);
        }

        public static SendResult failed(String code, String message) {
            return new SendResult(false, code, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}

