package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.Hospital;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HospitalRepository extends JpaRepository<Hospital, Long> {

    Optional<Hospital> findByName(String name);

    /**
     * 原子预留一个床位：仅当医院开放接收且仍有空余床位时成功。
     * 条件更新在数据库行锁下执行，并发争抢最后一个名额只有一个事务影响 1 行，
     * reservedBeds 永远不会超过 bedCapacity。
     *
     * @return 1 预留成功，0 表示医院已关闭或床位已满
     */
    @Modifying
    @Query("update Hospital h set h.reservedBeds = h.reservedBeds + 1 "
            + "where h.id = :id "
            + "and h.receivingStatus = com.chris64233.ambulancedispatch.domain.ReceivingStatus.OPEN "
            + "and h.reservedBeds < h.bedCapacity")
    int reserveBedIfOpen(@Param("id") Long id);

    /**
     * 释放一个床位预留：reservedBeds 大于 0 才更新，容量绝不为负。
     *
     * @return 1 释放成功，0 表示没有可释放的预留
     */
    @Modifying
    @Query("update Hospital h set h.reservedBeds = h.reservedBeds - 1 "
            + "where h.id = :id and h.reservedBeds > 0")
    int releaseBed(@Param("id") Long id);

    /** 行锁加载医院，串行化状态更新与预留/释放/改派。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hospital h where h.id = :id")
    Optional<Hospital> findWithLockById(@Param("id") Long id);
}
