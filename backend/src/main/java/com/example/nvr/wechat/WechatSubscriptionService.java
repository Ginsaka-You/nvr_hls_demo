package com.example.nvr.wechat;

import com.example.nvr.persistence.WechatSubscriberEntity;
import com.example.nvr.persistence.WechatSubscriberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class WechatSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WechatSubscriptionService.class);

    private final WechatSubscriberRepository repository;

    public WechatSubscriptionService(WechatSubscriberRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void recordAuthorization(String openId, String templateId, int quotaDelta, String scene) {
        if (!StringUtils.hasText(openId) || !StringUtils.hasText(templateId) || quotaDelta <= 0) {
            return;
        }
        Optional<WechatSubscriberEntity> existing = repository.findByOpenIdAndTemplateId(openId, templateId);
        WechatSubscriberEntity entity = existing.orElseGet(WechatSubscriberEntity::new);
        entity.setOpenId(openId);
        entity.setTemplateId(templateId);
        entity.setQuota(Math.min(20, entity.getQuota() + quotaDelta)); // hard cap to avoid runaway
        entity.setLastScene(scene);
        entity.setUpdatedAt(Instant.now());
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<WechatSubscriberEntity> findEligible(String templateId) {
        if (!StringUtils.hasText(templateId)) {
            return List.of();
        }
        return repository.findByTemplateIdAndQuotaGreaterThan(templateId, 0);
    }

    @Transactional
    public void decrementQuota(Long id) {
        if (id == null) {
            return;
        }
        repository.findById(id).ifPresent(entity -> {
            int quota = Math.max(0, entity.getQuota() - 1);
            entity.setQuota(quota);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
        });
    }

    @Transactional
    public void disableSubscriber(Long id) {
        if (id == null) {
            return;
        }
        repository.findById(id).ifPresent(entity -> {
            entity.setQuota(0);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
        });
    }
}

