package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.DiversionTask;
import com.chris64233.ambulancedispatch.domain.DiversionTaskStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiversionTaskRepository extends JpaRepository<DiversionTask, Long> {

    /** 派遣当前待处理的改派任务（一般最多一条）。 */
    Optional<DiversionTask> findFirstByDispatchIdAndStatusOrderByIdAsc(
            Long dispatchId, DiversionTaskStatus status);

    /** 派遣处于指定状态的全部任务。 */
    List<DiversionTask> findByDispatchIdAndStatus(Long dispatchId, DiversionTaskStatus status);

    List<DiversionTask> findByEventIdOrderByCreatedAtAscIdAsc(Long eventId);

    List<DiversionTask> findByStatusOrderByCreatedAtAscIdAsc(DiversionTaskStatus status);
}
