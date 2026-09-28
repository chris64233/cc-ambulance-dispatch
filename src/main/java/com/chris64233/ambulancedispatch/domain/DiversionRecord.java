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
 * 改派记录：每次途中改派申请一条，成功与失败都留痕，构成事件的历次改派历史。
 * 业务号唯一，保证同号改派的幂等与冲突检测。
 */
@Entity
@Table(name = "diversion_record", indexes = {
        @Index(name = "idx_diversion_biz_no", columnList = "bizNo", unique = true),
        @Index(name = "idx_diversion_dispatch", columnList = "dispatch_id"),
        @Index(name = "idx_diversion_event", columnList = "event_id")
})
public class DiversionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String bizNo;

    /** 同业务号幂等冲突检测用的内容指纹。 */
    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispatch_id", nullable = false)
    private Dispatch dispatch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private EmergencyEvent event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_hospital_id", nullable = false)
    private Hospital fromHospital;

    /** 申请的目标医院；申请时记录，失败时也保留申请目标。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_hospital_id", nullable = false)
    private Hospital toHospital;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DiversionReason reason;

    /** 本次改派按何急救类型匹配目标医院（病情变化时可能与事件原始类型不同）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EmergencyType emergencyType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DiversionStatus status;

    /** 失败时记录原因（满床、关闭、不接收类型等）；成功为空。 */
    @Column(length = 500)
    private String failMessage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected DiversionRecord() {
    }

    public DiversionRecord(String bizNo, String requestFingerprint, Dispatch dispatch,
                           EmergencyEvent event, Hospital fromHospital, Hospital toHospital,
                           DiversionReason reason, EmergencyType emergencyType,
                           DiversionStatus status, String failMessage, Instant createdAt) {
        this.bizNo = bizNo;
        this.requestFingerprint = requestFingerprint;
        this.dispatch = dispatch;
        this.event = event;
        this.fromHospital = fromHospital;
        this.toHospital = toHospital;
        this.reason = reason;
        this.emergencyType = emergencyType;
        this.status = status;
        this.failMessage = failMessage;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getBizNo() {
        return bizNo;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
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

    public Hospital getToHospital() {
        return toHospital;
    }

    public DiversionReason getReason() {
        return reason;
    }

    public EmergencyType getEmergencyType() {
        return emergencyType;
    }

    public DiversionStatus getStatus() {
        return status;
    }

    public String getFailMessage() {
        return failMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
