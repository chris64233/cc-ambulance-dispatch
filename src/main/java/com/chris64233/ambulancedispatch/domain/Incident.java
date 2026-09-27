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

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 急救事件：包含位置（服务区域 + 详细地址）、优先级和所需能力。
 */
@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 事件所属服务区域，用于匹配车辆的服务区域。 */
    @Column(nullable = false)
    private String area;

    /** 详细位置描述。 */
    @Column(nullable = false)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    /** 所需能力标签，由车辆设备与救护组资质的并集覆盖。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "incident_required_capabilities", joinColumns = @JoinColumn(name = "incident_id"))
    @Column(name = "capability", nullable = false)
    private Set<String> requiredCapabilities = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status = IncidentStatus.PENDING;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Incident() {
    }

    public Incident(String area, String location, Priority priority, Set<String> requiredCapabilities, Instant now) {
        this.area = area;
        this.location = location;
        this.priority = priority;
        this.requiredCapabilities = new LinkedHashSet<>(requiredCapabilities);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getArea() {
        return area;
    }

    public String getLocation() {
        return location;
    }

    public Priority getPriority() {
        return priority;
    }

    public Set<String> getRequiredCapabilities() {
        return requiredCapabilities;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void transitionTo(IncidentStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    public boolean isTerminal() {
        return status == IncidentStatus.COMPLETED || status == IncidentStatus.CANCELLED;
    }
}
