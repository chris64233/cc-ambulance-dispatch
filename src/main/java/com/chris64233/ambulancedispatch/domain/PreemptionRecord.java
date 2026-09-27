package com.chris64233.ambulancedispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 抢占记录：高优先级事件抢占低优先级派遣时生成，构成抢占链。
 */
@Entity
@Table(name = "preemption_records", indexes = {
        @Index(name = "idx_preemption_new_incident", columnList = "preemptingIncidentId"),
        @Index(name = "idx_preemption_old_incident", columnList = "preemptedIncidentId")
})
public class PreemptionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发起抢占的事件 / 派遣。 */
    @Column(nullable = false)
    private Long preemptingIncidentId;

    @Column(nullable = false)
    private Long preemptingDispatchId;

    /** 被抢占的事件 / 派遣。 */
    @Column(nullable = false)
    private Long preemptedIncidentId;

    @Column(nullable = false)
    private Long preemptedDispatchId;

    @Column(nullable = false)
    private Long vehicleId;

    @Column(nullable = false)
    private Long crewId;

    @Column(nullable = false)
    private Instant occurredAt;

    protected PreemptionRecord() {
    }

    public PreemptionRecord(Long preemptingIncidentId, Long preemptingDispatchId,
                            Long preemptedIncidentId, Long preemptedDispatchId,
                            Long vehicleId, Long crewId, Instant occurredAt) {
        this.preemptingIncidentId = preemptingIncidentId;
        this.preemptingDispatchId = preemptingDispatchId;
        this.preemptedIncidentId = preemptedIncidentId;
        this.preemptedDispatchId = preemptedDispatchId;
        this.vehicleId = vehicleId;
        this.crewId = crewId;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getPreemptingIncidentId() {
        return preemptingIncidentId;
    }

    public Long getPreemptingDispatchId() {
        return preemptingDispatchId;
    }

    public Long getPreemptedIncidentId() {
        return preemptedIncidentId;
    }

    public Long getPreemptedDispatchId() {
        return preemptedDispatchId;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public Long getCrewId() {
        return crewId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
