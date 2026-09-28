package com.chris64233.ambulancedispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 医院拒收记录：拒收时记录原因、归还名额，并据此生成改派任务。
 * 拒收不释放车辆与救护组，派遣保持在现场状态。
 */
@Entity
@Table(name = "hospital_rejection", indexes = {
        @Index(name = "idx_rejection_dispatch", columnList = "dispatch_id"),
        @Index(name = "idx_rejection_reservation", columnList = "reservation_id")
})
public class HospitalRejection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispatch_id", nullable = false)
    private Dispatch dispatch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private EmergencyEvent event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private HospitalReservation reservation;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant rejectedAt;

    protected HospitalRejection() {
    }

    public HospitalRejection(Hospital hospital, Dispatch dispatch, EmergencyEvent event,
                             HospitalReservation reservation, String reason, Instant rejectedAt) {
        this.hospital = hospital;
        this.dispatch = dispatch;
        this.event = event;
        this.reservation = reservation;
        this.reason = reason;
        this.rejectedAt = rejectedAt;
    }

    public Long getId() {
        return id;
    }

    public Hospital getHospital() {
        return hospital;
    }

    public Dispatch getDispatch() {
        return dispatch;
    }

    public EmergencyEvent getEvent() {
        return event;
    }

    public HospitalReservation getReservation() {
        return reservation;
    }

    public String getReason() {
        return reason;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }
}
