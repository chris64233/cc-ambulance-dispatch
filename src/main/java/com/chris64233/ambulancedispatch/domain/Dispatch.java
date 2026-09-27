package com.chris64233.ambulancedispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 派遣单：一次派遣同时选定一辆车和一组救护组。
 * requestId 为派遣业务号（幂等键），全局唯一；
 * contentFingerprint 记录请求内容指纹，用于区分幂等重放与内容冲突。
 */
@Entity
@Table(name = "dispatches", indexes = {
        @Index(name = "idx_dispatch_incident", columnList = "incidentId"),
        @Index(name = "idx_dispatch_vehicle", columnList = "vehicleId"),
        @Index(name = "idx_dispatch_crew", columnList = "crewId")
})
public class Dispatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 派遣业务号（客户端幂等键）。 */
    @Column(nullable = false, unique = true)
    private String requestId;

    /** 请求内容指纹：incidentId/vehicleId/crewId 的组合。 */
    @Column(nullable = false)
    private String contentFingerprint;

    @Column(nullable = false)
    private Long incidentId;

    @Column(nullable = false)
    private Long vehicleId;

    @Column(nullable = false)
    private Long crewId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DispatchStatus status = DispatchStatus.ACTIVE;

    /** 到达现场时间；非空后不可再被抢占。 */
    private Instant arrivedAt;

    @Column(nullable = false)
    private Instant createdAt;

    /** 终态（PREEMPTED/COMPLETED/CANCELLED）形成时间。 */
    private Instant closedAt;

    protected Dispatch() {
    }

    public Dispatch(String requestId, String contentFingerprint, Long incidentId,
                    Long vehicleId, Long crewId, Instant now) {
        this.requestId = requestId;
        this.contentFingerprint = contentFingerprint;
        this.incidentId = incidentId;
        this.vehicleId = vehicleId;
        this.crewId = crewId;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getContentFingerprint() {
        return contentFingerprint;
    }

    public Long getIncidentId() {
        return incidentId;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public Long getCrewId() {
        return crewId;
    }

    public DispatchStatus getStatus() {
        return status;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void markArrived(Instant now) {
        this.arrivedAt = now;
    }

    public void close(DispatchStatus terminal, Instant now) {
        this.status = terminal;
        this.closedAt = now;
    }

    public boolean hasArrived() {
        return arrivedAt != null;
    }
}
