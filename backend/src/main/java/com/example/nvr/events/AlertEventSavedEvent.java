package com.example.nvr.events;

import com.example.nvr.persistence.AlertEventEntity;

/**
 * Simple application event published whenever a new alert event record is saved.
 */
public class AlertEventSavedEvent {

    private final AlertEventEntity entity;

    public AlertEventSavedEvent(AlertEventEntity entity) {
        this.entity = entity;
    }

    public AlertEventEntity getEntity() {
        return entity;
    }
}

