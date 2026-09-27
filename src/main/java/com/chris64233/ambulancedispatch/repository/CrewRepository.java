package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.Crew;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CrewRepository extends JpaRepository<Crew, Long> {

    /** 悲观写锁读取，用于派遣时原子占用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Crew c where c.id = :id")
    Optional<Crew> findByIdForUpdate(@Param("id") Long id);
}
