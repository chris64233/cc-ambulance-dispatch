package com.chris64233.ambulancedispatch.service;

import com.chris64233.ambulancedispatch.domain.Diversion;
import com.chris64233.ambulancedispatch.repository.DiversionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立事务中持久化改派失败记录：主业务事务随后回滚（资源不变），
 * 但失败记录保留，用于相同业务号重放时返回首次失败结果。
 */
@Component
public class DiversionFailureRecorder {

    private final DiversionRepository diversionRepository;

    public DiversionFailureRecorder(DiversionRepository diversionRepository) {
        this.diversionRepository = diversionRepository;
    }

    /**
     * 写入失败记录。若相同业务号的失败记录已被并发事务抢先提交（唯一约束冲突），
     * 忽略——首次结果已经落库，调用方仍按本次失败回滚并返回错误即可。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailed(Diversion diversion) {
        try {
            diversionRepository.save(diversion);
            diversionRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 并发下另一事务已写入同业务号首次失败记录，保留首次结果
        }
    }
}
