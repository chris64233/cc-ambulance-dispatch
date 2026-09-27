package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.ResourceEvent;
import com.chris64233.ambulancedispatch.domain.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResourceEventRepository extends JpaRepository<ResourceEvent, Long> {

    List<ResourceEvent> findByResourceTypeAndResourceIdOrderByOccurredAtAscIdAsc(
            ResourceType resourceType, Long resourceId);
}
