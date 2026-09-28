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
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 派遣单：一次派遣同时绑定一辆车和一个救护组。
 * 记录业务号（幂等键）、请求指纹（幂等冲突检测）、抢占来源与抢占链根。
 */
@Entity
@Table(name = "dispatch_record", indexes = {
        @Index(name = "idx_dispatch_biz_no", columnList = "bizNo", unique = true),
        @Index(name = "idx_dispatch_event", columnList = "event_id"),
        @Index(name = "idx_dispatch_hospital", columnList = "hospital_id"),
        @Index(name = "idx_dispatch_preempt_root", columnList = "preemption_root_id")
})
public class Dispatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String bizNo;

    /** SHA-256 of canonical request content; same bizNo with different fingerprint => conflict. */
    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private EmergencyEvent event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ambulance_id", nullable = false)
    private Ambulance ambulance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "crew_id", nullable = false)
    private Crew crew;

    /** 当前目的医院。改派成功时原地切换为新医院，保证一条派遣的目的地唯一。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DispatchStatus status = DispatchStatus.EN_ROUTE;

    /** 若本单由抢占产生，指向被抢占的原派遣单；否则为空。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preempted_dispatch_id")
    private Dispatch preemptedDispatch;

    /** 抢占链根派遣单；插入后回填为自身（自引用需先持久化取得 id）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preemption_root_id")
    private Dispatch preemptionRoot;

    @Column(nullable = false, updatable = false)
    private Instant dispatchedAt;

    private Instant arrivedAt;

    /** 到达目的医院的时刻；非空后不可再改派。 */
    private Instant arrivedAtHospitalAt;

    private Instant finishedAt;

    @Version
    private long version;

    protected Dispatch() {
    }

    public Dispatch(String bizNo, String requestFingerprint, EmergencyEvent event,
                    Ambulance ambulance, Crew crew, Hospital hospital, Dispatch preemptionRoot,
                    Dispatch preemptedDispatch, Instant dispatchedAt) {
        this.bizNo = bizNo;
        this.requestFingerprint = requestFingerprint;
        this.event = event;
        this.ambulance = ambulance;
        this.crew = crew;
        this.hospital = hospital;
        this.preemptionRoot = preemptionRoot;
        this.preemptedDispatch = preemptedDispatch;
        this.dispatchedAt = dispatchedAt;
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

    public EmergencyEvent getEvent() {
        return event;
    }

    public Ambulance getAmbulance() {
        return ambulance;
    }

    public Crew getCrew() {
        return crew;
    }

    public Hospital getHospital() {
        return hospital;
    }

    public void setHospital(Hospital hospital) {
        this.hospital = hospital;
    }

    public DispatchStatus getStatus() {
        return status;
    }

    public void setStatus(DispatchStatus status) {
        this.status = status;
    }

    public Dispatch getPreemptedDispatch() {
        return preemptedDispatch;
    }

    public Dispatch getPreemptionRoot() {
        return preemptionRoot;
    }

    public void setPreemptionRoot(Dispatch preemptionRoot) {
        this.preemptionRoot = preemptionRoot;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public void setArrivedAt(Instant arrivedAt) {
        this.arrivedAt = arrivedAt;
    }

    public Instant getArrivedAtHospitalAt() {
        return arrivedAtHospitalAt;
    }

    public void setArrivedAtHospitalAt(Instant arrivedAtHospitalAt) {
        this.arrivedAtHospitalAt = arrivedAtHospitalAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public long getVersion() {
        return version;
    }
}
