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

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 急救车辆：记录服务区域、设备能力和当前状态。
 */
@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 车辆呼号，全局唯一。 */
    @Column(nullable = false, unique = true)
    private String callSign;

    /** 服务区域，派遣时要求与事件区域一致。 */
    @Column(nullable = false)
    private String serviceArea;

    /** 车载设备能力标签，如 DEFIBRILLATOR、STRETCHER。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "vehicle_equipment", joinColumns = @JoinColumn(name = "vehicle_id"))
    @Column(name = "equipment", nullable = false)
    private Set<String> equipment = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleStatus status = VehicleStatus.AVAILABLE;

    protected Vehicle() {
    }

    public Vehicle(String callSign, String serviceArea, Set<String> equipment) {
        this.callSign = callSign;
        this.serviceArea = serviceArea;
        this.equipment = new LinkedHashSet<>(equipment);
    }

    public Long getId() {
        return id;
    }

    public String getCallSign() {
        return callSign;
    }

    public String getServiceArea() {
        return serviceArea;
    }

    public Set<String> getEquipment() {
        return equipment;
    }

    public VehicleStatus getStatus() {
        return status;
    }

    public void setStatus(VehicleStatus status) {
        this.status = status;
    }
}
