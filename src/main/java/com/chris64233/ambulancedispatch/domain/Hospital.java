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
 * 目的医院：上报可接收的急救类型、床位容量与接收状态。
 *
 * <p>{@code reservedBeds} 为已预留（含在途/在场未完成）床位数，预留采用条件更新，
 * 保证并发争抢时 {@code reservedBeds <= bedCapacity} 恒成立、容量不为负。
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
    @Column(name = "emergency_type", nullable = false)
    private Set<String> acceptedEmergencyTypes = new HashSet<>();

    /** 床位总容量，始终 >= reservedBeds。 */
    @Column(nullable = false)
    private int bedCapacity;

    /** 已预留床位数，预留/释放均走条件更新，任何情况下不为负且不超过总容量。 */
    @Column(nullable = false)
    private int reservedBeds = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReceivingStatus receivingStatus = ReceivingStatus.OPEN;

    @Version
    private long version;

    protected Hospital() {
    }

    public Hospital(String name, Set<String> acceptedEmergencyTypes, int bedCapacity,
                    ReceivingStatus receivingStatus) {
        this.name = name;
        this.acceptedEmergencyTypes = new HashSet<>(acceptedEmergencyTypes);
        this.bedCapacity = bedCapacity;
        this.reservedBeds = 0;
        this.receivingStatus = receivingStatus == null ? ReceivingStatus.OPEN : receivingStatus;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<String> getAcceptedEmergencyTypes() {
        return acceptedEmergencyTypes;
    }

    public void setAcceptedEmergencyTypes(Set<String> acceptedEmergencyTypes) {
        this.acceptedEmergencyTypes = new HashSet<>(acceptedEmergencyTypes);
    }

    public int getBedCapacity() {
        return bedCapacity;
    }

    public void setBedCapacity(int bedCapacity) {
        this.bedCapacity = bedCapacity;
    }

    public int getReservedBeds() {
        return reservedBeds;
    }

    public void setReservedBeds(int reservedBeds) {
        this.reservedBeds = reservedBeds;
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
