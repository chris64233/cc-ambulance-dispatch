package com.chris64233.ambulancedispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chris64233.ambulancedispatch.TestFixtures;
import com.chris64233.ambulancedispatch.domain.Ambulance;
import com.chris64233.ambulancedispatch.domain.AmbulanceStatus;
import com.chris64233.ambulancedispatch.domain.AssignmentStatus;
import com.chris64233.ambulancedispatch.domain.Crew;
import com.chris64233.ambulancedispatch.domain.DispatchStatus;
import com.chris64233.ambulancedispatch.domain.EmergencyEvent;
import com.chris64233.ambulancedispatch.domain.EventStatus;
import com.chris64233.ambulancedispatch.domain.Priority;
import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.dto.DispatchResponse;
import com.chris64233.ambulancedispatch.dto.EventDispatchDetailResponse;
import com.chris64233.ambulancedispatch.dto.PreemptRequest;
import com.chris64233.ambulancedispatch.dto.PreemptionChainItemResponse;
import com.chris64233.ambulancedispatch.dto.TimelineEntryResponse;
import com.chris64233.ambulancedispatch.exception.BusinessException;
import com.chris64233.ambulancedispatch.exception.ErrorCode;
import com.chris64233.ambulancedispatch.repository.AmbulanceRepository;
import com.chris64233.ambulancedispatch.repository.CrewRepository;
import com.chris64233.ambulancedispatch.repository.DispatchRepository;
import com.chris64233.ambulancedispatch.repository.EmergencyEventRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DispatchServiceIntegrationTest {

    private static final String AREA = "东城区";
    private static final Set<String> CAPS = Set.of("AED", "VENTILATOR");

    @Autowired private DispatchService dispatchService;
    @Autowired private CatalogService catalogService;
    @Autowired private TestFixtures fixtures;
    @Autowired private AmbulanceRepository ambulanceRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private EmergencyEventRepository eventRepository;
    @Autowired private DispatchRepository dispatchRepository;

    private String bizNo() {
        return "BIZ-" + UUID.randomUUID();
    }

    // ------------------------------------------------------------------
    // 联合派遣
    // ------------------------------------------------------------------

    @Test
    void dispatch_occupies_ambulance_and_crew_atomically() {
        Long amb = fixtures.ambulance("京A1", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("一组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);

        DispatchResponse resp = dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew));

        assertThat(resp.status()).isEqualTo(DispatchStatus.EN_ROUTE.name());
        assertThat(resp.preemptionRootId()).isEqualTo(resp.id());
        assertThat(ambulanceRepository.findById(amb).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.DISPATCHED);
        assertThat(crewRepository.findById(crew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.ASSIGNED);
        assertThat(eventRepository.findById(event).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DISPATCHED);
    }

    @Test
    void dispatch_rejects_service_area_mismatch() {
        Long amb = fixtures.ambulance("京A2", Set.of("西城区"), CAPS);
        Long crew = fixtures.crew("二组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);

        assertThatThrownBy(() -> dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_AREA_MISMATCH);

        // 校验失败不得占用任何资源
        assertThat(ambulanceRepository.findById(amb).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.AVAILABLE);
        assertThat(crewRepository.findById(crew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.IDLE);
    }

    @Test
    void dispatch_rejects_capability_mismatch() {
        Long amb = fixtures.ambulance("京A3", Set.of(AREA), Set.of("AED"));
        Long crew = fixtures.crew("三组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);

        assertThatThrownBy(() -> dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CAPABILITY_MISMATCH);
    }

    @Test
    void dispatch_rejects_off_duty_crew() {
        Long amb = fixtures.ambulance("京A4", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("四组", CAPS, false);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);

        assertThatThrownBy(() -> dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CREW_OFF_DUTY);
    }

    // ------------------------------------------------------------------
    // 并发争抢
    // ------------------------------------------------------------------

    @Test
    void concurrent_dispatch_same_resources_only_one_succeeds() throws Exception {
        Long amb = fixtures.ambulance("京A5", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("五组", CAPS, true);
        Long event1 = fixtures.event("东单1", AREA, Priority.NORMAL, CAPS);
        Long event2 = fixtures.event("东单2", AREA, Priority.NORMAL, CAPS);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> f1 = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.dispatch(new DispatchRequest(bizNo(), event1, amb, crew));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            Future<?> f2 = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.dispatch(new DispatchRequest(bizNo(), event2, amb, crew));
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

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void idempotent_retry_returns_original_result() {
        Long amb = fixtures.ambulance("京A6", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("六组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        String bizNo = bizNo();

        DispatchResponse first = dispatchService.dispatch(
                new DispatchRequest(bizNo, event, amb, crew));
        DispatchResponse replay = dispatchService.dispatch(
                new DispatchRequest(bizNo, event, amb, crew));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.replayed()).isTrue();
        assertThat(dispatchRepository.findByEventIdOrderByDispatchedAtDesc(event)).hasSize(1);
    }

    @Test
    void same_biz_no_different_content_returns_conflict() {
        Long amb = fixtures.ambulance("京A7", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("七组", CAPS, true);
        Long event1 = fixtures.event("东单1", AREA, Priority.NORMAL, CAPS);
        Long event2 = fixtures.event("东单2", AREA, Priority.NORMAL, CAPS);
        String bizNo = bizNo();

        dispatchService.dispatch(new DispatchRequest(bizNo, event1, amb, crew));

        assertThatThrownBy(() -> dispatchService.dispatch(
                new DispatchRequest(bizNo, event2, amb, crew)))
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
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);

        DispatchResponse low = dispatchService.dispatch(
                new DispatchRequest(bizNo(), lowEvent, amb, crew));
        DispatchResponse high = dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew));

        assertThat(high.status()).isEqualTo(DispatchStatus.EN_ROUTE.name());
        assertThat(high.preemptedDispatchId()).isEqualTo(low.id());
        assertThat(high.preemptionRootId()).isEqualTo(low.id());

        // 原事件恢复为待派遣；原单 PREEMPTED；新事件已派遣
        assertThat(eventRepository.findById(lowEvent).orElseThrow().getStatus())
                .isEqualTo(EventStatus.PENDING);
        assertThat(eventRepository.findById(highEvent).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DISPATCHED);
        assertThat(dispatchRepository.findById(low.id()).orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.PREEMPTED);
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
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);

        DispatchResponse low = dispatchService.dispatch(
                new DispatchRequest(bizNo(), lowEvent, amb, crew));
        dispatchService.arrive(low.id());

        assertThatThrownBy(() -> dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DISPATCH_ALREADY_ARRIVED);

        // 抢占被拒：原派遣与资源归属完全不变
        assertThat(dispatchRepository.findById(low.id()).orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.ON_SCENE);
        assertThat(eventRepository.findById(lowEvent).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DISPATCHED);
        assertThat(eventRepository.findById(highEvent).orElseThrow().getStatus())
                .isEqualTo(EventStatus.PENDING);
    }

    @Test
    void equal_or_lower_priority_cannot_preempt() {
        Long amb = fixtures.ambulance("京A10", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十组", CAPS, true);
        Long normalEvent = fixtures.event("东单常", AREA, Priority.NORMAL, CAPS);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);

        DispatchResponse normal = dispatchService.dispatch(
                new DispatchRequest(bizNo(), normalEvent, amb, crew));

        assertThatThrownBy(() -> dispatchService.preempt(
                new PreemptRequest(bizNo(), lowEvent, normal.id(), amb, crew)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PREEMPTION_NOT_HIGHER_PRIORITY);
    }

    @Test
    void failed_preemption_keeps_original_dispatch_unchanged() {
        // 新事件要求 VENTILATOR，但车辆不具备 -> 抢占安全失败，原派遣不动
        Long amb = fixtures.ambulance("京A11", Set.of(AREA), Set.of("AED"));
        Long crew = fixtures.crew("十一组", CAPS, true);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, Set.of("AED"));
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);

        DispatchResponse low = dispatchService.dispatch(
                new DispatchRequest(bizNo(), lowEvent, amb, crew));

        assertThatThrownBy(() -> dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew)))
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
    }

    @Test
    void preempt_is_idempotent_and_detects_conflict() {
        Long amb = fixtures.ambulance("京A12", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十二组", CAPS, true);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);
        DispatchResponse low = dispatchService.dispatch(
                new DispatchRequest(bizNo(), lowEvent, amb, crew));

        String bizNo = bizNo();
        DispatchResponse first = dispatchService.preempt(
                new PreemptRequest(bizNo, highEvent, low.id(), amb, crew));
        DispatchResponse replay = dispatchService.preempt(
                new PreemptRequest(bizNo, highEvent, low.id(), amb, crew));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.replayed()).isTrue();

        Long otherEvent = fixtures.event("另一急", AREA, Priority.HIGH, CAPS);
        assertThatThrownBy(() -> dispatchService.preempt(
                new PreemptRequest(bizNo, otherEvent, low.id(), amb, crew)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_CONFLICT);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Test
    void preempt_vs_arrive_race_only_one_takes_effect() throws Exception {
        Long amb = fixtures.ambulance("京A22", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("二二组", CAPS, true);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);
        DispatchResponse low = dispatchService.dispatch(
                new DispatchRequest(bizNo(), lowEvent, amb, crew));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> preemptF = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.preempt(new PreemptRequest(
                            bizNo(), highEvent, low.id(), amb, crew));
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
        } else {
            assertThat(lowStatus).isEqualTo(DispatchStatus.PREEMPTED);
            assertThat(lowEventStatus).isEqualTo(EventStatus.PENDING);
            assertThat(highEventStatus).isEqualTo(EventStatus.DISPATCHED);
        }
    }

    @Test
    void complete_releases_resources_and_event_becomes_terminal() {
        Long amb = fixtures.ambulance("京A13", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十三组", CAPS, true);
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        DispatchResponse d = dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew));

        dispatchService.arrive(d.id());
        DispatchResponse finished = dispatchService.complete(d.id());

        assertThat(finished.status()).isEqualTo(DispatchStatus.COMPLETED.name());
        assertThat(ambulanceRepository.findById(amb).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.AVAILABLE);
        assertThat(crewRepository.findById(crew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.IDLE);
        EmergencyEvent e = eventRepository.findById(event).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(EventStatus.COMPLETED);

        // 终态不可修改：再次完成/取消均被拒
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
        DispatchResponse d = dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew));

        DispatchResponse cancelled = dispatchService.cancel(d.id());
        assertThat(cancelled.status()).isEqualTo(DispatchStatus.CANCELLED.name());
        assertThat(eventRepository.findById(event).orElseThrow().getStatus())
                .isEqualTo(EventStatus.CANCELLED);

        // 对已取消事件再次派遣被拒
        Long amb2 = fixtures.ambulance("京A15", Set.of(AREA), CAPS);
        Long crew2 = fixtures.crew("十五组", CAPS, true);
        assertThatThrownBy(() -> dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb2, crew2)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_TERMINAL);
    }

    @Test
    void preempted_event_can_be_redispatched_after_release() {
        Long amb = fixtures.ambulance("京A16", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十六组", CAPS, true);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);
        DispatchResponse low = dispatchService.dispatch(
                new DispatchRequest(bizNo(), lowEvent, amb, crew));
        DispatchResponse high = dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew));

        // 高优先级完成释放资源后，原待派遣事件可重新派遣
        dispatchService.arrive(high.id());
        dispatchService.complete(high.id());

        DispatchResponse redispatch = dispatchService.dispatch(
                new DispatchRequest(bizNo(), lowEvent, amb, crew));
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
    void event_dispatch_detail_shows_current_and_history() {
        Long amb = fixtures.ambulance("京A17", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十七组", CAPS, true);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);
        DispatchResponse low = dispatchService.dispatch(
                new DispatchRequest(bizNo(), lowEvent, amb, crew));
        DispatchResponse high = dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew));

        EventDispatchDetailResponse detail = dispatchService.getEventDispatchDetail(highEvent);
        assertThat(detail.event().id()).isEqualTo(highEvent);
        assertThat(detail.currentDispatch().id()).isEqualTo(high.id());
        assertThat(detail.dispatches()).hasSize(1);

        EventDispatchDetailResponse lowDetail = dispatchService.getEventDispatchDetail(lowEvent);
        assertThat(lowDetail.currentDispatch()).isNull();
        assertThat(lowDetail.dispatches()).singleElement()
                .satisfies(d -> assertThat(d.status()).isEqualTo(DispatchStatus.PREEMPTED.name()));
    }

    @Test
    void resource_timeline_records_assigned_preempted_arrived_released() {
        Long amb = fixtures.ambulance("京A18", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十八组", CAPS, true);
        Long lowEvent = fixtures.event("东单低", AREA, Priority.LOW, CAPS);
        Long highEvent = fixtures.event("东单急", AREA, Priority.CRITICAL, CAPS);
        DispatchResponse low = dispatchService.dispatch(
                new DispatchRequest(bizNo(), lowEvent, amb, crew));
        DispatchResponse high = dispatchService.preempt(
                new PreemptRequest(bizNo(), highEvent, low.id(), amb, crew));
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
    void preemption_chain_queries_all_links_in_order() {
        Long amb = fixtures.ambulance("京A19", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("十九组", CAPS, true);
        Long e1 = fixtures.event("事件1", AREA, Priority.LOW, CAPS);
        Long e2 = fixtures.event("事件2", AREA, Priority.NORMAL, CAPS);
        Long e3 = fixtures.event("事件3", AREA, Priority.CRITICAL, CAPS);

        DispatchResponse d1 = dispatchService.dispatch(new DispatchRequest(bizNo(), e1, amb, crew));
        DispatchResponse d2 = dispatchService.preempt(
                new PreemptRequest(bizNo(), e2, d1.id(), amb, crew));
        DispatchResponse d3 = dispatchService.preempt(
                new PreemptRequest(bizNo(), e3, d2.id(), amb, crew));

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
        Long event = fixtures.event("东单", AREA, Priority.NORMAL, CAPS);
        dispatchService.dispatch(new DispatchRequest(bizNo(), event, amb, crew));

        Long amb2 = fixtures.ambulance("京A21", Set.of(AREA), CAPS);
        Long crew2 = fixtures.crew("二十一组", CAPS, true);
        assertThatThrownBy(() -> dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb2, crew2)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DISPATCH_ALREADY_ACTIVE);
    }
}
