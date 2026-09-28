package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.ResourceTimelineEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResourceTimelineEntryRepository extends JpaRepository<ResourceTimelineEntry, Long> {

    List<ResourceTimelineEntry> findByAmbulanceIdOrderByOccurredAtAscIdAsc(Long ambulanceId);

    List<ResourceTimelineEntry> findByCrewIdOrderByOccurredAtAscIdAsc(Long crewId);

    List<ResourceTimelineEntry> findByHospitalIdOrderByOccurredAtAscIdAsc(Long hospitalId);

    List<ResourceTimelineEntry> findByDispatchIdOrderByOccurredAtAscIdAsc(Long dispatchId);

    /** 事件完整时间线：该事件所有派遣（含抢占/改派）上的全部条目，按发生顺序排列。 */
    @Query("select t from ResourceTimelineEntry t where t.dispatch.event.id = :eventId "
            + "order by t.occurredAt asc, t.id asc")
    List<ResourceTimelineEntry> findByEventIdOrderByOccurredAtAscIdAsc(@Param("eventId") Long eventId);
}
