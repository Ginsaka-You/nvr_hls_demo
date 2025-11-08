package com.example.nvr.tcb.service;

import com.example.nvr.config.TcbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class WeixinTcbClient {

    private static final Logger log = LoggerFactory.getLogger(WeixinTcbClient.class);
    private static final Set<String> UPLOAD_RESERVED_KEYS = Set.of("errcode", "errmsg", "url", "file_id");

    private final RestTemplate restTemplate;
    private final WeixinAccessTokenClient tokenClient;
    private final TcbProperties tcbProperties;

    public WeixinTcbClient(RestTemplate restTemplate,
                           WeixinAccessTokenClient tokenClient,
                           TcbProperties tcbProperties) {
        this.restTemplate = restTemplate;
        this.tokenClient = tokenClient;
        this.tcbProperties = tcbProperties;
    }

    public String uploadSnapshot(byte[] jpegBytes) {
        String accessToken = tokenClient.getAccessToken();
        String cloudPath = String.format("alerts/%d-%s.jpg", Instant.now().toEpochMilli(), UUID.randomUUID());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("env", tcbProperties.getEnvId());
        requestBody.put("path", cloudPath);

        Map<?, ?> uploadMeta = restTemplate.postForObject(
                "https://api.weixin.qq.com/tcb/uploadfile?access_token={token}",
                requestBody,
                Map.class,
                accessToken
        );
        validateUploadMeta(uploadMeta);
        String uploadUrl = Objects.toString(uploadMeta.get("url"));
        String fileId = Objects.toString(uploadMeta.get("file_id"));

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        uploadMeta.forEach((key, value) -> {
            if (!UPLOAD_RESERVED_KEYS.contains(key) && value != null) {
                form.add(key.toString(), value.toString());
            }
        });
        form.add("file", new ByteArrayResource(jpegBytes) {
            @Override
            public String getFilename() {
                return "snapshot.jpg";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            restTemplate.postForEntity(uploadUrl, new HttpEntity<>(form, headers), String.class);
        } catch (RestClientException ex) {
            log.error("COS upload failed", ex);
            throw new IllegalStateException("upload snapshot failed", ex);
        }
        return fileId;
    }

    public String invokeFunction(String functionName, String requestData) {
        String accessToken = tokenClient.getAccessToken();
        Map<String, Object> body = new HashMap<>();
        body.put("request_data", requestData);
        return restTemplate.postForObject(
                "https://api.weixin.qq.com/tcb/invokecloudfunction?access_token={token}&env={env}&name={name}",
                body,
                String.class,
                accessToken,
                tcbProperties.getEnvId(),
                functionName
        );
    }

    public String encodeBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void validateUploadMeta(Map<?, ?> uploadMeta) {
        if (uploadMeta == null) {
            throw new IllegalStateException("upload metadata missing");
        }
        Number errcode = uploadMeta.containsKey("errcode")
                ? (Number) uploadMeta.get("errcode")
                : Integer.valueOf(0);
        if (errcode.intValue() != 0) {
            throw new IllegalStateException("uploadfile error: " + uploadMeta);
        }
        if (!uploadMeta.containsKey("url") || !uploadMeta.containsKey("file_id")) {
            throw new IllegalStateException("uploadfile missing url/file_id: " + uploadMeta);
        }
    }
}
