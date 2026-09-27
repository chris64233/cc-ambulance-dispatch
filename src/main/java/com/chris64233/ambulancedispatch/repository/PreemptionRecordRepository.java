package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.PreemptionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PreemptionRecordRepository extends JpaRepository<PreemptionRecord, Long> {

    /** 查询事件作为抢占方或被抢占方参与的全部抢占记录，按时间升序构成抢占链。 */
    List<PreemptionRecord> findByPreemptingIncidentIdOrPreemptedIncidentIdOrderByOccurredAtAscIdAsc(
            Long preemptingIncidentId, Long preemptedIncidentId);
}
