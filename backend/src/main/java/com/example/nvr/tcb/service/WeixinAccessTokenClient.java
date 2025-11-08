package com.example.nvr.tcb.service;

import com.example.nvr.config.WeixinOpenApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

@Component
public class WeixinAccessTokenClient {

    private static final Logger log = LoggerFactory.getLogger(WeixinAccessTokenClient.class);

    private final RestTemplate restTemplate;
    private final WeixinOpenApiProperties properties;

    private volatile String accessToken;
    private volatile long expireAtEpochSecond = 0;

    public WeixinAccessTokenClient(RestTemplate restTemplate, WeixinOpenApiProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public synchronized String getAccessToken() {
        long now = Instant.now().getEpochSecond();
        if (accessToken != null && now < expireAtEpochSecond) {
            return accessToken;
        }
        fetchAndCacheToken();
        return accessToken;
    }

    private void fetchAndCacheToken() {
        RestClientException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Map<?, ?> resp = restTemplate.getForObject(
                        "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}",
                        Map.class,
                        properties.getAppid(),
                        properties.getSecret()
                );
                if (resp == null || !resp.containsKey("access_token")) {
                    throw new IllegalStateException("access_token missing: " + resp);
                }
                String token = (String) resp.get("access_token");
                Number expiresIn = resp.containsKey("expires_in")
                        ? (Number) resp.get("expires_in")
                        : Integer.valueOf(7200);
                accessToken = token;
                expireAtEpochSecond = Instant.now().getEpochSecond() + Math.max(0, expiresIn.longValue() - 200);
                return;
            } catch (RestClientException ex) {
                lastError = ex;
                log.warn("Failed to fetch access_token attempt={}", attempt + 1, ex);
            }
        }
        throw new IllegalStateException("Failed to fetch access_token", lastError);
    }
}
