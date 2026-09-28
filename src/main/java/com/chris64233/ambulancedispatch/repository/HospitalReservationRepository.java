package com.chris64233.ambulancedispatch.repository;

import com.chris64233.ambulancedispatch.domain.HospitalReservation;
import com.chris64233.ambulancedispatch.domain.ReservationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HospitalReservationRepository extends JpaRepository<HospitalReservation, Long> {

    /** 派遣当前生效的预留（HELD）。 */
    Optional<HospitalReservation> findByDispatchIdAndStatus(Long dispatchId, ReservationStatus status);

    /** 事件当前生效的预留（HELD）。 */
    Optional<HospitalReservation> findByEventIdAndStatus(Long eventId, ReservationStatus status);

    List<HospitalReservation> findByEventIdOrderByReservedAtAscIdAsc(Long eventId);

    List<HospitalReservation> findByDispatchIdOrderByReservedAtAscIdAsc(Long dispatchId);
}
