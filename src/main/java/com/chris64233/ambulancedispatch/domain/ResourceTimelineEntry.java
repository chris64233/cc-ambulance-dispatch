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
 * 资源时间线条目：记录某辆车或某个救护组被哪条派遣占用/释放。
 * 只增不改，用于资源时间线查询与抢占审计。
 */
@Entity
@Table(name = "resource_timeline_entry", indexes = {
        @Index(name = "idx_timeline_ambulance", columnList = "ambulance_id,occurred_at"),
        @Index(name = "idx_timeline_crew", columnList = "crew_id,occurred_at"),
        @Index(name = "idx_timeline_hospital", columnList = "hospital_id,occurred_at")
})
public class ResourceTimelineEntry {

    public enum ResourceType {
        AMBULANCE, CREW, HOSPITAL
    }

    public enum Action {
        /** 派遣确认，资源被占用。 */
        ASSIGNED,
        /** 到达现场。 */
        ARRIVED,
        /** 任务完成或取消，资源释放。 */
        RELEASED,
        /** 被抢占，资源从原派遣释放并立刻进入下一条 ASSIGNED。 */
        PREEMPTED,
        /** 医院接收名额预留成功。 */
        RESERVED,
        /** 医院接收名额释放（完成/取消/改派成功后）。 */
        RESERVATION_RELEASED,
        /** 到达目的医院，此后不可再改派。 */
        ARRIVED_HOSPITAL,
        /** 改派成功，目的地切换（note 记录原因，hospitalId 为新医院）。 */
        DIVERTED,
        /** 医院拒收（note 记录拒收原因，hospitalId 为拒收医院）。 */
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ResourceType resourceType;

    @Column(name = "ambulance_id")
    private Long ambulanceId;

    @Column(name = "crew_id")
    private Long crewId;

    @Column(name = "hospital_id")
    private Long hospitalId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispatch_id", nullable = false)
    private Dispatch dispatch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Action action;

    /** 补充说明：改派/拒收原因、涉及的原/新医院等。 */
    @Column(length = 512)
    private String note;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected ResourceTimelineEntry() {
    }

    public ResourceTimelineEntry(ResourceType resourceType, Long ambulanceId, Long crewId,
                                 Dispatch dispatch, Action action, Instant occurredAt) {
        this(resourceType, ambulanceId, crewId, null, dispatch, action, null, occurredAt);
    }

    public ResourceTimelineEntry(ResourceType resourceType, Long ambulanceId, Long crewId,
                                 Long hospitalId, Dispatch dispatch, Action action,
                                 String note, Instant occurredAt) {
        this.resourceType = resourceType;
        this.ambulanceId = ambulanceId;
        this.crewId = crewId;
        this.hospitalId = hospitalId;
        this.dispatch = dispatch;
        this.action = action;
        this.note = note;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public Long getAmbulanceId() {
        return ambulanceId;
    }

    public Long getCrewId() {
        return crewId;
    }

    public Long getHospitalId() {
        return hospitalId;
    }

    public Dispatch getDispatch() {
        return dispatch;
    }

    public Action getAction() {
        return action;
    }

    public String getNote() {
        return note;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
