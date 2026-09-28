package com.chris64233.ambulancedispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chris64233.ambulancedispatch.TestFixtures;
import com.chris64233.ambulancedispatch.domain.AmbulanceStatus;
import com.chris64233.ambulancedispatch.domain.AssignmentStatus;
import com.chris64233.ambulancedispatch.domain.DiversionReason;
import com.chris64233.ambulancedispatch.domain.DiversionStatus;
import com.chris64233.ambulancedispatch.domain.DiversionTaskStatus;
import com.chris64233.ambulancedispatch.domain.DispatchStatus;
import com.chris64233.ambulancedispatch.domain.EmergencyType;
import com.chris64233.ambulancedispatch.domain.EventStatus;
import com.chris64233.ambulancedispatch.domain.Priority;
import com.chris64233.ambulancedispatch.domain.ReceivingStatus;
import com.chris64233.ambulancedispatch.domain.ReservationStatus;
import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.dto.DispatchResponse;
import com.chris64233.ambulancedispatch.dto.DiversionRequest;
import com.chris64233.ambulancedispatch.dto.DiversionResponse;
import com.chris64233.ambulancedispatch.dto.DiversionTaskResponse;
import com.chris64233.ambulancedispatch.dto.EventDispatchDetailResponse;
import com.chris64233.ambulancedispatch.dto.RejectionResponse;
import com.chris64233.ambulancedispatch.dto.UpdateHospitalRequest;
import com.chris64233.ambulancedispatch.exception.BusinessException;
import com.chris64233.ambulancedispatch.exception.ErrorCode;
import com.chris64233.ambulancedispatch.repository.AmbulanceRepository;
import com.chris64233.ambulancedispatch.repository.CrewRepository;
import com.chris64233.ambulancedispatch.repository.EmergencyEventRepository;
import com.chris64233.ambulancedispatch.repository.HospitalRepository;
import com.chris64233.ambulancedispatch.repository.HospitalReservationRepository;
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

/**
 * 目的医院预留、医院拒收与途中改派的集成测试。
 */
@SpringBootTest
class DestinationDiversionIntegrationTest {

    private static final String AREA = "海淀区";
    private static final Set<String> CAPS = Set.of("AED");
    private static final Set<EmergencyType> ALL_TYPES =
            Set.of(EmergencyType.GENERAL, EmergencyType.CARDIAC, EmergencyType.TRAUMA);

    @Autowired private DispatchService dispatchService;
    @Autowired private HospitalService hospitalService;
    @Autowired private TestFixtures fixtures;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private HospitalReservationRepository reservationRepository;
    @Autowired private AmbulanceRepository ambulanceRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private EmergencyEventRepository eventRepository;

    private String bizNo() {
        return "BIZ-" + UUID.randomUUID();
    }

    private Long hospital(String name, int capacity) {
        return fixtures.hospital(name, ALL_TYPES, capacity);
    }

    private DispatchResponse prepareOnScene(Long h1) {
        Long amb = fixtures.ambulance("京H-" + UUID.randomUUID(), Set.of(AREA), CAPS);
        Long crew = fixtures.crew("H组" + UUID.randomUUID(), CAPS, true);
        Long event = fixtures.event("中关村", AREA, Priority.NORMAL, EmergencyType.GENERAL, CAPS);
        DispatchResponse d = dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew, h1));
        dispatchService.arrive(d.id());
        return d;
    }

    // ------------------------------------------------------------------
    // 派遣：医院名额原子预留
    // ------------------------------------------------------------------

    @Test
    void dispatch_reserves_one_hospital_bed() {
        Long h = hospital("协和", 5);
        DispatchResponse d = prepareOnScene(h);

        assertThat(d.hospitalId()).isEqualTo(h);
        assertThat(hospitalRepository.findById(h).orElseThrow().getReservedCount()).isEqualTo(1);
        var reservation = reservationRepository
                .findByDispatchIdAndStatus(d.id(), ReservationStatus.HELD).orElseThrow();
        assertThat(reservation.getEmergencyType()).isEqualTo(EmergencyType.GENERAL);
    }

    @Test
    void dispatch_fails_when_hospital_closed_no_partial_dispatch() {
        Long h = fixtures.hospital("关闭医院", ALL_TYPES, 5, ReceivingStatus.CLOSED);
        Long amb = fixtures.ambulance("京HC", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("HC组", CAPS, true);
        Long event = fixtures.event("中关村", AREA, Priority.NORMAL, EmergencyType.GENERAL, CAPS);

        assertThatThrownBy(() -> dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew, h)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOSPITAL_NOT_RECEIVING);

        // 不形成部分派遣：车、组均未占用，事件仍待派遣
        assertThat(reservationRepository.findByEventIdOrderByReservedAtAscIdAsc(event)).isEmpty();
    }

    @Test
    void dispatch_fails_when_bed_full() {
        Long h = hospital("满床医院", 0);
        Long amb = fixtures.ambulance("京HF", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("HF组", CAPS, true);
        Long event = fixtures.event("中关村", AREA, Priority.NORMAL, CAPS);

        assertThatThrownBy(() -> dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew, h)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOSPITAL_BED_UNAVAILABLE);
    }

    @Test
    void dispatch_fails_when_hospital_does_not_accept_type() {
        Long h = fixtures.hospital("创伤专科", Set.of(EmergencyType.TRAUMA), 5);
        Long amb = fixtures.ambulance("京HT", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("HT组", CAPS, true);
        Long event = fixtures.event("中关村", AREA, Priority.NORMAL, EmergencyType.CARDIAC, CAPS);

        assertThatThrownBy(() -> dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew, h)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOSPITAL_TYPE_UNSUPPORTED);
    }

    @Test
    void complete_releases_hospital_bed() {
        Long h = hospital("北大", 3);
        DispatchResponse d = prepareOnScene(h);
        dispatchService.arriveHospital(d.id());
        dispatchService.complete(d.id());

        assertThat(hospitalRepository.findById(h).orElseThrow().getReservedCount()).isZero();
        assertThat(reservationRepository
                .findByDispatchIdAndStatus(d.id(), ReservationStatus.HELD)).isEmpty();
    }

    // ------------------------------------------------------------------
    // 并发争抢最后一个名额
    // ------------------------------------------------------------------

    @Test
    void concurrent_dispatch_contending_last_bed_only_one_reserves() throws Exception {
        Long h = hospital("单床医院", 1);
        Long amb1 = fixtures.ambulance("京HL1", Set.of(AREA), CAPS);
        Long crew1 = fixtures.crew("HL1组", CAPS, true);
        Long event1 = fixtures.event("地点1", AREA, Priority.NORMAL, CAPS);
        Long amb2 = fixtures.ambulance("京HL2", Set.of(AREA), CAPS);
        Long crew2 = fixtures.crew("HL2组", CAPS, true);
        Long event2 = fixtures.event("地点2", AREA, Priority.NORMAL, CAPS);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> f1 = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.dispatch(new DispatchRequest(bizNo(), event1, amb1, crew1, h));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            Future<?> f2 = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.dispatch(new DispatchRequest(bizNo(), event2, amb2, crew2, h));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            start.countDown();

            int success = 0;
            int rejected = 0;
            for (Future<?> f : List.of(f1, f2)) {
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

        // 容量绝不为负，也不超卖：恰好占用 1
        assertThat(hospitalRepository.findById(h).orElseThrow().getReservedCount()).isEqualTo(1);

        // 恰好一个事件被派遣、一个仍待派遣
        boolean e1Dispatched = eventRepository.findById(event1).orElseThrow()
                .getStatus() == EventStatus.DISPATCHED;
        boolean e2Dispatched = eventRepository.findById(event2).orElseThrow()
                .getStatus() == EventStatus.DISPATCHED;
        assertThat(e1Dispatched ^ e2Dispatched).isTrue();

        // 赢家车 DISPATCHED / 组 ASSIGNED；输家车 AVAILABLE / 组 IDLE（无部分派遣）
        Long winnerAmbulance = e1Dispatched ? amb1 : amb2;
        Long loserAmbulance = e1Dispatched ? amb2 : amb1;
        Long winnerCrew = e1Dispatched ? crew1 : crew2;
        Long loserCrew = e1Dispatched ? crew2 : crew1;
        assertThat(ambulanceRepository.findById(winnerAmbulance).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.DISPATCHED);
        assertThat(ambulanceRepository.findById(loserAmbulance).orElseThrow().getStatus())
                .isEqualTo(AmbulanceStatus.AVAILABLE);
        assertThat(crewRepository.findById(winnerCrew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.ASSIGNED);
        assertThat(crewRepository.findById(loserCrew).orElseThrow().getAssignmentStatus())
                .isEqualTo(AssignmentStatus.IDLE);
    }

    // ------------------------------------------------------------------
    // 改派成功
    // ------------------------------------------------------------------

    @Test
    void diversion_reserves_new_then_releases_old() {
        Long h1 = hospital("人民医院", 2);
        Long h2 = hospital("友谊医院", 2);
        DispatchResponse d = prepareOnScene(h1);

        DiversionResponse resp = dispatchService.divert(new DiversionRequest(
                bizNo(), d.id(), h2, DiversionReason.HOSPITAL_CLOSED, null, h1));

        assertThat(resp.status()).isEqualTo(DiversionStatus.SUCCESS.name());
        assertThat(resp.fromHospitalId()).isEqualTo(h1);
        assertThat(resp.toHospitalId()).isEqualTo(h2);
        // 原名额释放、新名额占用
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedCount()).isZero();
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedCount()).isEqualTo(1);
        // 派遣目的地已切换，车组不变
        DispatchResponse updated = dispatchService.getDispatch(d.id());
        assertThat(updated.hospitalId()).isEqualTo(h2);
        assertThat(updated.status()).isEqualTo(DispatchStatus.ON_SCENE.name());
        assertThat(updated.ambulanceId()).isEqualTo(d.ambulanceId());
        assertThat(updated.crewId()).isEqualTo(d.crewId());
        // 历史预留保留：原单 RELEASED，新单 HELD
        var reservations = reservationRepository.findByDispatchIdOrderByReservedAtAscIdAsc(d.id());
        assertThat(reservations).hasSize(2);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservations.get(1).getStatus()).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void diversion_failure_keeps_original_destination_and_reservation() {
        Long h1 = hospital("原医院", 2);
        Long h2 = hospital("满床目标", 0);
        DispatchResponse d = prepareOnScene(h1);

        DiversionResponse resp = dispatchService.divert(new DiversionRequest(
                bizNo(), d.id(), h2, DiversionReason.CONDITION_CHANGED, null, h1));

        assertThat(resp.status()).isEqualTo(DiversionStatus.FAILED.name());
        assertThat(resp.failMessage()).contains("床位已满");
        // 原目的地与名额保持不变
        assertThat(dispatchService.getDispatch(d.id()).hospitalId()).isEqualTo(h1);
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedCount()).isEqualTo(1);
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedCount()).isZero();
        // 失败也留痕
        EventDispatchDetailResponse detail = dispatchService.getEventDispatchDetail(d.eventId());
        assertThat(detail.diversions()).singleElement()
                .satisfies(x -> assertThat(x.status()).isEqualTo(DiversionStatus.FAILED.name()));
    }

    @Test
    void diversion_to_closed_hospital_fails_and_closed_gets_no_new_event() {
        Long h1 = hospital("原医院C", 2);
        Long h2 = fixtures.hospital("已关闭C", ALL_TYPES, 2, ReceivingStatus.CLOSED);
        DispatchResponse d = prepareOnScene(h1);

        DiversionResponse resp = dispatchService.divert(new DiversionRequest(
                bizNo(), d.id(), h2, DiversionReason.HOSPITAL_CLOSED, null, h1));
        assertThat(resp.status()).isEqualTo(DiversionStatus.FAILED.name());
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedCount()).isZero();
        assertThat(dispatchService.getDispatch(d.id()).hospitalId()).isEqualTo(h1);
    }

    @Test
    void diversion_with_changed_condition_matches_new_type() {
        Long h1 = fixtures.hospital("综合D", Set.of(EmergencyType.GENERAL), 2);
        Long h2 = fixtures.hospital("心脑血管D", Set.of(EmergencyType.CARDIAC), 2);
        DispatchResponse d = prepareOnScene(h1);

        DiversionResponse resp = dispatchService.divert(new DiversionRequest(
                bizNo(), d.id(), h2, DiversionReason.CONDITION_CHANGED,
                EmergencyType.CARDIAC, h1));

        assertThat(resp.status()).isEqualTo(DiversionStatus.SUCCESS.name());
        assertThat(resp.emergencyType()).isEqualTo(EmergencyType.CARDIAC.name());
        assertThat(dispatchService.getDispatch(d.id()).hospitalId()).isEqualTo(h2);
    }

    @Test
    void cannot_divert_after_arrived_at_hospital() {
        Long h1 = hospital("原医院A", 2);
        Long h2 = hospital("目标A", 2);
        DispatchResponse d = prepareOnScene(h1);
        dispatchService.arriveHospital(d.id());

        assertThatThrownBy(() -> dispatchService.divert(new DiversionRequest(
                bizNo(), d.id(), h2, DiversionReason.HOSPITAL_CLOSED, null, h1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DIVERSION_ALREADY_ARRIVED);

        assertThat(dispatchService.getDispatch(d.id()).hospitalId()).isEqualTo(h1);
    }

    @Test
    void stale_diversion_cannot_overwrite_later_confirmed_destination() {
        Long h1 = hospital("第一医院", 2);
        Long h2 = hospital("第二医院", 2);
        Long h3 = hospital("第三医院", 2);
        DispatchResponse d = prepareOnScene(h1);

        // 先成功改派 h1 -> h2
        dispatchService.divert(new DiversionRequest(
                bizNo(), d.id(), h2, DiversionReason.HOSPITAL_CLOSED, null, h1));

        // 旧请求仍以为当前是 h1（expectedHospitalId=h1），试图改去 h3 -> 冲突，不覆盖
        assertThatThrownBy(() -> dispatchService.divert(new DiversionRequest(
                bizNo(), d.id(), h3, DiversionReason.HOSPITAL_CLOSED, null, h1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DIVERSION_DESTINATION_CONFLICT);

        // 目的地仍是后来确认的 h2，h3 未收到名额
        assertThat(dispatchService.getDispatch(d.id()).hospitalId()).isEqualTo(h2);
        assertThat(hospitalRepository.findById(h3).orElseThrow().getReservedCount()).isZero();
    }

    @Test
    void diversion_is_idempotent_and_detects_conflict() {
        Long h1 = hospital("原医院I", 2);
        Long h2 = hospital("目标I", 2);
        Long h3 = hospital("另一目标I", 2);
        DispatchResponse d = prepareOnScene(h1);
        String biz = bizNo();

        DiversionRequest req = new DiversionRequest(
                biz, d.id(), h2, DiversionReason.HOSPITAL_CLOSED, null, h1);
        DiversionResponse first = dispatchService.divert(req);
        DiversionResponse replay = dispatchService.divert(req);
        assertThat(replay.diversionId()).isEqualTo(first.diversionId());
        assertThat(replay.replayed()).isTrue();
        // 只切换一次：h2 占 1，h1 为 0
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedCount()).isEqualTo(1);
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedCount()).isZero();

        // 同号异内容 409
        assertThatThrownBy(() -> dispatchService.divert(new DiversionRequest(
                biz, d.id(), h3, DiversionReason.HOSPITAL_CLOSED, null, h2)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_CONFLICT);
    }

    @Test
    void failed_diversion_same_biz_returns_first_failure() {
        Long h1 = hospital("原医院F", 2);
        Long h2 = hospital("满床F", 0);
        DispatchResponse d = prepareOnScene(h1);
        String biz = bizNo();

        DiversionRequest req = new DiversionRequest(
                biz, d.id(), h2, DiversionReason.CONDITION_CHANGED, null, h1);
        DiversionResponse first = dispatchService.divert(req);
        DiversionResponse replay = dispatchService.divert(req);
        assertThat(first.status()).isEqualTo(DiversionStatus.FAILED.name());
        assertThat(replay.diversionId()).isEqualTo(first.diversionId());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(DiversionStatus.FAILED.name());
    }

    // ------------------------------------------------------------------
    // 医院拒收
    // ------------------------------------------------------------------

    @Test
    void rejection_records_reason_creates_task_and_keeps_resources() {
        Long h1 = hospital("拒收医院", 2);
        DispatchResponse d = prepareOnScene(h1);

        RejectionResponse rejection = dispatchService.reject(d.id(), "ICU 满床，无法接收");

        assertThat(rejection.reason()).isEqualTo("ICU 满床，无法接收");
        assertThat(rejection.diversionTaskId()).isNotNull();
        // 名额归还
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedCount()).isZero();
        var reservation = reservationRepository
                .findByDispatchIdAndStatus(d.id(), ReservationStatus.REJECTED)
                .orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.REJECTED);
        // 车与救护组不释放
        // 通过待处理任务确认任务已生成
        List<DiversionTaskResponse> pending = dispatchService.listPendingDiversionTasks();
        assertThat(pending).anySatisfy(t -> {
            assertThat(t.id()).isEqualTo(rejection.diversionTaskId());
            assertThat(t.status()).isEqualTo(DiversionTaskStatus.PENDING.name());
            assertThat(t.reason()).isEqualTo(DiversionReason.REJECTED.name());
        });
        // 派遣仍 ON_SCENE，目的地不变
        DispatchResponse current = dispatchService.getDispatch(d.id());
        assertThat(current.status()).isEqualTo(DispatchStatus.ON_SCENE.name());
        assertThat(current.hospitalId()).isEqualTo(h1);
    }

    @Test
    void cannot_arrive_hospital_after_rejection_without_successful_diversion() {
        Long h1 = hospital("拒收未改派院", 2);
        Long h2 = hospital("满床改不了院", 0);
        DispatchResponse d = prepareOnScene(h1);
        RejectionResponse rejection = dispatchService.reject(d.id(), "无法接收");

        // 改派失败（目标满床）
        DiversionResponse failed = dispatchService.divert(new DiversionRequest(
                bizNo(), d.id(), h2, DiversionReason.REJECTED, null, h1));
        assertThat(failed.status()).isEqualTo(DiversionStatus.FAILED.name());

        // 未成功改派前不能到达已拒收的医院
        assertThatThrownBy(() -> dispatchService.arriveHospital(d.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DIVERSION_NOT_ALLOWED);

        // 取消后改派任务关闭，不再滞留 PENDING
        dispatchService.cancel(d.id());
        assertThat(dispatchService.listPendingDiversionTasks())
                .noneMatch(t -> t.id().equals(rejection.diversionTaskId()));
    }

    @Test
    void reject_then_divert_fulfills_task() {
        Long h1 = hospital("拒收后原院", 2);
        Long h2 = hospital("改派接收院", 2);
        DispatchResponse d = prepareOnScene(h1);
        RejectionResponse rejection = dispatchService.reject(d.id(), "无相应专科");

        DiversionResponse resp = dispatchService.divert(new DiversionRequest(
                bizNo(), d.id(), h2, DiversionReason.REJECTED, null, h1));
        assertThat(resp.status()).isEqualTo(DiversionStatus.SUCCESS.name());

        // 任务关闭
        assertThat(dispatchService.listPendingDiversionTasks())
                .noneMatch(t -> t.id().equals(rejection.diversionTaskId()));
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedCount()).isEqualTo(1);
    }

    @Test
    void cannot_reject_before_arrival_or_after_hospital_arrival() {
        Long h1 = hospital("拒收时机院", 2);
        Long amb = fixtures.ambulance("京HR", Set.of(AREA), CAPS);
        Long crew = fixtures.crew("HR组", CAPS, true);
        Long event = fixtures.event("中关村", AREA, Priority.NORMAL, CAPS);
        DispatchResponse d = dispatchService.dispatch(
                new DispatchRequest(bizNo(), event, amb, crew, h1));

        // 尚未到达现场，不能拒收
        assertThatThrownBy(() -> dispatchService.reject(d.id(), "太早"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REJECTION_NOT_ALLOWED);

        dispatchService.arrive(d.id());
        dispatchService.arriveHospital(d.id());
        // 已到达医院，不能拒收
        assertThatThrownBy(() -> dispatchService.reject(d.id(), "太晚"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REJECTION_NOT_ALLOWED);
    }

    // ------------------------------------------------------------------
    // 医院状态更新与改派并发
    // ------------------------------------------------------------------

    @Test
    void closing_hospital_while_diverting_never_makes_count_negative() throws Exception {
        Long h1 = hospital("并发原院", 1);
        Long h2 = hospital("并发目标院", 1);
        DispatchResponse d = prepareOnScene(h1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> divert = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.divert(new DiversionRequest(
                            bizNo(), d.id(), h2, DiversionReason.HOSPITAL_CLOSED, null, h1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            Future<?> close = pool.submit(() -> {
                try {
                    start.await();
                    hospitalService.update(h2, new UpdateHospitalRequest(
                            null, null, ReceivingStatus.CLOSED));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            start.countDown();
            divert.get();
            close.get();
        } finally {
            pool.shutdown();
        }

        // 无论谁先，名额计数都在合法范围：原院 0，目标院 0 或 1
        int h1Count = hospitalRepository.findById(h1).orElseThrow().getReservedCount();
        int h2Count = hospitalRepository.findById(h2).orElseThrow().getReservedCount();
        assertThat(h1Count).isBetween(0, 1);
        assertThat(h2Count).isBetween(0, 1);
        assertThat(h1Count + h2Count).isEqualTo(1);
    }

    @Test
    void update_capacity_cannot_go_below_reserved() {
        Long h = hospital("容量收缩院", 5);
        prepareOnScene(h); // 占用 1
        assertThatThrownBy(() -> hospitalService.update(h,
                new UpdateHospitalRequest(null, 0, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ------------------------------------------------------------------
    // 完整事件查询
    // ------------------------------------------------------------------

    @Test
    void opposite_diversions_between_two_hospitals_deadlock_free() throws Exception {
        // 两家医院各有一个 ON_SCENE 派遣，两个线程反向往对方医院改派（H1<->H2）
        Long h1 = hospital("双向院A", 5);
        Long h2 = hospital("双向院B", 5);
        DispatchResponse d1 = prepareOnScene(h1);
        DispatchResponse d2 = prepareOnScene(h2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> f1 = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.divert(new DiversionRequest(
                            bizNo(), d1.id(), h2, DiversionReason.HOSPITAL_CLOSED, null, h1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            Future<?> f2 = pool.submit(() -> {
                try {
                    start.await();
                    dispatchService.divert(new DiversionRequest(
                            bizNo(), d2.id(), h1, DiversionReason.HOSPITAL_CLOSED, null, h2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            start.countDown();
            // 两条改派都应成功，不抛出死锁/数据访问异常
            f1.get();
            f2.get();
        } finally {
            pool.shutdown();
        }

        assertThat(dispatchService.getDispatch(d1.id()).hospitalId()).isEqualTo(h2);
        assertThat(dispatchService.getDispatch(d2.id()).hospitalId()).isEqualTo(h1);
        // 名额守恒：每家各占 1
        assertThat(hospitalRepository.findById(h1).orElseThrow().getReservedCount()).isEqualTo(1);
        assertThat(hospitalRepository.findById(h2).orElseThrow().getReservedCount()).isEqualTo(1);
    }

    @Test
    void event_detail_shows_reservations_diversions_rejections_and_timeline() {
        Long h1 = hospital("详情原院", 2);
        Long h2 = hospital("详情新院", 2);
        DispatchResponse d = prepareOnScene(h1);
        dispatchService.reject(d.id(), "设备故障");
        dispatchService.divert(new DiversionRequest(
                bizNo(), d.id(), h2, DiversionReason.REJECTED, null, h1));

        EventDispatchDetailResponse detail = dispatchService.getEventDispatchDetail(d.eventId());
        assertThat(detail.reservations()).hasSize(2);
        assertThat(detail.diversions()).singleElement()
                .satisfies(x -> assertThat(x.status()).isEqualTo(DiversionStatus.SUCCESS.name()));
        assertThat(detail.rejections()).singleElement()
                .satisfies(r -> assertThat(r.reason()).isEqualTo("设备故障"));
        assertThat(detail.diversionTasks()).singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo(DiversionTaskStatus.FULFILLED.name()));
        assertThat(detail.timeline()).extracting("type")
                .contains("DISPATCHED", "ARRIVED_SCENE", "RESERVED",
                        "REJECTED", "DIVERTED");
    }
}
