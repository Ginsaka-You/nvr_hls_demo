package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WechatSubscriberRepository extends JpaRepository<WechatSubscriberEntity, Long> {

    Optional<WechatSubscriberEntity> findByOpenIdAndTemplateId(String openId, String templateId);

    List<WechatSubscriberEntity> findByTemplateIdAndQuotaGreaterThan(String templateId, int quota);
}

