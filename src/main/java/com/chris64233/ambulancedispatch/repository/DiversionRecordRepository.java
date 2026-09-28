package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.DiversionRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiversionRecordRepository extends JpaRepository<DiversionRecord, Long> {

    Optional<DiversionRecord> findByBizNo(String bizNo);

    /** 事件的历次改派（成功与失败都包含），按申请时间升序。 */
    List<DiversionRecord> findByEventIdOrderByCreatedAtAscIdAsc(Long eventId);
}
