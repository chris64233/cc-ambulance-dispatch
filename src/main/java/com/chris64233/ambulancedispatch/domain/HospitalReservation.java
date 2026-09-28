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
 * 医院接收名额预留：派遣确认时为事件原子预留，随派遣占用车辆与救护组一起生效。
 * 预留记录只新增并在释放/拒收时置为终态，保留完整审计轨迹。
 */
@Entity
@Table(name = "hospital_reservation", indexes = {
        @Index(name = "idx_reservation_dispatch", columnList = "dispatch_id"),
        @Index(name = "idx_reservation_hospital", columnList = "hospital_id"),
        @Index(name = "idx_reservation_event", columnList = "event_id")
})
public class HospitalReservation {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EmergencyType emergencyType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status = ReservationStatus.HELD;

    @Column(nullable = false, updatable = false)
    private Instant reservedAt;

    private Instant releasedAt;

    protected HospitalReservation() {
    }

    public HospitalReservation(Hospital hospital, Dispatch dispatch, EmergencyEvent event,
                               EmergencyType emergencyType, Instant reservedAt) {
        this.hospital = hospital;
        this.dispatch = dispatch;
        this.event = event;
        this.emergencyType = emergencyType;
        this.reservedAt = reservedAt;
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

    public EmergencyType getEmergencyType() {
        return emergencyType;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public Instant getReservedAt() {
        return reservedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }
}
