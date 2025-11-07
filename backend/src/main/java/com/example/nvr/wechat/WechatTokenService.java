package com.example.nvr.wechat;

import com.example.nvr.config.WxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class WechatTokenService {

    private static final Logger log = LoggerFactory.getLogger(WechatTokenService.class);

    private final RestTemplate restTemplate;
    private final WxProperties wxProperties;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String accessToken;
    private volatile long expiresAtEpochSeconds = 0;

    public WechatTokenService(RestTemplate restTemplate, WxProperties wxProperties) {
        this.restTemplate = restTemplate;
        this.wxProperties = wxProperties;
    }

    public String getToken() {
        long now = Instant.now().getEpochSecond();
        if (StringUtils.hasText(accessToken) && now < expiresAtEpochSeconds - 120) {
            return accessToken;
        }
        lock.lock();
        try {
            if (StringUtils.hasText(accessToken) && Instant.now().getEpochSecond() < expiresAtEpochSeconds - 120) {
                return accessToken;
            }
            refreshToken();
            return accessToken;
        } finally {
            lock.unlock();
        }
    }

    public void forceRefresh() {
        lock.lock();
        try {
            refreshToken();
        } finally {
            lock.unlock();
        }
    }

    private void refreshToken() {
        String url = String.format(
                "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s",
                wxProperties.getAppId(),
                wxProperties.getAppSecret()
        );
        try {
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            if (resp == null) {
                throw new IllegalStateException("empty response from wechat token api");
            }
            Object errCode = resp.get("errcode");
            if (errCode instanceof Number && ((Number) errCode).intValue() != 0) {
                throw new IllegalStateException("wechat token error: " + errCode + " - " + resp.get("errmsg"));
            }
            String token = (String) resp.get("access_token");
            Number expiresIn = (Number) resp.getOrDefault("expires_in", 7200);
            if (!StringUtils.hasText(token)) {
                throw new IllegalStateException("missing access_token");
            }
            this.accessToken = token;
            this.expiresAtEpochSeconds = Instant.now().getEpochSecond() + expiresIn.longValue();
        } catch (RestClientException ex) {
            log.warn("Failed to fetch wechat token: {}", ex.getMessage());
            throw new IllegalStateException("fetch wechat token failed");
        }
    }
}

