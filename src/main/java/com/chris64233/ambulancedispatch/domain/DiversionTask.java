package com.chris64233.ambulancedispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 改派任务：医院拒收后生成，指向需要另找接收医院的派遣。
 * 改派成功（新医院预留完成）后置为 FULFILLED。车辆与救护组不随拒收释放。
 */
@Entity
@Table(name = "diversion_task", indexes = {
        @Index(name = "idx_diversion_task_dispatch", columnList = "dispatch_id"),
        @Index(name = "idx_diversion_task_event", columnList = "event_id"),
        @Index(name = "idx_diversion_task_status", columnList = "status")
})
public class DiversionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispatch_id", nullable = false)
    private Dispatch dispatch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private EmergencyEvent event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_hospital_id", nullable = false)
    private Hospital fromHospital;

    /** 触发改派的拒收记录；因医院关闭/病情变化主动申请时可为空。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejection_id")
    private HospitalRejection rejection;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DiversionReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DiversionTaskStatus status = DiversionTaskStatus.PENDING;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant fulfilledAt;

    protected DiversionTask() {
    }

    public DiversionTask(Dispatch dispatch, EmergencyEvent event, Hospital fromHospital,
                         HospitalRejection rejection, DiversionReason reason, Instant createdAt) {
        this.dispatch = dispatch;
        this.event = event;
        this.fromHospital = fromHospital;
        this.rejection = rejection;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Dispatch getDispatch() {
        return dispatch;
    }

    public EmergencyEvent getEvent() {
        return event;
    }

    public Hospital getFromHospital() {
        return fromHospital;
    }

    public HospitalRejection getRejection() {
        return rejection;
    }

    public DiversionReason getReason() {
        return reason;
    }

    public DiversionTaskStatus getStatus() {
        return status;
    }

    public void setStatus(DiversionTaskStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public void setFulfilledAt(Instant fulfilledAt) {
        this.fulfilledAt = fulfilledAt;
    }
}
