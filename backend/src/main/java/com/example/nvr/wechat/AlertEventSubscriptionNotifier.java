package com.example.nvr.wechat;

import com.example.nvr.config.WxProperties;
import com.example.nvr.events.AlertEventSavedEvent;
import com.example.nvr.persistence.WechatSubscriberEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AlertEventSubscriptionNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertEventSubscriptionNotifier.class);

    private final WxProperties wxProperties;
    private final WechatSubscriptionService subscriptionService;
    private final WechatMessageService messageService;

    public AlertEventSubscriptionNotifier(WxProperties wxProperties,
                                          WechatSubscriptionService subscriptionService,
                                          WechatMessageService messageService) {
        this.wxProperties = wxProperties;
        this.subscriptionService = subscriptionService;
        this.messageService = messageService;
    }

    @EventListener
    public void handleAlert(AlertEventSavedEvent event) {
        if (event == null || event.getEntity() == null) {
            return;
        }
        List<WechatSubscriberEntity> subscribers = subscriptionService.findEligible(wxProperties.getTemplateId());
        if (subscribers.isEmpty()) {
            return;
        }
        subscribers.forEach(subscriber -> {
            WechatMessageService.SendResult result = messageService.sendAlert(event.getEntity(), subscriber.getOpenId());
            if (result.isSuccess()) {
                subscriptionService.decrementQuota(subscriber.getId());
            } else {
                log.debug("wechat push failed for {} errCode={} msg={}", subscriber.getOpenId(), result.getErrorCode(), result.getErrorMessage());
                if ("43101".equals(result.getErrorCode()) || "40003".equals(result.getErrorCode())) {
                    subscriptionService.disableSubscriber(subscriber.getId());
                }
            }
        });
    }
}

