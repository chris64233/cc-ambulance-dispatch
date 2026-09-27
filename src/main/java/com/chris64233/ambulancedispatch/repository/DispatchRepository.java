package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.domain.DispatchStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DispatchRepository extends JpaRepository<Dispatch, Long> {

    /** 按派遣业务号查询，用于幂等判断。 */
    Optional<Dispatch> findByRequestId(String requestId);

    /** 悲观写锁读取，用于到达/完成/取消等状态流转。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dispatch d where d.id = :id")
    Optional<Dispatch> findByIdForUpdate(@Param("id") Long id);

    Optional<Dispatch> findByVehicleIdAndStatus(Long vehicleId, DispatchStatus status);

    Optional<Dispatch> findByCrewIdAndStatus(Long crewId, DispatchStatus status);

    List<Dispatch> findByIncidentIdOrderByIdAsc(Long incidentId);
}
