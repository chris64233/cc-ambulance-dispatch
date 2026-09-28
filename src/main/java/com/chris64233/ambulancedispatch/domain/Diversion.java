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
 * 改派记录：一条派遣可能经历多次改派（或拒收生成的待处理任务）。
 *
 * <p>成功改派先在新医院预留名额、随后释放原医院名额；失败记录同样持久化，
 * 用于相同业务号重放时返回首次结果。旧改派请求携带 {@code fromHospitalId}，
 * 与当前目的地不一致时拒绝，防止旧请求覆盖后来确认的目的地。
 */
@Entity
@Table(name = "diversion_record", indexes = {
        @Index(name = "idx_diversion_biz_no", columnList = "bizNo", unique = true),
        @Index(name = "idx_diversion_dispatch", columnList = "dispatch_id"),
        @Index(name = "idx_diversion_event", columnList = "event_id")
})
public class Diversion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String bizNo;

    /** SHA-256 of canonical request content; same bizNo with different content => conflict. */
    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispatch_id", nullable = false)
    private Dispatch dispatch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private EmergencyEvent event;

    /** 请求声明的原目的地；成功时与改派前实际目的地一致。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_hospital_id", nullable = false)
    private Hospital fromHospital;

    /** 目标新医院；拒收待处理任务没有目标医院时为空。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_hospital_id")
    private Hospital toHospital;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DiversionReason reason;

    /** 拒收原因明细（自由文本）；改派失败时也记录失败原因。 */
    @Column(length = 512)
    private String reasonDetail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DiversionStatus status;

    /** 失败时的业务错误码，重放时原样返回首次失败结果。 */
    @Column(length = 64)
    private String failureCode;

    /** 拒收来源改派任务（若由某次拒收记录消化而来）；一般为空。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_rejection_id")
    private Diversion resolvedRejection;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant finishedAt;

    protected Diversion() {
    }

    public Diversion(String bizNo, String requestFingerprint, Dispatch dispatch,
                     EmergencyEvent event, Hospital fromHospital, Hospital toHospital,
                     DiversionReason reason, String reasonDetail, DiversionStatus status,
                     Instant createdAt) {
        this.bizNo = bizNo;
        this.requestFingerprint = requestFingerprint;
        this.dispatch = dispatch;
        this.event = event;
        this.fromHospital = fromHospital;
        this.toHospital = toHospital;
        this.reason = reason;
        this.reasonDetail = reasonDetail;
        this.status = status;
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

    public String getReasonDetail() {
        return reasonDetail;
    }

    public DiversionStatus getStatus() {
        return status;
    }

    public void setStatus(DiversionStatus status) {
        this.status = status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public Diversion getResolvedRejection() {
        return resolvedRejection;
    }

    public void setResolvedRejection(Diversion resolvedRejection) {
        this.resolvedRejection = resolvedRejection;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
