package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.AssignmentStatus;
import com.chris64233.ambulancedispatch.domain.Crew;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CrewRepository extends JpaRepository<Crew, Long> {

    Optional<Crew> findByName(String name);

    List<Crew> findByDutyStatusAndAssignmentStatus(
            com.chris64233.ambulancedispatch.domain.DutyStatus dutyStatus, AssignmentStatus assignmentStatus);

    /**
     * 原子占用：仅当救护组在岗且空闲时更新成功。
     *
     * @return 1 占用成功，0 表示不在岗或已被占用
     */
    @Modifying
    @Query("update Crew c set c.assignmentStatus = com.chris64233.ambulancedispatch.domain.AssignmentStatus.ASSIGNED "
            + "where c.id = :id "
            + "and c.dutyStatus = com.chris64233.ambulancedispatch.domain.DutyStatus.ON_DUTY "
            + "and c.assignmentStatus = com.chris64233.ambulancedispatch.domain.AssignmentStatus.IDLE")
    int occupyIfIdleAndOnDuty(@Param("id") Long id);

    @Modifying
    @Query("update Crew c set c.assignmentStatus = com.chris64233.ambulancedispatch.domain.AssignmentStatus.IDLE "
            + "where c.id = :id and c.assignmentStatus = com.chris64233.ambulancedispatch.domain.AssignmentStatus.ASSIGNED")
    int releaseIfAssigned(@Param("id") Long id);

    /** 行锁加载救护组。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Crew c where c.id = :id")
    java.util.Optional<Crew> findWithLockById(@Param("id") Long id);
}
