package com.example.nvr.persistence;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "radar_targets")
public class RadarTargetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "radar_host", length = 128)
    private String radarHost;

    @Column(name = "control_port")
    private Integer controlPort;

    @Column(name = "data_port")
    private Integer dataPort;

    @Column(name = "actual_data_port")
    private Integer actualDataPort;

    @Column(name = "transport_tcp")
    private boolean transportTcp;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "payload_length")
    private Integer payloadLength;

    @Column(name = "target_count")
    private Integer targetCount;

    @Column(name = "target_id")
    private Integer targetId;

    @Column(name = "longitudinal_distance")
    private Double longitudinalDistance;

    @Column(name = "lateral_distance")
    private Double lateralDistance;

    @Column(name = "speed")
    private Double speed;

    @Column(name = "range_value")
    private Double range;

    @Column(name = "angle")
    private Double angle;

    @Column(name = "amplitude")
    private Integer amplitude;

    @Column(name = "snr")
    private Integer snr;

    @Column(name = "rcs")
    private Double rcs;

    @Column(name = "element_count")
    private Integer elementCount;

    @Column(name = "target_length")
    private Integer targetLength;

    @Column(name = "detection_frames")
    private Integer detectionFrames;

    @Column(name = "track_state")
    private Integer trackState;

    @Column(name = "reserve1")
    private Integer reserve1;

    @Column(name = "reserve2")
    private Integer reserve2;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt = Instant.now();

    public RadarTargetEntity() {
    }

    public RadarTargetEntity(String radarHost, Integer controlPort, Integer dataPort, Integer actualDataPort,
                             boolean transportTcp, Integer statusCode, Integer payloadLength, Integer targetCount,
                             Integer targetId, Double longitudinalDistance, Double lateralDistance, Double speed,
                             Double range, Double angle, Integer amplitude, Integer snr, Double rcs,
                             Integer elementCount, Integer targetLength, Integer detectionFrames,
                             Integer trackState, Integer reserve1, Integer reserve2, Instant capturedAt) {
        this.radarHost = radarHost;
        this.controlPort = controlPort;
        this.dataPort = dataPort;
        this.actualDataPort = actualDataPort;
        this.transportTcp = transportTcp;
        this.statusCode = statusCode;
        this.payloadLength = payloadLength;
        this.targetCount = targetCount;
        this.targetId = targetId;
        this.longitudinalDistance = longitudinalDistance;
        this.lateralDistance = lateralDistance;
        this.speed = speed;
        this.range = range;
        this.angle = angle;
        this.amplitude = amplitude;
        this.snr = snr;
        this.rcs = rcs;
        this.elementCount = elementCount;
        this.targetLength = targetLength;
        this.detectionFrames = detectionFrames;
        this.trackState = trackState;
        this.reserve1 = reserve1;
        this.reserve2 = reserve2;
        this.capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }

    public Long getId() {
        return id;
    }

    public String getRadarHost() {
        return radarHost;
    }

    public Integer getControlPort() {
        return controlPort;
    }

    public Integer getDataPort() {
        return dataPort;
    }

    public Integer getActualDataPort() {
        return actualDataPort;
    }

    public boolean isTransportTcp() {
        return transportTcp;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public Integer getPayloadLength() {
        return payloadLength;
    }

    public Integer getTargetCount() {
        return targetCount;
    }

    public Integer getTargetId() {
        return targetId;
    }

    public Double getLongitudinalDistance() {
        return longitudinalDistance;
    }

    public Double getLateralDistance() {
        return lateralDistance;
    }

    public Double getSpeed() {
        return speed;
    }

    public Double getRange() {
        return range;
    }

    public Double getAngle() {
        return angle;
    }

    public Integer getAmplitude() {
        return amplitude;
    }

    public Integer getSnr() {
        return snr;
    }

    public Double getRcs() {
        return rcs;
    }

    public Integer getElementCount() {
        return elementCount;
    }

    public Integer getTargetLength() {
        return targetLength;
    }

    public Integer getDetectionFrames() {
        return detectionFrames;
    }

    public Integer getTrackState() {
        return trackState;
    }

    public Integer getReserve1() {
        return reserve1;
    }

    public Integer getReserve2() {
        return reserve2;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
