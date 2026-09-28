package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.Diversion;
import com.chris64233.ambulancedispatch.domain.DiversionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiversionRepository extends JpaRepository<Diversion, Long> {

    Optional<Diversion> findByBizNo(String bizNo);

    @Query("select d from Diversion d join fetch d.dispatch join fetch d.event "
            + "join fetch d.fromHospital left join fetch d.toHospital "
            + "where d.bizNo = :bizNo")
    Optional<Diversion> findDetailedByBizNo(@Param("bizNo") String bizNo);

    @Query("select d from Diversion d join fetch d.dispatch join fetch d.event "
            + "join fetch d.fromHospital left join fetch d.toHospital where d.id = :id")
    Optional<Diversion> findDetailedById(@Param("id") Long id);

    List<Diversion> findByDispatchIdOrderByCreatedAtAscIdAsc(Long dispatchId);

    List<Diversion> findByEventIdOrderByCreatedAtAscIdAsc(Long eventId);

    List<Diversion> findByDispatchIdAndStatusOrderByCreatedAtAscIdAsc(
            Long dispatchId, DiversionStatus status);
}
