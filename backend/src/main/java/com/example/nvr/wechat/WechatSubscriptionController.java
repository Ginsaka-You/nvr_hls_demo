package com.example.nvr.wechat;

import com.example.nvr.config.WxProperties;
import com.example.nvr.wechat.dto.SubscriptionReportRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/wechat/subscription")
public class WechatSubscriptionController {

    private final WechatAuthService authService;
    private final WechatSubscriptionService subscriptionService;
    private final WxProperties wxProperties;

    public WechatSubscriptionController(WechatAuthService authService,
                                        WechatSubscriptionService subscriptionService,
                                        WxProperties wxProperties) {
        this.authService = authService;
        this.subscriptionService = subscriptionService;
        this.wxProperties = wxProperties;
    }

    @PostMapping("/report")
    public ResponseEntity<Map<String, Object>> reportAuthorization(@RequestBody SubscriptionReportRequest request) {
        if (request == null || !StringUtils.hasText(request.getCode())) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "缺少登录 code"));
        }
        String openId = authService.exchangeCodeForOpenId(request.getCode());
        Map<String, String> results = request.getResults();
        int accepted = 0;
        if (!CollectionUtils.isEmpty(results)) {
            for (Map.Entry<String, String> entry : results.entrySet()) {
                String templateId = entry.getKey();
                String status = entry.getValue();
                if ("accept".equalsIgnoreCase(status)) {
                    subscriptionService.recordAuthorization(openId, templateId, 1, request.getScene());
                    accepted++;
                }
            }
        } else {
            // default to configured template if request payload omitted results but call succeeded
            subscriptionService.recordAuthorization(openId, wxProperties.getTemplateId(), 1, request.getScene());
            accepted = 1;
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("accepted", accepted);
        resp.put("openId", openId);
        return ResponseEntity.ok(resp);
    }
}

