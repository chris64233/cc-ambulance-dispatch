package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.HospitalRejection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HospitalRejectionRepository extends JpaRepository<HospitalRejection, Long> {

    List<HospitalRejection> findByEventIdOrderByRejectedAtAscIdAsc(Long eventId);
}
