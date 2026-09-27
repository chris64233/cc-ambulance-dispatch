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
 * 救护组：记录人员资质、值勤状态与派勤状态。
 */
@Entity
public class Crew {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "crew_qualification", joinColumns = @JoinColumn(name = "crew_id"))
    @Column(name = "qualification", nullable = false)
    private Set<String> qualifications = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DutyStatus dutyStatus = DutyStatus.ON_DUTY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssignmentStatus assignmentStatus = AssignmentStatus.IDLE;

    @Version
    private long version;

    protected Crew() {
    }

    public Crew(String name, Set<String> qualifications, DutyStatus dutyStatus) {
        this.name = name;
        this.qualifications = new HashSet<>(qualifications);
        this.dutyStatus = dutyStatus;
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

    public AssignmentStatus getAssignmentStatus() {
        return assignmentStatus;
    }

    public void setAssignmentStatus(AssignmentStatus assignmentStatus) {
        this.assignmentStatus = assignmentStatus;
    }

    public long getVersion() {
        return version;
    }
}
