package com.example.nvr.wechat;

import com.example.nvr.config.WxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class WechatAuthService {

    private static final Logger log = LoggerFactory.getLogger(WechatAuthService.class);

    private final RestTemplate restTemplate;
    private final WxProperties wxProperties;

    public WechatAuthService(RestTemplate restTemplate, WxProperties wxProperties) {
        this.restTemplate = restTemplate;
        this.wxProperties = wxProperties;
    }

    public String exchangeCodeForOpenId(String code) {
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("code must not be blank");
        }
        String url = String.format(
                "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                wxProperties.getAppId(),
                wxProperties.getAppSecret(),
                code
        );
        try {
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            if (resp == null) {
                throw new IllegalStateException("empty response from wechat");
            }
            Object errCode = resp.get("errcode");
            if (errCode instanceof Number && ((Number) errCode).intValue() != 0) {
                throw new IllegalStateException("wechat jscode2session error: " + errCode + " - " + resp.get("errmsg"));
            }
            String openId = (String) resp.get("openid");
            if (!StringUtils.hasText(openId)) {
                throw new IllegalStateException("missing openid in response");
            }
            return openId;
        } catch (RestClientException ex) {
            log.warn("Failed to exchange wx code: {}", ex.getMessage());
            throw new IllegalStateException("wechat auth failed");
        }
    }
}

