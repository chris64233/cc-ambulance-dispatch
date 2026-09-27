package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.EmergencyEvent;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmergencyEventRepository extends JpaRepository<EmergencyEvent, Long> {

    /** 行锁加载事件，串行化同一事件上的派遣/抢占/生命周期操作。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EmergencyEvent e where e.id = :id")
    Optional<EmergencyEvent> findWithLockById(@Param("id") Long id);
}
