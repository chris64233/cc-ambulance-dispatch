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
import jakarta.persistence.Version;
import java.util.HashSet;
import java.util.Set;

/**
 * 救护车：记录服务区域、车载设备能力与占用状态。
 */
@Entity
public class Ambulance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String plateNumber;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ambulance_service_area", joinColumns = @JoinColumn(name = "ambulance_id"))
    @Column(name = "service_area", nullable = false)
    private Set<String> serviceAreas = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ambulance_equipment", joinColumns = @JoinColumn(name = "ambulance_id"))
    @Column(name = "capability", nullable = false)
    private Set<String> equipmentCapabilities = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AmbulanceStatus status = AmbulanceStatus.AVAILABLE;

    @Version
    private long version;

    protected Ambulance() {
    }

    public Ambulance(String plateNumber, Set<String> serviceAreas, Set<String> equipmentCapabilities) {
        this.plateNumber = plateNumber;
        this.serviceAreas = new HashSet<>(serviceAreas);
        this.equipmentCapabilities = new HashSet<>(equipmentCapabilities);
    }

    public Long getId() {
        return id;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public Set<String> getServiceAreas() {
        return serviceAreas;
    }

    public Set<String> getEquipmentCapabilities() {
        return equipmentCapabilities;
    }

    public AmbulanceStatus getStatus() {
        return status;
    }

    public void setStatus(AmbulanceStatus status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }
}
