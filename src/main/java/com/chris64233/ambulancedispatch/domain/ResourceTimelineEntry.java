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
        @Index(name = "idx_timeline_crew", columnList = "crew_id,occurred_at")
})
public class ResourceTimelineEntry {

    public enum ResourceType {
        AMBULANCE, CREW
    }

    public enum Action {
        /** 派遣确认，资源被占用。 */
        ASSIGNED,
        /** 到达现场。 */
        ARRIVED,
        /** 到达目的医院。 */
        ARRIVED_HOSPITAL,
        /** 任务完成或取消，资源释放。 */
        RELEASED,
        /** 被抢占，资源从原派遣释放并立刻进入下一条 ASSIGNED。 */
        PREEMPTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResourceType resourceType;

    @Column(name = "ambulance_id")
    private Long ambulanceId;

    @Column(name = "crew_id")
    private Long crewId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispatch_id", nullable = false)
    private Dispatch dispatch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Action action;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected ResourceTimelineEntry() {
    }

    public ResourceTimelineEntry(ResourceType resourceType, Long ambulanceId, Long crewId,
                                 Dispatch dispatch, Action action, Instant occurredAt) {
        this.resourceType = resourceType;
        this.ambulanceId = ambulanceId;
        this.crewId = crewId;
        this.dispatch = dispatch;
        this.action = action;
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

    public Dispatch getDispatch() {
        return dispatch;
    }

    public Action getAction() {
        return action;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
