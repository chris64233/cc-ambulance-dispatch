package com.chris64233.ambulancedispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chris64233.ambulancedispatch.TestFixtures;
import com.chris64233.ambulancedispatch.domain.Ambulance;
import com.chris64233.ambulancedispatch.domain.AmbulanceStatus;
import com.chris64233.ambulancedispatch.domain.AssignmentStatus;
import com.chris64233.ambulancedispatch.domain.Crew;
import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.domain.DispatchStatus;
import com.chris64233.ambulancedispatch.domain.DiversionStatus;
import com.chris64233.ambulancedispatch.domain.EmergencyEvent;
import com.chris64233.ambulancedispatch.domain.EventStatus;
import com.chris64233.ambulancedispatch.domain.Hospital;
import com.chris64233.ambulancedispatch.domain.Priority;
import com.chris64233.ambulancedispatch.domain.ReceivingStatus;
import com.chris64233.ambulancedispatch.dto.DivertRequest;
import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.dto.DispatchResponse;
import com.chris64233.ambulancedispatch.dto.DiversionResponse;
import com.chris64233.ambulancedispatch.dto.EventDispatchDetailResponse;
import com.chris64233.ambulancedispatch.dto.PreemptRequest;
import com.chris64233.ambulancedispatch.dto.PreemptionChainItemResponse;
import com.chris64233.ambulancedispatch.dto.RejectRequest;
import com.chris64233.ambulancedispatch.dto.TimelineEntryResponse;
import com.chris64233.ambulancedispatch.exception.BusinessException;
import com.chris64233.ambulancedispatch.exception.ErrorCode;
import com.chris64233.ambulancedispatch.repository.AmbulanceRepository;
import com.chris64233.ambulancedispatch.repository.CrewRepository;
import com.chris64233.ambulancedispatch.repository.DispatchRepository;
import com.chris64233.ambulancedispatch.repository.DiversionRepository;
import com.chris64233.ambulancedispatch.repository.EmergencyEventRepository;
import com.chris64233.ambulancedispatch.repository.HospitalRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DispatchServiceIntegrationTest {

    private static final String AREA = "东城区";
    private static final Set<String> CAPS = Set.of("AED", "VENTILATOR");
    private static final Set<String> TYPES = Set.of("GENERAL", "TRAUMA");

    @Autowired private DispatchService dispatchService;
    @Autowired private CatalogService catalogService;
    @Autowired private HospitalService hospitalService;
    @Autowired private TestFixtures fixtures;
    @Autowired private AmbulanceRepository ambulanceRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private EmergencyEventRepository eventRepository;
    @Autowired private DispatchRepository dispatchRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DiversionRepository diversionRepository;

    private String bizNo() {
        return "BIZ-" + UUID.randomUUID();
    }

    private DispatchResponse dispatch(Long amb, Long crew, Long event, Long hospital) {
        return dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew, hospital));
    }

    // ------------------------------------------------------------------
    // 联合派遣
    // ------------------------------------------------------------------

    @Test
    void dispatch_occupies_ambulance_crew_and_hospital_bed_atomically() {
        Long amb = fixtures.ambulance("京A1", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("一组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long hospital = fixtures.hospital("一院", TYPES, 5);

        DispatchResponse resp = dispatch(amb, crew, event, hospital);

        assertThat(resp.status()).isEqualTo(DispatchStatus.EN_ROUTE.name());
        assertThat(resp.hospitalId()).isEqualTo(hospital);
        assertThat(resp.preemptionRootId()).isEqualTo(resp.id());
        assertThat(ambulanceRepository.findById(amb).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.DISPATCHED);
        assertThat(crewRepository.findById(crew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.ASSIGNED);
        Hospital h = hospitalRepository.findById(hospital).orElseThrow();
        assertThat(h.getReservedBeds()).isEqualTo(1);
        assertThat(eventRepository.findById(event).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DISPATCHED);
    }

    @Test
    void dispatch_rejects_service_area_mismatch() {
        Long amb = fixtures.ambulance("京A2", Set.of("西城区"), CAPS);
        Long crew = fixtures.crew("二组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long hospital = fixtures.hospital("一院", TYPES, 5);

        assertThatThrownBy(() -> dispatch(amb, crew, event, hospital))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_AREA_MISMATCH);

        // 校验失败不得占用任何资源（含医院床位）
        assertThat(ambulanceRepository.findById(amb).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.AVAILABLE);
        assertThat(crewRepository.findById(crew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.IDLE);
        assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds()).isZero();
    }

    @Test
    void dispatch_rejects_capability_mismatch() {
        Long amb = fixtures.ambulance("京A3", Set.of(AREA), Set.of("AED"));
        Long crew = fixtures.crew("三组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long hospital = fixtures.hospital("一院", TYPES, 5);

        assertThatThrownBy(() -> dispatch(amb, crew, event, hospital))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CAPABILITY_MISMATCH);
    }

    @Test
    void dispatch_rejects_off_duty_crew() {
        Long amb = fixtures.ambulance("京A4", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("四组", CAPS, false);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long hospital = fixtures.hospital("一院", TYPES, 5);

        assertThatThrownBy(() -> dispatch(amb, crew, event, hospital))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CREW_OFF_DUTY);
    }

    // ------------------------------------------------------------------
    // 医院名额：关闭 / 容量 / 急救类型 / 原子性
    // ------------------------------------------------------------------

    @Test
    void dispatch_rejects_closed_hospital_and_reserves_nothing() {
        Long amb = fixtures.ambulance("京A30", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("三十组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long hospital = fixtures.hospital("闭院", TYPES, 5, "CLOSED");

        assertThatThrownBy(() -> dispatch(amb, crew, event, hospital))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOSPITAL_CLOSED);

        assertThat(ambulanceRepository.findById(amb).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.AVAILABLE);
        assertThat(crewRepository.findById(crew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.IDLE);
        assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds()).isZero();
        assertThat(eventRepository.findById(event).orElseThrow().getStatus())
                .isEqualTo(EventStatus.PENDING);
    }

    @Test
    void dispatch_rejects_full_hospital_without_partial_dispatch() {
        Long amb = fixtures.ambulance("京A31", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("三一组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long hospital = fixtures.hospital("满院", TYPES, 1);

        // 占掉唯一床位
        Long amb0 = fixtures.ambulance("京A310", Set.of(AREA), CAPS);
        Long crew0 = fixtures.crew("三一零组", CAPS, true);
        Long event0 = fixtures.event("西单", AREA, Priority.NORMAL, CAPS);
        dispatch(amb0, crew0, event0, hospital);
        assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds()).isEqualTo(1);

        assertThatThrownBy(() -> dispatch(amb, crew, event, hospital))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOSPITAL_NO_CAPACITY);

        // 床位不超额、不为负；车组未被部分占用
        assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds()).isEqualTo(1);
        assertThat(ambulanceRepository.findById(amb).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.AVAILABLE);
        assertThat(crewRepository.findById(crew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.IDLE);
    }

    @Test
    void dispatch_rejects_unaccepted_emergency_type() {
        Long amb = fixtures.ambulance("京A32", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("三二组", CAPS, true);
        Long event = fixtures.event("东单", AREA, "CARDIAC", Priority.NORMAL, CAPS);
        Long hospital = fixtures.hospital("创伤院", Set.of("TRAUMA"), 5);

        assertThatThrownBy(() -> dispatch(amb, crew, event, hospital))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOSPITAL_TYPE_NOT_ACCEPTED);
        assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds()).isZero();
    }

    @Test
    void closing_hospital_concurrent_with_dispatch_admits_no_new_event() throws Exception {
        // 医院只有 1 个床位；一个线程派遣、一个线程关闭接收
        Long amb = fixtures.ambulance("京A33", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("三三组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long hospital = fixtures.hospital("待闭院", TYPES, 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> dispatchF = pool.submit(() -> {
                try {
                    start.await();
                    dispatch(amb, crew, event, hospital);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            Future<?> closeF = pool.submit(() -> {
                try {
                    start.await();
                    fixtures.updateHospital(hospital, null, null, "CLOSED");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            start.countDown();

            boolean dispatched = false;
            for (Future<?> f : List.of(dispatchF, closeF)) {
                try {
                    f.get();
                } catch (Exception e) {
                    // 派遣失败是预期结果之一
                }
            }
            if (eventRepository.findById(event).orElseThrow().getStatus()
                    == EventStatus.DISPATCHED) {
                dispatched = true;
            }

            Hospital h = hospitalRepository.findById(hospital).orElseThrow();
            if (dispatched) {
                // 派遣先抢到名额：保留 1 个床位，医院随后关闭
                assertThat(h.getReservedBeds()).isEqualTo(1);
                assertThat(h.getReceivingStatus()).isEqualTo(ReceivingStatus.CLOSED);
            } else {
                // 关闭先生效：关闭医院没有收到新事件，床位为空
                assertThat(h.getReservedBeds()).isZero();
                assertThat(eventRepository.findById(event).orElseThrow().getStatus())
                        .isEqualTo(EventStatus.PENDING);
            }
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // 并发争抢
    // ------------------------------------------------------------------

    @Test
    void concurrent_dispatch_same_resources_only_one_succeeds() throws Exception {
        Long amb = fixtures.ambulance("京A5", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("五组", CAPS, true);
        Long hospital = fixtures.hospital("五院", TYPES, 5);
        Long event1 = fixtures.event("东单1", AREA, Priority.NORMAL, CAPS);
        Long event2 = fixtures.event("东单2", AREA, Priority.NORMAL, CAPS);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> f1 = pool.submit(() -> {
                try {
                    start.await();
                    dispatch(amb, crew, event1, hospital);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            Future<?> f2 = pool.submit(() -> {
                try {
                    start.await();
                    dispatch(amb, crew, event2, hospital);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            start.countDown();

            int success = 0;
            int conflict = 0;
            for (Future<?> f : List.of(f1, f2)) {
                try {
                    f.get();
                    success++;
                } catch (Exception e) {
                    conflict++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(conflict).isEqualTo(1);
        } finally {
            pool.shutdown();
        }

        Ambulance a = ambulanceRepository.findById(amb).orElseThrow();
        Crew c = crewRepository.findById(crew).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(AmbulanceStatus.DISPATCHED);
        assertThat(c.getAssignmentStatus()).isEqualTo(AssignmentStatus.ASSIGNED);
        assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds()).isEqualTo(1);

        // 车和人必须属于同一条派遣，不能分别被两个事件占用
        List<com.chris64233.ambulancedispatch.domain.Dispatch> byAmbulance =
                dispatchRepository.findActiveByAmbulance(amb,
                        List.of(DispatchStatus.EN_ROUTE, DispatchStatus.ON_SCENE));
        List<com.chris64233.ambulancedispatch.domain.Dispatch> byCrew =
                dispatchRepository.findActiveByCrew(crew,
                        List.of(DispatchStatus.EN_ROUTE, DispatchStatus.ON_SCENE));
        assertThat(byAmbulance).hasSize(1);
        assertThat(byCrew).hasSize(1);
        assertThat(byAmbulance.get(0).getId()).isEqualTo(byCrew.get(0).getId());
    }

    @Test
    void concurrent_dispatch_contending_last_bed_never_overshoots_capacity() throws Exception {
        int capacity = 3;
        int threads = 8;
        Long hospital = fixtures.hospital("抢位院", TYPES, capacity);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        Long amb = fixtures.ambulance("京C" + idx, Set.of(AREA), CAPS);
                        Long crew = fixtures.crew("C" + idx + "组", CAPS, true);
                        Long event = fixtures.event("现场" + idx, AREA, Priority.NORMAL, CAPS);
                        dispatch(amb, crew, event, hospital);
                        success.incrementAndGet();
                    } catch (BusinessException e) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(success.get()).isEqualTo(capacity);
        assertThat(rejected.get()).isEqualTo(threads - capacity);
        Hospital h = hospitalRepository.findById(hospital).orElseThrow();
        assertThat(h.getReservedBeds()).isEqualTo(capacity);
        assertThat(h.getReservedBeds()).isLessThanOrEqualTo(h.getBedCapacity());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void idempotent_retry_returns_original_result() {
        Long amb = fixtures.ambulance("京A6", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("六组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long hospital = fixtures.hospital("六院", TYPES, 5);
        String bizNo = bizNo();

        DispatchResponse first = dispatchService.dispatch(
                new DispatchRequest(bizNo, event, amb, crew, hospital));
        DispatchResponse replay = dispatchService.dispatch(
                new DispatchRequest(bizNo, event, amb, crew, hospital));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.replayed()).isTrue();
        assertThat(dispatchRepository.findByEventIdOrderByDispatchedAtDesc(event)).hasSize(1);
        // 重放不重复预留床位
        assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds()).isEqualTo(1);
    }

    @Test
    void same_biz_no_different_content_returns_conflict() {
        Long amb = fixtures.ambulance("京A7", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("七组", CAPS, true);
        Long hospital = fixtures.hospital("七院", TYPES, 5);
        Long event1 = fixtures.event("东单1", AREA, Priority.NORMAL, CAPS);
        Long event2 = fixtures.event("东单2", AREA, Priority.NORMAL, CAPS);
        String bizNo = bizNo();

        dispatchService.dispatch(new DispatchRequest(bizNo, event1, amb, crew, hospital));

        assertThatThrownBy(() -> dispatchService.dispatch(
                new DispatchRequest(bizNo, event2, amb, crew, hospital)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_CONFLICT);
    }

    // ------------------------------------------------------------------
    // 抢占
    // ------------------------------------------------------------------

    @Test
    void high_priority_event_preempts_en_route_dispatch() {
        Long amb = fixtures.ambulance("京A8", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("八组", CAPS, true);
        Long lowHospital = fixtures.hospital("八院", TYPES, 5);
        Long highHospital = fixtures.hospital("急救中心", TYPES, 5);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);

        DispatchResponse low = dispatch(amb, crew, lowEvent, lowHospital);
        DispatchResponse high = dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew, highHospital));

        assertThat(high.status()).isEqualTo(DispatchStatus.EN_ROUTE.name());
        assertThat(high.hospitalId()).isEqualTo(highHospital);
        assertThat(high.preemptedDispatchId()).isEqualTo(low.id());
        assertThat(high.preemptionRootId()).isEqualTo(low.id());

        // 原事件恢复为待派遣；原单 PREEMPTED；新事件已派遣
        assertThat(eventRepository.findById(lowEvent).orElseThrow().getStatus())
                .isEqualTo(EventStatus.PENDING);
        assertThat(eventRepository.findById(highEvent).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DISPATCHED);
        assertThat(dispatchRepository.findById(low.id()).orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.PREEMPTED);
        // 原医院名额已释放、新医院名额已预留
        assertThat(hospitalRepository.findById(lowHospital).orElseThrow().getReservedBeds()).isZero();
        assertThat(hospitalRepository.findById(highHospital).orElseThrow().getReservedBeds()).isEqualTo(1);
        // 资源仍占用，但已属新单
        assertThat(dispatchRepository.findActiveByAmbulance(amb,
                List.of(DispatchStatus.EN_ROUTE, DispatchStatus.ON_SCENE)))
                .singleElement()
                .satisfies(d -> assertThat(d.getId()).isEqualTo(high.id()));
    }

    @Test
    void arrived_dispatch_cannot_be_preempted() {
        Long amb = fixtures.ambulance("京A9", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("九组", CAPS, true);
        Long hospital = fixtures.hospital("九院", TYPES, 5);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);

        DispatchResponse low = dispatch(amb, crew, lowEvent, hospital);
        dispatchService.arrive(low.id());

        assertThatThrownBy(() -> dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew, hospital)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DISPATCH_ALREADY_ARRIVED);

        // 抢占被拒：原派遣、资源与医院名额完全不变
        assertThat(dispatchRepository.findById(low.id()).orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.ON_SCENE);
        assertThat(eventRepository.findById(lowEvent).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DISPATCHED);
        assertThat(eventRepository.findById(highEvent).orElseThrow().getStatus())
                .isEqualTo(EventStatus.PENDING);
        assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds()).isEqualTo(1);
    }

    @Test
    void equal_or_lower_priority_cannot_preempt() {
        Long amb = fixtures.ambulance("京A10", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十组", CAPS, true);
        Long hospital = fixtures.hospital("十院", TYPES, 5);
        Long normalEvent = fixtures.event("东单常", AREA, Priority.NORMAL, CAPS);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);

        DispatchResponse normal = dispatch(amb, crew, normalEvent, hospital);

        assertThatThrownBy(() -> dispatchService.preempt(
                new PreemptRequest(bizNo(), lowEvent, normal.id(), amb, crew, hospital)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PREEMPTION_NOT_HIGHER_PRIORITY);
    }

    @Test
    void failed_preemption_keeps_original_dispatch_unchanged() {
        // 新事件要求 VENTILATOR，但车辆不具备 -> 抢占安全失败，原派遣与原医院名额不动
        Long amb = fixtures.ambulance("京A11", Set.of(AREA), Set.of("AED"));
        Long crew = fixtures.crew("十一组", CAPS, true);
        Long hospital = fixtures.hospital("十一院", TYPES, 5);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, Set.of("AED"));
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);

        DispatchResponse low = dispatch(amb, crew, lowEvent, hospital);

        assertThatThrownBy(() -> dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew, hospital)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CAPABILITY_MISMATCH);

        assertThat(dispatchRepository.findById(low.id()).orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.EN_ROUTE);
        assertThat(eventRepository.findById(lowEvent).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DISPATCHED);
        assertThat(eventRepository.findById(highEvent).orElseThrow().getStatus())
                .isEqualTo(EventStatus.PENDING);
        assertThat(ambulanceRepository.findById(amb).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.DISPATCHED);
        assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds()).isEqualTo(1);
    }

    @Test
    void preempt_is_idempotent_and_detects_conflict() {
        Long amb = fixtures.ambulance("京A12", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十二组", CAPS, true);
        Long hospital = fixtures.hospital("十二院", TYPES, 5);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);
        DispatchResponse low = dispatch(amb, crew, lowEvent, hospital);

        String bizNo = bizNo();
        DispatchResponse first = dispatchService.preempt(
                new PreemptRequest(bizNo, highEvent, low.id(), amb, crew, hospital));
        DispatchResponse replay = dispatchService.preempt(
                new PreemptRequest(bizNo, highEvent, low.id(), amb, crew, hospital));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.replayed()).isTrue();

        Long otherEvent = fixtures.event("另一急", AREA, Priority.HIGH, CAPS);
        assertThatThrownBy(() -> dispatchService.preempt(
                new PreemptRequest(bizNo, otherEvent, low.id(), amb, crew, hospital)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_CONFLICT);
    }

    // ------------------------------------------------------------------
    // 途中改派
    // ------------------------------------------------------------------

    @Test
    void divert_after_arrival_switches_hospital_and_keeps_resources() {
        Long amb = fixtures.ambulance("京A40", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("四十组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("原院", TYPES, 5);
        Long h2 = fixtures.hospital("新院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());

        DiversionResponse div = dispatchService.divert(new DivertRequest(
                bizNo(), d.id(), h1, h2, "HOSPITAL_CLOSED", "原院突然关闭接收"));

        assertThat(div.status()).isEqualTo(DiversionStatus.SUCCESS.name());
        assertThat(div.toHospitalId()).isEqualTo(h2);
        // 新名额预留成功后才释放原名额
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds()).isZero();
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedBeds()).isEqualTo(1);
        // 派遣目的地切换，车组保持占用
        Dispatch reloaded = dispatchRepository.findDetailedById(d.id()).orElseThrow();
        assertThat(reloaded.getHospital().getId()).isEqualTo(h2);
        assertThat(reloaded.getStatus()).isEqualTo(DispatchStatus.ON_SCENE);
        assertThat(ambulanceRepository.findById(amb).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.DISPATCHED);
        assertThat(crewRepository.findById(crew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.ASSIGNED);
    }

    @Test
    void divert_before_arrival_rejected() {
        Long amb = fixtures.ambulance("京A41", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("四一组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("原院", TYPES, 5);
        Long h2 = fixtures.hospital("新院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);

        assertThatThrownBy(() -> dispatchService.divert(
                new DivertRequest(bizNo(), d.id(), h1, h2, "CONDITION_CHANGED", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DIVERSION_NOT_ARRIVED);

        assertThat(dispatchRepository.findDetailedById(d.id()).orElseThrow().getHospital().getId())
                .isEqualTo(h1);
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds()).isEqualTo(1);
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedBeds()).isZero();
    }

    @Test
    void divert_after_hospital_arrival_rejected() {
        Long amb = fixtures.ambulance("京A42", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("四二组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("原院", TYPES, 5);
        Long h2 = fixtures.hospital("新院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());
        dispatchService.arriveAtHospital(d.id());

        assertThatThrownBy(() -> dispatchService.divert(
                new DivertRequest(bizNo(), d.id(), h1, h2, "CONDITION_CHANGED", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DIVERSION_ALREADY_AT_HOSPITAL);
    }

    @Test
    void divert_failure_keeps_original_destination_and_replays_first_failure() {
        Long amb = fixtures.ambulance("京A43", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("四三组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("原院", TYPES, 5);
        Long h2 = fixtures.hospital("满的新院", TYPES, 1);
        // 占满 h2 唯一床位
        Long amb0 = fixtures.ambulance("京A430", Set.of(AREA), CAPS);
        Long crew0 = fixtures.crew("四三零组", CAPS, true);
        Long event0 = fixtures.event("别处", AREA, Priority.NORMAL, CAPS);
        DispatchResponse d0 = dispatch(amb0, crew0, event0, h2);
        dispatchService.arrive(d0.id());

        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());
        String bizNo = bizNo();

        // 首次改派失败：新院满床
        assertThatThrownBy(() -> dispatchService.divert(
                new DivertRequest(bizNo, d.id(), h1, h2, "CONDITION_CHANGED", "病情恶化")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOSPITAL_NO_CAPACITY);

        // 原目的地、原名额、派遣均不变
        assertThat(dispatchRepository.findDetailedById(d.id()).orElseThrow().getHospital().getId())
                .isEqualTo(h1);
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds()).isEqualTo(1);
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedBeds()).isEqualTo(1);

        // 同业务号同内容重放：返回首次失败结果（FAILED 记录已持久化）
        assertThatThrownBy(() -> dispatchService.divert(
                new DivertRequest(bizNo, d.id(), h1, h2, "CONDITION_CHANGED", "病情恶化")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOSPITAL_NO_CAPACITY);
        assertThat(diversionRepository.findByBizNo(bizNo).orElseThrow().getStatus())
                .isEqualTo(DiversionStatus.FAILED);
        // 没有产生第二次预留尝试导致的脏数据
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds()).isEqualTo(1);
    }

    @Test
    void divert_to_closed_hospital_fails_and_keeps_original() {
        Long amb = fixtures.ambulance("京A44", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("四四组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("原院", TYPES, 5);
        Long h2 = fixtures.hospital("关闭新院", TYPES, 5, "CLOSED");
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());

        assertThatThrownBy(() -> dispatchService.divert(
                new DivertRequest(bizNo(), d.id(), h1, h2, "HOSPITAL_CLOSED", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOSPITAL_CLOSED);

        assertThat(dispatchRepository.findDetailedById(d.id()).orElseThrow().getHospital().getId())
                .isEqualTo(h1);
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds()).isEqualTo(1);
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedBeds()).isZero();
    }

    @Test
    void stale_divert_cannot_override_later_confirmed_destination() {
        Long amb = fixtures.ambulance("京A45", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("四五组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("一院", TYPES, 5);
        Long h2 = fixtures.hospital("二院", TYPES, 5);
        Long h3 = fixtures.hospital("三院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());

        // 第一次改派成功：h1 -> h2
        dispatchService.divert(new DivertRequest(bizNo(), d.id(), h1, h2,
                "HOSPITAL_CLOSED", "h1 关闭"));
        assertThat(dispatchRepository.findDetailedById(d.id()).orElseThrow().getHospital().getId())
                .isEqualTo(h2);

        // 旧请求仍声称从 h1 出发 -> 拒绝，不能覆盖后来确认的 h2
        assertThatThrownBy(() -> dispatchService.divert(
                new DivertRequest(bizNo(), d.id(), h1, h3, "CONDITION_CHANGED", "旧请求")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DIVERSION_DESTINATION_MISMATCH);

        // 以当前目的地 h2 为起点的新改派正常
        DiversionResponse again = dispatchService.divert(
                new DivertRequest(bizNo(), d.id(), h2, h3, "CONDITION_CHANGED", "需要专科"));
        assertThat(again.status()).isEqualTo(DiversionStatus.SUCCESS.name());
        assertThat(dispatchRepository.findDetailedById(d.id()).orElseThrow().getHospital().getId())
                .isEqualTo(h3);
        // 名额在 h3，h1/h2 均已释放
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds()).isZero();
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedBeds()).isZero();
        assertThat(hospitalRepository.findById(h3).orElseThrow().getReservedBeds()).isEqualTo(1);
    }

    @Test
    void divert_same_biz_no_different_content_conflicts() {
        Long amb = fixtures.ambulance("京A46", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("四六组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("一院", TYPES, 5);
        Long h2 = fixtures.hospital("二院", TYPES, 5);
        Long h3 = fixtures.hospital("三院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());
        String bizNo = bizNo();

        dispatchService.divert(new DivertRequest(bizNo, d.id(), h1, h2,
                "HOSPITAL_CLOSED", null));
        assertThatThrownBy(() -> dispatchService.divert(
                new DivertRequest(bizNo, d.id(), h1, h3, "HOSPITAL_CLOSED", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_CONFLICT);
    }

    @Test
    void divert_idempotent_retry_returns_first_result() {
        Long amb = fixtures.ambulance("京A47", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("四七组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("一院", TYPES, 5);
        Long h2 = fixtures.hospital("二院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());
        String bizNo = bizNo();

        DivertRequest req = new DivertRequest(bizNo, d.id(), h1, h2,
                "CONDITION_CHANGED", "病情变化");
        DiversionResponse first = dispatchService.divert(req);
        DiversionResponse replay = dispatchService.divert(req);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.replayed()).isTrue();
        // 重放不重复操作名额
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds()).isZero();
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedBeds()).isEqualTo(1);
    }

    @Test
    void concurrent_divert_and_hospital_arrival_only_one_takes_effect() throws Exception {
        Long amb = fixtures.ambulance("京A48", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("四八组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("一院", TYPES, 5);
        Long h2 = fixtures.hospital("二院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> divertF = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.divert(new DivertRequest(bizNo(), d.id(), h1, h2,
                            "CONDITION_CHANGED", null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            Future<?> arriveHF = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.arriveAtHospital(d.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            start.countDown();

            int success = 0;
            int rejected = 0;
            for (Future<?> f : List.of(divertF, arriveHF)) {
                try {
                    f.get();
                    success++;
                } catch (Exception e) {
                    rejected++;
                }
            }
            // 到院先提交则改派必被拒（恰一个成功）；改派先提交时，到院记录落在新医院 h2，
            // 属于“先改派、再到新院”的合法顺序（两个都成功）。两种顺序都必须满足一致性。
            assertThat(success + rejected).isEqualTo(2);
        } finally {
            pool.shutdown();
        }

        Dispatch reloaded = dispatchRepository.findDetailedById(d.id()).orElseThrow();
        Long currentHospital = reloaded.getHospital().getId();
        if (reloaded.getArrivedAtHospitalAt() != null) {
            // 已到院：到院记录必须落在当前目的医院，且该医院恰好持有名额
            if (currentHospital.equals(h1)) {
                // 到院先生效：改派必被拒
                assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds())
                        .isEqualTo(1);
                assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedBeds()).isZero();
            } else {
                // 改派先、到院后：目的地 h2，到院记录属于 h2
                assertThat(currentHospital).isEqualTo(h2);
                assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds()).isZero();
                assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedBeds())
                        .isEqualTo(1);
            }
        } else {
            // 改派成功但尚未到院
            assertThat(currentHospital).isEqualTo(h2);
            assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds()).isZero();
            assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedBeds()).isEqualTo(1);
        }
        // 到院后不可再改派
        if (reloaded.getArrivedAtHospitalAt() != null) {
            assertThatThrownBy(() -> dispatchService.divert(new DivertRequest(
                    bizNo(), d.id(), currentHospital, h1, "CONDITION_CHANGED", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DIVERSION_ALREADY_AT_HOSPITAL);
        }
        // 容量恒等式：两院合计恰好 1 个预留，绝不为负
        int totalReserved = List.of(h1, h2).stream()
                .mapToInt(hid -> hospitalRepository.findById(hid).orElseThrow().getReservedBeds())
                .sum();
        assertThat(totalReserved).isEqualTo(1);
        for (Long hid : List.of(h1, h2)) {
            Hospital h = hospitalRepository.findById(hid).orElseThrow();
            assertThat(h.getReservedBeds()).isBetween(0, h.getBedCapacity());
        }
    }

    // ------------------------------------------------------------------
    // 医院拒收
    // ------------------------------------------------------------------

    @Test
    void rejection_records_reason_creates_task_and_keeps_resources() {
        Long amb = fixtures.ambulance("京A50", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("五十组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("拒收院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());

        DiversionResponse rejection = dispatchService.reject(new RejectRequest(
                bizNo(), d.id(), h1, "无相应专科能力"));

        assertThat(rejection.status()).isEqualTo(DiversionStatus.PENDING.name());
        assertThat(rejection.reason()).isEqualTo("HOSPITAL_REJECTED");
        assertThat(rejection.reasonDetail()).isEqualTo("无相应专科能力");
        assertThat(rejection.toHospitalId()).isNull();

        // 拒收不得自行释放车辆、救护组，原医院名额仍保留
        assertThat(ambulanceRepository.findById(amb).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.DISPATCHED);
        assertThat(crewRepository.findById(crew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.ASSIGNED);
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds()).isEqualTo(1);
        assertThat(dispatchRepository.findDetailedById(d.id()).orElseThrow().getHospital().getId())
                .isEqualTo(h1);
        // 时间线记录拒收原因
        assertThat(dispatchService.getHospitalTimeline(h1))
                .extracting(TimelineEntryResponse::action)
                .contains("REJECTED");
    }

    @Test
    void successful_diversion_resolves_pending_rejection() {
        Long amb = fixtures.ambulance("京A51", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("五一组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("拒收院", TYPES, 5);
        Long h2 = fixtures.hospital("接收院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());

        DiversionResponse rejection = dispatchService.reject(
                new RejectRequest(bizNo(), d.id(), h1, "床位紧张"));

        DiversionResponse divert = dispatchService.divert(new DivertRequest(
                bizNo(), d.id(), h1, h2, "CONDITION_CHANGED", "按拒收任务改派"));
        assertThat(divert.status()).isEqualTo(DiversionStatus.SUCCESS.name());

        // 拒收任务被标记为 RESOLVED 并关联到成功改派，原名额释放、新名额预留
        var saved = diversionRepository.findById(rejection.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(DiversionStatus.RESOLVED);
        assertThat(saved.getResolvedRejection().getId()).isEqualTo(divert.id());
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedBeds()).isZero();
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedBeds()).isEqualTo(1);
    }

    @Test
    void rejection_idempotent_and_conflict() {
        Long amb = fixtures.ambulance("京A52", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("五二组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("拒收院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());
        String bizNo = bizNo();

        RejectRequest req = new RejectRequest(bizNo, d.id(), h1, "设备故障");
        DiversionResponse first = dispatchService.reject(req);
        DiversionResponse replay = dispatchService.reject(req);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.replayed()).isTrue();

        assertThatThrownBy(() -> dispatchService.reject(
                new RejectRequest(bizNo, d.id(), h1, "不同原因")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_CONFLICT);
        // 只有一条拒收记录
        assertThat(diversionRepository.findByDispatchIdOrderByCreatedAtAscIdAsc(d.id()))
                .hasSize(1);
    }

    @Test
    void rejection_from_non_current_hospital_rejected() {
        Long amb = fixtures.ambulance("京A53", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("五三组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("一院", TYPES, 5);
        Long h2 = fixtures.hospital("二院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());
        dispatchService.divert(new DivertRequest(bizNo(), d.id(), h1, h2,
                "HOSPITAL_CLOSED", null));

        // h1 已不是当前目的地，其迟到的拒收不能生效
        assertThatThrownBy(() -> dispatchService.reject(
                new RejectRequest(bizNo(), d.id(), h1, "迟来的拒收")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REJECTION_DESTINATION_MISMATCH);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Test
    void preempt_vs_arrive_race_only_one_takes_effect() throws Exception {
        Long amb = fixtures.ambulance("京A22", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("二二组", CAPS, true);
        Long hospital = fixtures.hospital("二二院", TYPES, 5);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);
        DispatchResponse low = dispatch(amb, crew, lowEvent, hospital);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> preemptF = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.preempt(new PreemptRequest(
                            bizNo(), highEvent, low.id(), amb, crew, hospital));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            Future<?> arriveF = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.arrive(low.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            start.countDown();

            int success = 0;
            int rejected = 0;
            for (Future<?> f : List.of(preemptF, arriveF)) {
                try {
                    f.get();
                    success++;
                } catch (Exception e) {
                    rejected++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(rejected).isEqualTo(1);
        } finally {
            pool.shutdown();
        }

        // 终态一致性：若到达先生效，抢占必然被拒；反之亦然
        DispatchStatus lowStatus = dispatchRepository.findById(low.id()).orElseThrow().getStatus();
        EventStatus lowEventStatus = eventRepository.findById(lowEvent).orElseThrow().getStatus();
        EventStatus highEventStatus = eventRepository.findById(highEvent).orElseThrow().getStatus();
        if (lowStatus == DispatchStatus.ON_SCENE) {
            assertThat(lowEventStatus).isEqualTo(EventStatus.DISPATCHED);
            assertThat(highEventStatus).isEqualTo(EventStatus.PENDING);
            assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds())
                    .isEqualTo(1);
        } else {
            assertThat(lowStatus).isEqualTo(DispatchStatus.PREEMPTED);
            assertThat(lowEventStatus).isEqualTo(EventStatus.PENDING);
            assertThat(highEventStatus).isEqualTo(EventStatus.DISPATCHED);
        }
    }

    @Test
    void complete_releases_resources_bed_and_event_becomes_terminal() {
        Long amb = fixtures.ambulance("京A13", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十三组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long hospital = fixtures.hospital("十三院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, hospital);

        dispatchService.arrive(d.id());
        DispatchResponse finished = dispatchService.complete(d.id());

        assertThat(finished.status()).isEqualTo(DispatchStatus.COMPLETED.name());
        assertThat(ambulanceRepository.findById(amb).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.AVAILABLE);
        assertThat(crewRepository.findById(crew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.IDLE);
        assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds()).isZero();
        EmergencyEvent e = eventRepository.findById(event).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(EventStatus.COMPLETED);

        // 终态不可修改：再次完成/取消/改派均被拒
        assertThatThrownBy(() -> dispatchService.complete(d.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DISPATCH_TERMINAL);
        assertThatThrownBy(() -> dispatchService.cancel(d.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DISPATCH_TERMINAL);
    }

    @Test
    void cancel_releases_resources_and_blocks_redispatch_of_terminal_event() {
        Long amb = fixtures.ambulance("京A14", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十四组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long hospital = fixtures.hospital("十四院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, hospital);

        DispatchResponse cancelled = dispatchService.cancel(d.id());
        assertThat(cancelled.status()).isEqualTo(DispatchStatus.CANCELLED.name());
        assertThat(eventRepository.findById(event).orElseThrow().getStatus())
                .isEqualTo(EventStatus.CANCELLED);
        assertThat(hospitalRepository.findById(hospital).orElseThrow().getReservedBeds()).isZero();

        // 对已取消事件再次派遣被拒
        Long amb2 = fixtures.ambulance("京A15", Set.of(AREA), CAPS);
        Long crew2 = fixtures.crew("十五组", CAPS, true);
        assertThatThrownBy(() -> dispatch(amb2, crew2, event, hospital))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_TERMINAL);
    }

    @Test
    void preempted_event_can_be_redispatched_after_release() {
        Long amb = fixtures.ambulance("京A16", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十六组", CAPS, true);
        Long hospital = fixtures.hospital("十六院", TYPES, 5);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);
        DispatchResponse low = dispatch(amb, crew, lowEvent, hospital);
        DispatchResponse high = dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew, hospital));

        // 高优先级完成释放资源后，原待派遣事件可重新派遣
        dispatchService.arrive(high.id());
        dispatchService.complete(high.id());

        DispatchResponse redispatch = dispatch(amb, crew, lowEvent, hospital);
        assertThat(redispatch.status()).isEqualTo(DispatchStatus.EN_ROUTE.name());
        // 重新派遣是一条全新的派遣单，自身成为新抢占链根；旧链仍可从被抢占单查询
        assertThat(redispatch.preemptionRootId()).isEqualTo(redispatch.id());
        assertThat(dispatchRepository.findById(low.id()).orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.PREEMPTED);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Test
    void event_dispatch_detail_shows_current_hospital_diversions_and_timeline() {
        Long amb = fixtures.ambulance("京A17", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十七组", CAPS, true);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);
        Long h1 = fixtures.hospital("低院", TYPES, 5);
        Long h2 = fixtures.hospital("高院", TYPES, 5);
        DispatchResponse low = dispatch(amb, crew, lowEvent, h1);
        DispatchResponse high = dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew, h2));
        dispatchService.arrive(high.id());
        // 高院拒收，改往第三个医院
        Long h3 = fixtures.hospital("终院", TYPES, 5);
        dispatchService.reject(new RejectRequest(bizNo(), high.id(), h2, "无床位"));
        dispatchService.divert(new DivertRequest(bizNo(), high.id(), h2, h3,
                "HOSPITAL_CLOSED", "高院停收"));

        EventDispatchDetailResponse detail = dispatchService.getEventDispatchDetail(highEvent);
        assertThat(detail.event().id()).isEqualTo(highEvent);
        assertThat(detail.currentDispatch().id()).isEqualTo(high.id());
        assertThat(detail.currentHospital().id()).isEqualTo(h3);
        assertThat(detail.dispatches()).hasSize(1);

        // 历次改派/拒收：一条 PENDING 拒收（已 RESOLVED）+ 一条 SUCCESS 改派
        assertThat(detail.diversions()).hasSize(2);
        assertThat(detail.diversions()).extracting(DiversionResponse::status)
                .containsExactly("RESOLVED", "SUCCESS");
        assertThat(detail.diversions()).extracting(DiversionResponse::reasonDetail)
                .contains("无床位", "高院停收");

        // 完整时间线包含车/组/医院各类动作（PREEMPTED 挂在被抢占的低优先级事件上，不在本事件）
        List<String> actions = detail.timeline().stream().map(TimelineEntryResponse::action).toList();
        assertThat(actions).contains("ASSIGNED", "RESERVED",
                "RESERVATION_RELEASED", "ARRIVED", "REJECTED", "DIVERTED");
        assertThat(actions).doesNotContain("PREEMPTED");

        EventDispatchDetailResponse lowDetail = dispatchService.getEventDispatchDetail(lowEvent);
        assertThat(lowDetail.currentDispatch()).isNull();
        assertThat(lowDetail.currentHospital()).isNull();
        assertThat(lowDetail.dispatches()).singleElement()
                .satisfies(d -> assertThat(d.status()).isEqualTo(DispatchStatus.PREEMPTED.name()));
    }

    @Test
    void resource_timeline_records_assigned_preempted_arrived_released() {
        Long amb = fixtures.ambulance("京A18", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十八组", CAPS, true);
        Long hospital = fixtures.hospital("十八院", TYPES, 5);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);
        DispatchResponse low = dispatch(amb, crew, lowEvent, hospital);
        DispatchResponse high = dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew, hospital));
        dispatchService.arrive(high.id());
        dispatchService.complete(high.id());

        List<TimelineEntryResponse> timeline = dispatchService.getAmbulanceTimeline(amb);
        assertThat(timeline).extracting(TimelineEntryResponse::action)
                .containsExactly("ASSIGNED", "PREEMPTED", "ASSIGNED", "ARRIVED", "RELEASED");
        assertThat(timeline).extracting(TimelineEntryResponse::dispatchId)
                .containsExactly(low.id(), low.id(), high.id(), high.id(), high.id());

        List<TimelineEntryResponse> crewTimeline = dispatchService.getCrewTimeline(crew);
        assertThat(crewTimeline).hasSize(5);
    }

    @Test
    void hospital_timeline_records_reservation_and_diversion_lifecycle() {
        Long amb = fixtures.ambulance("京A60", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("六十组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        Long h1 = fixtures.hospital("一院", TYPES, 5);
        Long h2 = fixtures.hospital("二院", TYPES, 5);
        DispatchResponse d = dispatch(amb, crew, event, h1);
        dispatchService.arrive(d.id());
        dispatchService.reject(new RejectRequest(bizNo(), d.id(), h1, "拒收原因X"));
        dispatchService.divert(new DivertRequest(bizNo(), d.id(), h1, h2,
                "CONDITION_CHANGED", "病情变化Y"));
        dispatchService.arriveAtHospital(d.id());
        dispatchService.complete(d.id());

        List<TimelineEntryResponse> h1Timeline = dispatchService.getHospitalTimeline(h1);
        assertThat(h1Timeline).extracting(TimelineEntryResponse::action)
                .containsExactly("RESERVED", "REJECTED", "RESERVATION_RELEASED");
        assertThat(h1Timeline.get(1).note()).isEqualTo("拒收原因X");

        List<TimelineEntryResponse> h2Timeline = dispatchService.getHospitalTimeline(h2);
        assertThat(h2Timeline).extracting(TimelineEntryResponse::action)
                .containsExactly("RESERVED", "DIVERTED", "ARRIVED_HOSPITAL", "RESERVATION_RELEASED");
        assertThat(h2Timeline.get(1).note()).contains("病情变化Y");
    }

    @Test
    void preemption_chain_queries_all_links_in_order() {
        Long amb = fixtures.ambulance("京A19", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十九组", CAPS, true);
        Long hospital = fixtures.hospital("十九院", TYPES, 10);
        Long e1 = fixtures.event("事件1", AREA, Priority.LOW, CAPS);
        Long e2 = fixtures.event("事件2", AREA, Priority.NORMAL, CAPS);
        Long e3 = fixtures.event("事件3", AREA, Priority.CRITICAL, CAPS);

        DispatchResponse d1 = dispatch(amb, crew, e1, hospital);
        DispatchResponse d2 = dispatchService.preempt(
                new PreemptRequest(bizNo(), e2, d1.id(), amb, crew, hospital));
        DispatchResponse d3 = dispatchService.preempt(
                new PreemptRequest(bizNo(), e3, d2.id(), amb, crew, hospital));

        List<PreemptionChainItemResponse> chainFromLatest =
                dispatchService.getPreemptionChain(d3.id());
        assertThat(chainFromLatest).extracting(PreemptionChainItemResponse::dispatchId)
                .containsExactly(d1.id(), d2.id(), d3.id());
        assertThat(chainFromLatest).extracting(PreemptionChainItemResponse::eventId)
                .containsExactly(e1, e2, e3);
        assertThat(chainFromLatest.get(2).preemptedDispatchId()).isEqualTo(d2.id());
        assertThat(chainFromLatest.get(0).preemptedDispatchId()).isNull();

        // 从链上任一节点查询都得到同一条链
        assertThat(dispatchService.getPreemptionChain(d1.id()))
                .extracting(PreemptionChainItemResponse::dispatchId)
                .containsExactly(d1.id(), d2.id(), d3.id());
    }

    @Test
    void dispatching_already_dispatched_event_is_rejected() {
        Long amb = fixtures.ambulance("京A20", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("二十组", CAPS, true);
        Long hospital = fixtures.hospital("二十院", TYPES, 5);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        dispatch(amb, crew, event, hospital);

        Long amb2 = fixtures.ambulance("京A21", Set.of(AREA), CAPS);
        Long crew2 = fixtures.crew("二十一组", CAPS, true);
        assertThatThrownBy(() -> dispatch(amb2, crew2, event, hospital))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DISPATCH_ALREADY_ACTIVE);
    }
}
