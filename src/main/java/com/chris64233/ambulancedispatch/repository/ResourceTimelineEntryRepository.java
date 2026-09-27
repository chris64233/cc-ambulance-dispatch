package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.ResourceTimelineEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceTimelineEntryRepository extends JpaRepository<ResourceTimelineEntry, Long> {

    List<ResourceTimelineEntry> findByAmbulanceIdOrderByOccurredAtAscIdAsc(Long ambulanceId);

    List<ResourceTimelineEntry> findByCrewIdOrderByOccurredAtAscIdAsc(Long crewId);

    List<ResourceTimelineEntry> findByDispatchIdOrderByOccurredAtAscIdAsc(Long dispatchId);
}
