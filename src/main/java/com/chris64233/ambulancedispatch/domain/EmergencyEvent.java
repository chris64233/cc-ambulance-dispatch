package com.chris64233.ambulancedispatch.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * 急救事件：位置、所在服务区域、优先级与所需能力。
 */
@Entity
@Table(name = "emergency_event")
public class EmergencyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String location;

    @Column(nullable = false)
    private String serviceArea;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Priority priority;

    /** 急救类型，用于匹配医院可接收类型与床位预留。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EmergencyType emergencyType = EmergencyType.GENERAL;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_required_capability", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "capability", nullable = false)
    private Set<String> requiredCapabilities = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventStatus status = EventStatus.PENDING;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected EmergencyEvent() {
    }

    public EmergencyEvent(String location, String serviceArea, Priority priority,
                          Set<String> requiredCapabilities, Instant createdAt) {
        this(location, serviceArea, priority, EmergencyType.GENERAL, requiredCapabilities, createdAt);
    }

    public EmergencyEvent(String location, String serviceArea, Priority priority,
                          EmergencyType emergencyType, Set<String> requiredCapabilities,
                          Instant createdAt) {
        this.location = location;
        this.serviceArea = serviceArea;
        this.priority = priority;
        this.emergencyType = emergencyType;
        this.requiredCapabilities = new HashSet<>(requiredCapabilities);
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getLocation() {
        return location;
    }

    public String getServiceArea() {
        return serviceArea;
    }

    public Priority getPriority() {
        return priority;
    }

    public EmergencyType getEmergencyType() {
        return emergencyType;
    }

    public Set<String> getRequiredCapabilities() {
        return requiredCapabilities;
    }

    public EventStatus getStatus() {
        return status;
    }

    public void setStatus(EventStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
