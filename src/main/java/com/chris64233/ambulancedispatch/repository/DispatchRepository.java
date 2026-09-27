package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.domain.DispatchStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DispatchRepository extends JpaRepository<Dispatch, Long> {

    Optional<Dispatch> findByBizNo(String bizNo);

    List<Dispatch> findByEventIdOrderByDispatchedAtDesc(Long eventId);

    List<Dispatch> findByPreemptionRootIdOrderByDispatchedAtAsc(Long rootId);

    @Query("select d from Dispatch d join fetch d.event join fetch d.ambulance join fetch d.crew "
            + "where d.id = :id")
    Optional<Dispatch> findDetailedById(@Param("id") Long id);

    @Query("select d from Dispatch d join fetch d.event join fetch d.ambulance join fetch d.crew "
            + "where d.bizNo = :bizNo")
    Optional<Dispatch> findDetailedByBizNo(@Param("bizNo") String bizNo);

    /**
     * 某车辆当前占用它的活跃派遣（在途或已到场）。
     */
    @Query("select d from Dispatch d where d.ambulance.id = :ambulanceId and d.status in :statuses")
    List<Dispatch> findActiveByAmbulance(@Param("ambulanceId") Long ambulanceId,
                                         @Param("statuses") List<DispatchStatus> statuses);

    @Query("select d from Dispatch d where d.crew.id = :crewId and d.status in :statuses")
    List<Dispatch> findActiveByCrew(@Param("crewId") Long crewId,
                                    @Param("statuses") List<DispatchStatus> statuses);

    /** 行锁加载派遣单，抢占与生命周期操作使用。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dispatch d where d.id = :id")
    java.util.Optional<Dispatch> findWithLockById(@Param("id") Long id);
}
