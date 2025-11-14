package com.example.nvr.tcb.service;

import com.example.nvr.config.TcbProperties;
import com.example.nvr.config.TcbSecurityProperties;
import com.example.nvr.tcb.model.AlertEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertPublisherServiceTest {

    @Mock
    private WeixinTcbClient tcbClient;

    private TcbProperties tcbProperties;
    private TcbSecurityProperties securityProperties;

    @InjectMocks
    private AlertPublisherService service;

    @BeforeEach
    void setup() {
        tcbProperties = new TcbProperties();
        tcbProperties.setEnvId("env");
        tcbProperties.setIngestFunction("ingestAlarm");

        securityProperties = new TcbSecurityProperties();
        securityProperties.setHmacSecret("secret");

        service = new AlertPublisherService(tcbClient, tcbProperties, securityProperties, new ObjectMapper());
    }

    @Test
    void encodesSnapshotAsBase64Payload() throws Exception {
        when(tcbClient.invokeFunction(eq("ingestAlarm"), any())).thenReturn("{\"errcode\":0,\"result\":\"{\\\"id\\\":\\\"doc1\\\"}\"}");
        AlertEvent event = buildEvent();
        byte[] jpeg = "tiny".getBytes();

        String id = service.publish(event, jpeg);

        assertEquals("doc1", id);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(tcbClient).invokeFunction(eq("ingestAlarm"), captor.capture());
        JsonNode payload = new ObjectMapper().readTree(captor.getValue());
        String imageBase64 = payload.path("alert").path("imageBase64").asText("");
        assertTrue(imageBase64.length() > 0);
    }

    private AlertEvent buildEvent() {
        AlertEvent event = new AlertEvent();
        event.setUserId("u1");
        event.setTitle("入侵");
        event.setSeverity("HIGH");
        event.setLocation("A区");
        event.setDevice("Cam-01");
        event.setCamera("Cam");
        event.setOccurAt("2025-11-08T14:30:12+08:00");
        return event;
    }
}
