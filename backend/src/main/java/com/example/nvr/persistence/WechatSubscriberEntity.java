package com.example.nvr.persistence;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "wx_subscribers", indexes = {
        @Index(name = "idx_wx_subscribers_open_template", columnList = "open_id, template_id"),
        @Index(name = "idx_wx_subscribers_template_quota", columnList = "template_id, quota")
})
public class WechatSubscriberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "open_id", nullable = false, length = 64)
    private String openId;

    @Column(name = "template_id", nullable = false, length = 64)
    private String templateId;

    @Column(name = "quota", nullable = false)
    private int quota = 0;

    @Column(name = "last_scene", length = 64)
    private String lastScene;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public String getOpenId() {
        return openId;
    }

    public void setOpenId(String openId) {
        this.openId = openId;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public int getQuota() {
        return quota;
    }

    public void setQuota(int quota) {
        this.quota = quota;
    }

    public String getLastScene() {
        return lastScene;
    }

    public void setLastScene(String lastScene) {
        this.lastScene = lastScene;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

