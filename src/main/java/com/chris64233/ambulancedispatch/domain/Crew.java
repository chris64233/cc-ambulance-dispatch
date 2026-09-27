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
 * 救护组：记录人员资质和当前值勤状态。
 */
@Entity
@Table(name = "crews")
public class Crew {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 救护组名称，全局唯一。 */
    @Column(nullable = false, unique = true)
    private String name;

    /** 人员资质标签，如 PARAMEDIC、DOCTOR。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "crew_qualifications", joinColumns = @JoinColumn(name = "crew_id"))
    @Column(name = "qualification", nullable = false)
    private Set<String> qualifications = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DutyStatus dutyStatus = DutyStatus.ON_DUTY;

    protected Crew() {
    }

    public Crew(String name, Set<String> qualifications) {
        this.name = name;
        this.qualifications = new LinkedHashSet<>(qualifications);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<String> getQualifications() {
        return qualifications;
    }

    public DutyStatus getDutyStatus() {
        return dutyStatus;
    }

    public void setDutyStatus(DutyStatus dutyStatus) {
        this.dutyStatus = dutyStatus;
    }
}
