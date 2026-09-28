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
import java.util.HashSet;
import java.util.Set;

/**
 * 医院：上报可接收的急救类型、床位容量与接收状态。
 * {@code reservedCount} 为当前占用名额数，与容量配合做条件更新，保证任何并发下不为负、不超卖。
 */
@Entity
@Table(name = "hospital")
public class Hospital {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "hospital_accepted_type", joinColumns = @JoinColumn(name = "hospital_id"))
    @Column(name = "emergency_type", nullable = false, length = 32)
    private Set<EmergencyType> acceptedEmergencyTypes = new HashSet<>();

    /** 床位容量，>= 0。 */
    @Column(nullable = false)
    private int bedCapacity;

    /** 已预留床位数，条件更新保证 0 <= reservedCount <= bedCapacity。 */
    @Column(nullable = false)
    private int reservedCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReceivingStatus receivingStatus = ReceivingStatus.OPEN;

    @Version
    private long version;

    protected Hospital() {
    }

    public Hospital(String name, Set<EmergencyType> acceptedEmergencyTypes,
                    int bedCapacity, ReceivingStatus receivingStatus) {
        this.name = name;
        this.acceptedEmergencyTypes = new HashSet<>(acceptedEmergencyTypes);
        this.bedCapacity = bedCapacity;
        this.reservedCount = 0;
        this.receivingStatus = receivingStatus;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<EmergencyType> getAcceptedEmergencyTypes() {
        return acceptedEmergencyTypes;
    }

    public void setAcceptedEmergencyTypes(Set<EmergencyType> acceptedEmergencyTypes) {
        this.acceptedEmergencyTypes = new HashSet<>(acceptedEmergencyTypes);
    }

    public int getBedCapacity() {
        return bedCapacity;
    }

    public void setBedCapacity(int bedCapacity) {
        this.bedCapacity = bedCapacity;
    }

    public int getReservedCount() {
        return reservedCount;
    }

    /**
     * 在已持行锁的前提下占用一个名额。调用方必须先校验开放接收、类型可接收且有空床。
     *
     * @throws IllegalStateException 无可用名额（不应该发生，作为不变量兜底）
     */
    public void holdOneBed() {
        if (receivingStatus != ReceivingStatus.OPEN || reservedCount >= bedCapacity) {
            throw new IllegalStateException("医院 " + name + " 无可用床位或已关闭接收");
        }
        reservedCount++;
    }

    /**
     * 在已持行锁的前提下释放一个名额，永不为负。
     *
     * @return true 释放成功；false 当前没有占用名额
     */
    public boolean releaseOneBed() {
        if (reservedCount <= 0) {
            return false;
        }
        reservedCount--;
        return true;
    }

    /** 当前是否可预留指定类型的床位。 */
    public boolean canAccept(EmergencyType type) {
        return receivingStatus == ReceivingStatus.OPEN
                && reservedCount < bedCapacity
                && acceptedEmergencyTypes.contains(type);
    }

    public ReceivingStatus getReceivingStatus() {
        return receivingStatus;
    }

    public void setReceivingStatus(ReceivingStatus receivingStatus) {
        this.receivingStatus = receivingStatus;
    }

    public long getVersion() {
        return version;
    }
}
