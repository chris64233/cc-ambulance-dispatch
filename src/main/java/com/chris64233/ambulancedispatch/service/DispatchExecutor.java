package com.chris64233.ambulancedispatch.service;

import com.chris64233.ambulancedispatch.api.error.ApiException;
import com.chris64233.ambulancedispatch.api.error.ErrorCode;
import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.repository.DispatchRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 派遣入口执行器：在 {@link DispatchService} 的事务边界之外处理
 * “并发提交相同业务号” 的兜底——数据库唯一约束拦截后重读，
 * 内容相同按幂等重放返回原结果，内容不同返回冲突。
 */
@Component
public class DispatchExecutor {

    private final DispatchService dispatchService;
    private final DispatchRepository dispatchRepository;

    public DispatchExecutor(DispatchService dispatchService, DispatchRepository dispatchRepository) {
        this.dispatchService = dispatchService;
        this.dispatchRepository = dispatchRepository;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DispatchOutcome execute(String requestId, Long incidentId, Long vehicleId, Long crewId) {
        try {
            return dispatchService.dispatch(requestId, incidentId, vehicleId, crewId);
        } catch (DataIntegrityViolationException | TransactionSystemException e) {
            // 并发下两个请求同时通过幂等检查，唯一约束只放行一个。
            // 胜出事务可能尚未提交，短暂重试等待其可见。
            Dispatch found = awaitVisible(requestId, e);
            if (found.getContentFingerprint().equals(fingerprint(incidentId, vehicleId, crewId))) {
                return new DispatchOutcome(found, true);
            }
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "派遣业务号已存在且内容不同: requestId=" + requestId);
        }
    }

    private Dispatch awaitVisible(String requestId, RuntimeException original) {
        for (int attempt = 0; attempt < 10; attempt++) {
            var found = dispatchRepository.findByRequestId(requestId);
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw original;
    }

    static String fingerprint(Long incidentId, Long vehicleId, Long crewId) {
        return incidentId + ":" + vehicleId + ":" + crewId;
    }
}
