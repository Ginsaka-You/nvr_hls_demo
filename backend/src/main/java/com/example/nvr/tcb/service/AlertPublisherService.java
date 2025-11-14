package com.example.nvr.tcb.service;

import com.example.nvr.config.TcbProperties;
import com.example.nvr.config.TcbSecurityProperties;
import com.example.nvr.tcb.model.AlertEvent;
import com.example.nvr.tcb.support.HmacSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Map;

@Service
public class AlertPublisherService {

    private static final Logger log = LoggerFactory.getLogger(AlertPublisherService.class);

    private final WeixinTcbClient weixinTcbClient;
    private final TcbProperties tcbProperties;
    private final TcbSecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    public AlertPublisherService(WeixinTcbClient weixinTcbClient,
                                 TcbProperties tcbProperties,
                                 TcbSecurityProperties securityProperties,
                                 ObjectMapper objectMapper) {
        this.weixinTcbClient = weixinTcbClient;
        this.tcbProperties = tcbProperties;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
    }

    public String publish(AlertEvent event, byte[] snapshotJpeg) {
        ObjectNode alertNode = objectMapper.valueToTree(event);
        String imageBase64 = null;
        if (snapshotJpeg != null && snapshotJpeg.length > 0) {
            imageBase64 = Base64.getEncoder().encodeToString(snapshotJpeg);
            log.info("Encoded snapshot payload ({} bytes) for alert {}", snapshotJpeg.length, event.getTitle());
        }
        alertNode.putNull("snapshotFileId");
        if (imageBase64 != null) {
            alertNode.put("imageBase64", imageBase64);
        } else {
            alertNode.putNull("imageBase64");
        }

        String alertJson = alertNode.toString();
        String sig = HmacSupport.sign(securityProperties.getHmacSecret(), alertJson);
        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.set("alert", alertNode);
        requestNode.put("sig", sig);

        String response = weixinTcbClient.invokeFunction(tcbProperties.getIngestFunction(), requestNode.toString());
        return extractDocumentId(response);
    }

    private String extractDocumentId(String responseBody) {
        if (responseBody == null) {
            throw new IllegalStateException("invoke function returned empty body");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int errcode = root.path("errcode").asInt(-1);
            if (errcode != 0) {
                throw new IllegalStateException("invokecloudfunction failed: " + responseBody);
            }
            String result = root.path("result").asText();
            if (result == null || result.isBlank()) {
                return null;
            }
            JsonNode resultNode = objectMapper.readTree(result);
            return resultNode.path("id").asText(null);
        } catch (Exception ex) {
            log.warn("Failed to parse invoke response {}", responseBody, ex);
            throw new IllegalStateException("parse invoke response failed", ex);
        }
    }
}
