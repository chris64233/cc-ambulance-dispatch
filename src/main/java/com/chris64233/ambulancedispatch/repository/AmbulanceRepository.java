package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.Ambulance;
import com.chris64233.ambulancedispatch.domain.AmbulanceStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AmbulanceRepository extends JpaRepository<Ambulance, Long> {

    Optional<Ambulance> findByPlateNumber(String plateNumber);

    List<Ambulance> findByStatus(AmbulanceStatus status);

    /**
     * 原子占用：仅当车辆空闲时更新成功。并发争抢时数据库行锁保证只有一个事务影响 1 行。
     *
     * @return 受影响行数，1 表示占用成功，0 表示已被占用
     */
    @Modifying
    @Query("update Ambulance a set a.status = com.chris64233.ambulancedispatch.domain.AmbulanceStatus.DISPATCHED "
            + "where a.id = :id and a.status = com.chris64233.ambulancedispatch.domain.AmbulanceStatus.AVAILABLE")
    int occupyIfAvailable(@Param("id") Long id);

    @Modifying
    @Query("update Ambulance a set a.status = com.chris64233.ambulancedispatch.domain.AmbulanceStatus.AVAILABLE "
            + "where a.id = :id and a.status = com.chris64233.ambulancedispatch.domain.AmbulanceStatus.DISPATCHED")
    int releaseIfDispatched(@Param("id") Long id);

    /** 行锁加载车辆。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Ambulance a where a.id = :id")
    java.util.Optional<Ambulance> findWithLockById(@Param("id") Long id);
}
