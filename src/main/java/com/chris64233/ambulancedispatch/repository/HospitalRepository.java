package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.Hospital;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface HospitalRepository extends JpaRepository<Hospital, Long> {

    Optional<Hospital> findByName(String name);

    /**
     * 行锁加载医院。预留/释放名额与接收状态更新都在持锁事务内完成，串行化同一医院的并发操作，
     * 保证 reservedCount 永不为负、不超卖，且关闭后不会再收到新事件。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hospital h where h.id = :id")
    Optional<Hospital> findWithLockById(@Param("id") Long id);
}
