package com.example.nvr.tcb.service;

import com.example.nvr.config.WeixinOpenApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeixinAccessTokenClientTest {

    @Test
    void cachesTokenUntilExpired() {
        RestTemplate template = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).ignoreExpectOrder(true).build();

        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=appid&secret=secret";
        server.expect(ExpectedCount.once(), requestTo(url))
                .andRespond(withSuccess("{\"access_token\":\"token-1\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));

        WeixinOpenApiProperties props = new WeixinOpenApiProperties();
        props.setAppid("appid");
        props.setSecret("secret");

        WeixinAccessTokenClient client = new WeixinAccessTokenClient(template, props);
        assertEquals("token-1", client.getAccessToken());
        assertEquals("token-1", client.getAccessToken());
        server.verify();
    }

    @Test
    void refreshesWhenExpired() {
        RestTemplate template = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).ignoreExpectOrder(true).build();

        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=appid&secret=secret";
        server.expect(requestTo(url))
                .andRespond(withSuccess("{\"access_token\":\"token-1\",\"expires_in\":1}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess("{\"access_token\":\"token-2\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));

        WeixinOpenApiProperties props = new WeixinOpenApiProperties();
        props.setAppid("appid");
        props.setSecret("secret");

        WeixinAccessTokenClient client = new WeixinAccessTokenClient(template, props);
        assertEquals("token-1", client.getAccessToken());
        assertEquals("token-2", client.getAccessToken());
        server.verify();
    }
}
