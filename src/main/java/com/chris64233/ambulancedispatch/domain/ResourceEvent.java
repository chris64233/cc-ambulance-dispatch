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
 * 资源时间线事件：记录车辆/救护组在派遣生命周期中的每次状态变化。
 */
@Entity
@Table(name = "resource_events", indexes = {
        @Index(name = "idx_resource_event_resource", columnList = "resourceType,resourceId")
})
public class ResourceEvent {

    /** 时间线事件类型。 */
    public enum EventType {
        /** 资源被派遣占用。 */
        DISPATCHED,
        /** 资源因抢占从原派遣释放并转移给新派遣。 */
        TRANSFERRED_BY_PREEMPTION,
        /** 资源随派遣到达现场。 */
        ARRIVED_ON_SCENE,
        /** 派遣完成，资源释放。 */
        RELEASED_BY_COMPLETION,
        /** 派遣取消，资源释放。 */
        RELEASED_BY_CANCELLATION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResourceType resourceType;

    @Column(nullable = false)
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType eventType;

    @Column(nullable = false)
    private Long incidentId;

    @Column(nullable = false)
    private Long dispatchId;

    /** 抢占转移时关联的另一事件（被抢占方或抢占方），其余场景为 null。 */
    private Long relatedIncidentId;

    @Column(nullable = false)
    private Instant occurredAt;

    protected ResourceEvent() {
    }

    public ResourceEvent(ResourceType resourceType, Long resourceId, EventType eventType,
                         Long incidentId, Long dispatchId, Long relatedIncidentId, Instant occurredAt) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.eventType = eventType;
        this.incidentId = incidentId;
        this.dispatchId = dispatchId;
        this.relatedIncidentId = relatedIncidentId;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Long getIncidentId() {
        return incidentId;
    }

    public Long getDispatchId() {
        return dispatchId;
    }

    public Long getRelatedIncidentId() {
        return relatedIncidentId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
