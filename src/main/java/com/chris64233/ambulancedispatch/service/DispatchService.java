package com.chris64233.ambulancedispatch.service;

import com.chris64233.ambulancedispatch.domain.Ambulance;
import com.chris64233.ambulancedispatch.domain.Crew;
import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.domain.DispatchStatus;
import com.chris64233.ambulancedispatch.domain.DiversionReason;
import com.chris64233.ambulancedispatch.domain.DiversionRecord;
import com.chris64233.ambulancedispatch.domain.DiversionStatus;
import com.chris64233.ambulancedispatch.domain.DiversionTask;
import com.chris64233.ambulancedispatch.domain.DiversionTaskStatus;
import com.chris64233.ambulancedispatch.domain.DutyStatus;
import com.chris64233.ambulancedispatch.domain.EmergencyEvent;
import com.chris64233.ambulancedispatch.domain.EmergencyType;
import com.chris64233.ambulancedispatch.domain.EventStatus;
import com.chris64233.ambulancedispatch.domain.Hospital;
import com.chris64233.ambulancedispatch.domain.HospitalRejection;
import com.chris64233.ambulancedispatch.domain.HospitalReservation;
import com.chris64233.ambulancedispatch.domain.ReceivingStatus;
import com.chris64233.ambulancedispatch.domain.ReservationStatus;
import com.chris64233.ambulancedispatch.domain.ResourceTimelineEntry;
import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.dto.DispatchResponse;
import com.chris64233.ambulancedispatch.dto.DiversionRequest;
import com.chris64233.ambulancedispatch.dto.DiversionResponse;
import com.chris64233.ambulancedispatch.dto.DiversionTaskResponse;
import com.chris64233.ambulancedispatch.dto.EventDispatchDetailResponse;
import com.chris64233.ambulancedispatch.dto.EventResponse;
import com.chris64233.ambulancedispatch.dto.EventTimelineEntryResponse;
import com.chris64233.ambulancedispatch.dto.PreemptRequest;
import com.chris64233.ambulancedispatch.dto.PreemptionChainItemResponse;
import com.chris64233.ambulancedispatch.dto.RejectionResponse;
import com.chris64233.ambulancedispatch.dto.ReservationResponse;
import com.chris64233.ambulancedispatch.dto.TimelineEntryResponse;
import com.chris64233.ambulancedispatch.exception.BusinessException;
import com.chris64233.ambulancedispatch.exception.ErrorCode;
import com.chris64233.ambulancedispatch.repository.AmbulanceRepository;
import com.chris64233.ambulancedispatch.repository.CrewRepository;
import com.chris64233.ambulancedispatch.repository.DispatchRepository;
import com.chris64233.ambulancedispatch.repository.DiversionRecordRepository;
import com.chris64233.ambulancedispatch.repository.DiversionTaskRepository;
import com.chris64233.ambulancedispatch.repository.EmergencyEventRepository;
import com.chris64233.ambulancedispatch.repository.HospitalRepository;
import com.chris64233.ambulancedispatch.repository.HospitalRejectionRepository;
import com.chris64233.ambulancedispatch.repository.HospitalReservationRepository;
import com.chris64233.ambulancedispatch.repository.ResourceTimelineEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 联合派遣核心服务：车辆 + 救护组 + 目的医院名额三者原子占用，以及高优先级抢占、
 * 医院拒收与途中改派。
 *
 * <p>并发安全：事件/派遣/资源/医院行使用悲观行锁串行；车辆与救护组用条件更新（CAS）占用；
 * 医院名额在持锁事务内校验并占用，容量永不为负、不超卖。任一资源不足整个事务回滚，
 * 不会形成部分派遣。
 *
 * <p>改派严格按“新医院预留成功后才释放原名额”执行：新医院不可用时只记录一次失败改派，
 * 原目的地与派遣保持不变。已到达医院（AT_HOSPITAL）的事件不可再改派。
 */
@Service
public class DispatchService {

    private static final List<DispatchStatus> ACTIVE_STATUSES =
            List.of(DispatchStatus.EN_ROUTE, DispatchStatus.ON_SCENE, DispatchStatus.AT_HOSPITAL);

    private final DispatchRepository dispatchRepository;
    private final EmergencyEventRepository eventRepository;
    private final AmbulanceRepository ambulanceRepository;
    private final CrewRepository crewRepository;
    private final HospitalRepository hospitalRepository;
    private final HospitalReservationRepository reservationRepository;
    private final HospitalRejectionRepository rejectionRepository;
    private final DiversionRecordRepository diversionRepository;
    private final DiversionTaskRepository diversionTaskRepository;
    private final ResourceTimelineEntryRepository timelineRepository;
    private final RequestFingerprint fingerprint;
    private final Clock clock;

    public DispatchService(DispatchRepository dispatchRepository,
                           EmergencyEventRepository eventRepository,
                           AmbulanceRepository ambulanceRepository,
                           CrewRepository crewRepository,
                           HospitalRepository hospitalRepository,
                           HospitalReservationRepository reservationRepository,
                           HospitalRejectionRepository rejectionRepository,
                           DiversionRecordRepository diversionRepository,
                           DiversionTaskRepository diversionTaskRepository,
                           ResourceTimelineEntryRepository timelineRepository,
                           RequestFingerprint fingerprint,
                           Clock clock) {
        this.dispatchRepository = dispatchRepository;
        this.eventRepository = eventRepository;
        this.ambulanceRepository = ambulanceRepository;
        this.crewRepository = crewRepository;
        this.hospitalRepository = hospitalRepository;
        this.reservationRepository = reservationRepository;
        this.rejectionRepository = rejectionRepository;
        this.diversionRepository = diversionRepository;
        this.diversionTaskRepository = diversionTaskRepository;
        this.timelineRepository = timelineRepository;
        this.fingerprint = fingerprint;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 联合派遣（车 + 组 + 医院名额，原子）
    // ------------------------------------------------------------------

    @Transactional
    public DispatchResponse dispatch(DispatchRequest request) {
        String fp = fingerprint.dispatch(request);
        Dispatch existing = dispatchRepository.findDetailedByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, fp);
        }

        EmergencyEvent event = eventRepository.findWithLockById(request.eventId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
        existing = dispatchRepository.findDetailedByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, fp);
        }
        if (event.getStatus() == EventStatus.COMPLETED || event.getStatus() == EventStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.EVENT_TERMINAL);
        }
        if (event.getStatus() == EventStatus.DISPATCHED) {
            throw new BusinessException(ErrorCode.DISPATCH_ALREADY_ACTIVE);
        }

        Ambulance ambulance = ambulanceRepository.findWithLockById(request.ambulanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AMBULANCE_NOT_FOUND));
        Crew crew = crewRepository.findWithLockById(request.crewId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CREW_NOT_FOUND));
        Hospital hospital = hospitalRepository.findWithLockById(request.hospitalId())
                .orElseThrow(() -> new BusinessException(ErrorCode.HOSPITAL_NOT_FOUND));
        validateAssignment(event, ambulance, crew);
        // 医院名额与车、组同等关键：任一不足都不得形成部分派遣（异常即整体回滚）
        validateHospitalCanAccept(hospital, event.getEmergencyType());

        if (ambulanceRepository.occupyIfAvailable(ambulance.getId()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_UNAVAILABLE, "车辆已被占用");
        }
        if (crewRepository.occupyIfIdleAndOnDuty(crew.getId()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_UNAVAILABLE, "救护组已被占用或不值勤");
        }
        hospital.holdOneBed();

        event.setStatus(EventStatus.DISPATCHED);
        Instant now = clock.instant();
        Dispatch dispatch = new Dispatch(request.bizNo(), fp, event, ambulance, crew, hospital,
                null, null, now);
        try {
            dispatchRepository.save(dispatch);
            dispatchRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号已被使用且内容不一致: " + request.bizNo());
        }
        dispatch.setPreemptionRoot(dispatch);
        dispatchRepository.save(dispatch);
        reservationRepository.save(new HospitalReservation(
                hospital, dispatch, event, event.getEmergencyType(), now));

        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.AMBULANCE, ambulance.getId(), null,
                dispatch, ResourceTimelineEntry.Action.ASSIGNED, now));
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.CREW, null, crew.getId(),
                dispatch, ResourceTimelineEntry.Action.ASSIGNED, now));

        return DispatchResponse.from(dispatch);
    }

    private void validateAssignment(EmergencyEvent event, Ambulance ambulance, Crew crew) {
        if (!ambulance.getServiceAreas().contains(event.getServiceArea())) {
            throw new BusinessException(ErrorCode.SERVICE_AREA_MISMATCH,
                    "车辆 " + ambulance.getPlateNumber() + " 不服务区域 " + event.getServiceArea());
        }
        if (crew.getDutyStatus() != DutyStatus.ON_DUTY) {
            throw new BusinessException(ErrorCode.CREW_OFF_DUTY,
                    "救护组 " + crew.getName() + " 当前不值勤");
        }
        if (!ambulance.getEquipmentCapabilities().containsAll(event.getRequiredCapabilities())
                || !crew.getQualifications().containsAll(event.getRequiredCapabilities())) {
            throw new BusinessException(ErrorCode.CAPABILITY_MISMATCH,
                    "车辆设备或人员资质不满足事件所需能力 " + event.getRequiredCapabilities());
        }
    }

    private void validateHospitalCanAccept(Hospital hospital, EmergencyType type) {
        if (hospital.getReceivingStatus() == ReceivingStatus.CLOSED) {
            throw new BusinessException(ErrorCode.HOSPITAL_NOT_RECEIVING,
                    "医院 " + hospital.getName() + " 已关闭接收");
        }
        if (!hospital.getAcceptedEmergencyTypes().contains(type)) {
            throw new BusinessException(ErrorCode.HOSPITAL_TYPE_UNSUPPORTED,
                    "医院 " + hospital.getName() + " 不接收急救类型 " + type);
        }
        if (hospital.getReservedCount() >= hospital.getBedCapacity()) {
            throw new BusinessException(ErrorCode.HOSPITAL_BED_UNAVAILABLE,
                    "医院 " + hospital.getName() + " 床位已满");
        }
    }

    // ------------------------------------------------------------------
    // 抢占（同时切换医院名额）
    // ------------------------------------------------------------------

    @Transactional
    public DispatchResponse preempt(PreemptRequest request) {
        String fp = fingerprint.preempt(request);
        Dispatch existing = dispatchRepository.findDetailedByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, fp);
        }

        EmergencyEvent newEvent = eventRepository.findWithLockById(request.newEventId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
        existing = dispatchRepository.findDetailedByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, fp);
        }
        if (newEvent.getStatus() == EventStatus.COMPLETED
                || newEvent.getStatus() == EventStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.EVENT_TERMINAL);
        }
        if (newEvent.getStatus() == EventStatus.DISPATCHED) {
            throw new BusinessException(ErrorCode.DISPATCH_ALREADY_ACTIVE);
        }

        Dispatch target = dispatchRepository.findWithLockById(request.targetDispatchId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPATCH_NOT_FOUND));
        Ambulance ambulance = ambulanceRepository.findById(request.ambulanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AMBULANCE_NOT_FOUND));
        Crew crew = crewRepository.findById(request.crewId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CREW_NOT_FOUND));

        if (target.getStatus() != DispatchStatus.EN_ROUTE) {
            if (target.getStatus() == DispatchStatus.ON_SCENE
                    || target.getStatus() == DispatchStatus.AT_HOSPITAL) {
                throw new BusinessException(ErrorCode.DISPATCH_ALREADY_ARRIVED,
                        "派遣已到达现场，不可抢占");
            }
            throw new BusinessException(ErrorCode.PREEMPTION_TARGET_NOT_ACTIVE);
        }
        if (!target.getAmbulance().getId().equals(ambulance.getId())
                || !target.getCrew().getId().equals(crew.getId())) {
            throw new BusinessException(ErrorCode.PREEMPTION_RESOURCE_MISMATCH);
        }

        EmergencyEvent oldEvent = eventRepository.findWithLockById(target.getEvent().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
        if (newEvent.getPriority().getLevel() <= oldEvent.getPriority().getLevel()) {
            throw new BusinessException(ErrorCode.PREEMPTION_NOT_HIGHER_PRIORITY,
                    "新事件优先级必须严格高于原事件优先级");
        }
        validateAssignment(newEvent, ambulance, crew);

        // 多医院一律按 id 升序加锁，避免反向改派/抢占交叉时形成 AB-BA 死锁
        HospitalPair hospitalLocks = lockHospitalPair(
                target.getDestinationHospital().getId(), request.hospitalId());
        Hospital oldHospital = hospitalLocks.from();
        Hospital newHospital = hospitalLocks.to();

        Instant now = clock.instant();
        HospitalReservation oldReservation =
                reservationRepository.findByDispatchIdAndStatus(target.getId(), ReservationStatus.HELD)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        // 医院名额随抢占一起原子切换；任一步失败全部回滚，原派遣与原名额不动。
        if (newHospital.getId().equals(oldHospital.getId())) {
            // 同院：原名额随原单释放、新名额立即占用，净占用不变，不会把自己挤掉
            if (!oldHospital.releaseOneBed()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "原医院名额状态异常");
            }
            // 同院也必须仍开放、仍接收新事件类型且（释放后）有空床
            validateHospitalCanAccept(oldHospital, newEvent.getEmergencyType());
            oldHospital.holdOneBed();
        } else {
            // 跨院：先占用新院名额，成功后才释放原院名额
            validateHospitalCanAccept(newHospital, newEvent.getEmergencyType());
            newHospital.holdOneBed();
            if (!oldHospital.releaseOneBed()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "原医院名额状态异常");
            }
        }

        target.setStatus(DispatchStatus.PREEMPTED);
        target.setFinishedAt(now);
        releaseReservationEntity(oldReservation, now);
        oldEvent.setStatus(EventStatus.PENDING);
        newEvent.setStatus(EventStatus.DISPATCHED);

        Dispatch next = new Dispatch(request.bizNo(), fp, newEvent, ambulance, crew, newHospital,
                target.getPreemptionRoot(), target, now);
        try {
            dispatchRepository.save(next);
            dispatchRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号已被使用且内容不一致: " + request.bizNo());
        }
        reservationRepository.save(new HospitalReservation(
                newHospital, next, newEvent, newEvent.getEmergencyType(), now));

        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.AMBULANCE, ambulance.getId(), null,
                target, ResourceTimelineEntry.Action.PREEMPTED, now));
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.CREW, null, crew.getId(),
                target, ResourceTimelineEntry.Action.PREEMPTED, now));
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.AMBULANCE, ambulance.getId(), null,
                next, ResourceTimelineEntry.Action.ASSIGNED, now));
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.CREW, null, crew.getId(),
                next, ResourceTimelineEntry.Action.ASSIGNED, now));

        return DispatchResponse.from(next);
    }

    // ------------------------------------------------------------------
    // 生命周期：到达现场 / 到达医院 / 完成 / 取消
    // ------------------------------------------------------------------

    @Transactional
    public DispatchResponse arrive(Long dispatchId) {
        Dispatch dispatch = lockActive(dispatchId);
        if (dispatch.getStatus() != DispatchStatus.EN_ROUTE) {
            throw new BusinessException(ErrorCode.DISPATCH_ALREADY_ARRIVED);
        }
        Instant now = clock.instant();
        dispatch.setStatus(DispatchStatus.ON_SCENE);
        dispatch.setArrivedAt(now);
        saveTimelinePair(dispatch, ResourceTimelineEntry.Action.ARRIVED, now);
        return DispatchResponse.from(dispatch);
    }

    /**
     * 到达目的医院并完成交接。此后状态为 AT_HOSPITAL，不可再改派。
     * 必须先到达现场（ON_SCENE），且必须持有有效医院名额——医院拒收后须先成功改派，
     * 不能向已拒收（无名额）的医院交接。
     */
    @Transactional
    public DispatchResponse arriveHospital(Long dispatchId) {
        Dispatch dispatch = lockActive(dispatchId);
        if (dispatch.getStatus() != DispatchStatus.ON_SCENE) {
            throw new BusinessException(ErrorCode.DISPATCH_IMMUTABLE, "尚未到达现场，不能到达医院");
        }
        if (reservationRepository.findByDispatchIdAndStatus(
                dispatchId, ReservationStatus.HELD).isEmpty()) {
            throw new BusinessException(ErrorCode.DIVERSION_NOT_ALLOWED,
                    "当前目的医院未持有有效名额（可能已拒收），须先成功改派再到达医院");
        }
        Instant now = clock.instant();
        dispatch.setStatus(DispatchStatus.AT_HOSPITAL);
        dispatch.setArrivedHospitalAt(now);
        saveTimelinePair(dispatch, ResourceTimelineEntry.Action.ARRIVED_HOSPITAL, now);
        return DispatchResponse.from(dispatch);
    }

    @Transactional
    public DispatchResponse complete(Long dispatchId) {
        Dispatch dispatch = lockActive(dispatchId);
        if (dispatch.getStatus() != DispatchStatus.ON_SCENE
                && dispatch.getStatus() != DispatchStatus.AT_HOSPITAL) {
            throw new BusinessException(ErrorCode.DISPATCH_IMMUTABLE, "尚未到达现场，不能完成");
        }
        return finish(dispatch, DispatchStatus.COMPLETED, EventStatus.COMPLETED);
    }

    @Transactional
    public DispatchResponse cancel(Long dispatchId) {
        Dispatch dispatch = lockActive(dispatchId);
        return finish(dispatch, DispatchStatus.CANCELLED, EventStatus.CANCELLED);
    }

    private DispatchResponse finish(Dispatch dispatch, DispatchStatus dispatchStatus, EventStatus eventStatus) {
        Instant now = clock.instant();
        dispatch.setStatus(dispatchStatus);
        dispatch.setFinishedAt(now);
        dispatch.getEvent().setStatus(eventStatus);
        ambulanceRepository.releaseIfDispatched(dispatch.getAmbulance().getId());
        crewRepository.releaseIfAssigned(dispatch.getCrew().getId());
        releaseHeldReservation(dispatch, now);
        // 派遣终态后不可能再改派：关闭仍待处理的改派任务，避免遗留永久 PENDING 的脏任务
        diversionTaskRepository
                .findByDispatchIdAndStatus(dispatch.getId(), DiversionTaskStatus.PENDING)
                .forEach(t -> {
                    t.setStatus(DiversionTaskStatus.CLOSED);
                    t.setFulfilledAt(now);
                });
        saveTimelinePair(dispatch, ResourceTimelineEntry.Action.RELEASED, now);
        return DispatchResponse.from(dispatch);
    }

    private Dispatch lockActive(Long dispatchId) {
        Dispatch dispatch = dispatchRepository.findWithLockById(dispatchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPATCH_NOT_FOUND));
        if (dispatch.getStatus() == DispatchStatus.COMPLETED
                || dispatch.getStatus() == DispatchStatus.CANCELLED
                || dispatch.getStatus() == DispatchStatus.PREEMPTED) {
            throw new BusinessException(ErrorCode.DISPATCH_TERMINAL);
        }
        return dispatch;
    }

    private void saveTimelinePair(Dispatch dispatch, ResourceTimelineEntry.Action action, Instant at) {
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.AMBULANCE,
                dispatch.getAmbulance().getId(), null, dispatch, action, at));
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.CREW,
                null, dispatch.getCrew().getId(), dispatch, action, at));
    }

    // ------------------------------------------------------------------
    // 医院拒收：记录原因 + 生成改派任务，不释放车/组
    // ------------------------------------------------------------------

    /**
     * 目的医院拒收。要求派遣已到达现场且尚未到达医院、名额仍占用。
     * 原子地：名额状态置 REJECTED 并归还床位、写入拒收原因、生成 PENDING 改派任务。
     * 车辆与救护组保持占用，派遣目的地保持不变。
     */
    @Transactional
    public RejectionResponse reject(Long dispatchId, String reason) {
        Dispatch dispatch = lockActive(dispatchId);
        if (dispatch.getStatus() != DispatchStatus.ON_SCENE) {
            throw new BusinessException(ErrorCode.REJECTION_NOT_ALLOWED,
                    "仅已到达现场且未到达医院的派遣可被拒收");
        }
        HospitalReservation reservation = reservationRepository
                .findByDispatchIdAndStatus(dispatchId, ReservationStatus.HELD)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "当前没有占用中的医院名额，可能已被拒收或改派"));

        Instant now = clock.instant();
        Hospital hospital = hospitalRepository.findWithLockById(reservation.getHospital().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.HOSPITAL_NOT_FOUND));
        hospital.releaseOneBed();
        releaseReservationEntity(reservation, ReservationStatus.REJECTED, now);

        HospitalRejection rejection = new HospitalRejection(
                hospital, dispatch, dispatch.getEvent(), reservation, reason, now);
        rejectionRepository.save(rejection);

        // 已存在待处理任务则复用，避免一次拒收重复生成
        DiversionTask task = diversionTaskRepository
                .findFirstByDispatchIdAndStatusOrderByIdAsc(
                        dispatchId, DiversionTaskStatus.PENDING)
                .orElseGet(() -> diversionTaskRepository.save(new DiversionTask(
                        dispatch, dispatch.getEvent(), hospital, rejection,
                        DiversionReason.REJECTED, now)));

        return RejectionResponse.of(rejection, task.getId());
    }

    // ------------------------------------------------------------------
    // 途中改派：新院预留成功后才释放原名额
    // ------------------------------------------------------------------

    /**
     * 幂等改派。同业务号同内容返回首次结果（含失败结果），同业务号异内容 409。
     *
     * <p>只有 ON_SCENE（到达现场后、到达医院前）的派遣可改派；已到达医院一律拒绝。
     * 新医院名额完整预留成功后才释放原名额并切换目的地；新医院不可用时记录一次失败，
     * 原目的地与派遣保持不变。{@code expectedHospitalId} 守卫防止旧请求覆盖后来确认的目的地。
     */
    @Transactional
    public DiversionResponse divert(DiversionRequest request) {
        String fp = fingerprint.diversion(request);
        DiversionRecord existing = diversionRepository.findByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayOrConflictDiversion(existing, fp);
        }

        Dispatch dispatch = dispatchRepository.findWithLockById(request.dispatchId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPATCH_NOT_FOUND));
        existing = diversionRepository.findByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayOrConflictDiversion(existing, fp);
        }

        // 已到达医院不能再改派；终态同样不允许
        if (dispatch.getStatus() == DispatchStatus.AT_HOSPITAL) {
            throw new BusinessException(ErrorCode.DIVERSION_ALREADY_ARRIVED);
        }
        if (dispatch.getStatus() != DispatchStatus.ON_SCENE) {
            throw new BusinessException(ErrorCode.DIVERSION_NOT_ALLOWED,
                    "只有到达现场后、到达医院前的派遣可以改派");
        }

        // 多医院一律按 id 升序加锁，避免反向改派交叉形成 AB-BA 死锁。
        // 锁后再按角色区分 from/to，守卫判断也在锁后完成。
        HospitalPair hospitals = lockHospitalPair(
                dispatch.getDestinationHospital().getId(), request.toHospitalId());
        Hospital fromHospital = hospitals.from();
        Hospital toHospital = hospitals.to();

        // 旧改派请求不得覆盖后来确认的目的地
        if (request.expectedHospitalId() != null
                && !request.expectedHospitalId().equals(fromHospital.getId())) {
            throw new BusinessException(ErrorCode.DIVERSION_DESTINATION_CONFLICT,
                    "期望的原目的地 " + request.expectedHospitalId()
                            + " 与当前目的地 " + fromHospital.getId() + " 不一致");
        }
        if (request.toHospitalId().equals(fromHospital.getId())) {
            throw new BusinessException(ErrorCode.DIVERSION_NOT_ALLOWED, "改派目标不能与当前目的地相同");
        }

        EmergencyType type = request.emergencyType() != null
                ? request.emergencyType() : dispatch.getEvent().getEmergencyType();
        Instant now = clock.instant();

        if (!toHospital.canAccept(type)) {
            // 新医院预留失败：不改任何状态，只留痕一次失败，原目的地与派遣保持不变
            String failMessage = diversionFailMessage(toHospital, type);
            DiversionRecord failed = saveDiversion(request, fp, dispatch, fromHospital, toHospital,
                    type, DiversionStatus.FAILED, failMessage, now);
            return DiversionResponse.from(failed);
        }

        // 新医院名额完整预留成功后，才处理原医院名额。
        // 正常改派时原名额仍 HELD，需释放；医院拒收后名额已归还（REJECTED），不能重复释放。
        java.util.Optional<HospitalReservation> oldReservation = reservationRepository
                .findByDispatchIdAndStatus(dispatch.getId(), ReservationStatus.HELD);
        toHospital.holdOneBed();
        if (oldReservation.isPresent()) {
            if (!fromHospital.releaseOneBed()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "原医院名额状态异常");
            }
            releaseReservationEntity(oldReservation.get(), now);
        }

        dispatch.setDestinationHospital(toHospital);
        reservationRepository.save(new HospitalReservation(
                toHospital, dispatch, dispatch.getEvent(), type, now));

        DiversionRecord success = saveDiversion(request, fp, dispatch, fromHospital, toHospital,
                type, DiversionStatus.SUCCESS, null, now);

        // 关联的待处理改派任务（拒收生成）随改派成功关闭
        diversionTaskRepository
                .findFirstByDispatchIdAndStatusOrderByIdAsc(
                        dispatch.getId(), DiversionTaskStatus.PENDING)
                .ifPresent(t -> {
                    t.setStatus(DiversionTaskStatus.FULFILLED);
                    t.setFulfilledAt(now);
                });

        return DiversionResponse.from(success);
    }

    private String diversionFailMessage(Hospital hospital, EmergencyType type) {
        if (hospital.getReceivingStatus() == ReceivingStatus.CLOSED) {
            return "医院 " + hospital.getName() + " 已关闭接收";
        }
        if (!hospital.getAcceptedEmergencyTypes().contains(type)) {
            return "医院 " + hospital.getName() + " 不接收急救类型 " + type;
        }
        return "医院 " + hospital.getName() + " 床位已满";
    }

    private DiversionRecord saveDiversion(DiversionRequest request, String fp, Dispatch dispatch,
                                          Hospital fromHospital, Hospital toHospital,
                                          EmergencyType type, DiversionStatus status,
                                          String failMessage, Instant now) {
        DiversionRecord record = new DiversionRecord(
                request.bizNo(), fp, dispatch, dispatch.getEvent(), fromHospital, toHospital,
                request.reason(), type, status, failMessage, now);
        try {
            diversionRepository.save(record);
            diversionRepository.flush();
            return record;
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号已被使用且内容不一致: " + request.bizNo());
        }
    }

    private void releaseHeldReservation(Dispatch dispatch, Instant now) {
        reservationRepository.findByDispatchIdAndStatus(dispatch.getId(), ReservationStatus.HELD)
                .ifPresent(reservation -> {
                    Hospital hospital = hospitalRepository.findWithLockById(
                            reservation.getHospital().getId()).orElseThrow();
                    hospital.releaseOneBed();
                    releaseReservationEntity(reservation, now);
                });
    }

    private void releaseReservationEntity(HospitalReservation reservation, Instant now) {
        releaseReservationEntity(reservation, ReservationStatus.RELEASED, now);
    }

    private void releaseReservationEntity(HospitalReservation reservation,
                                          ReservationStatus status, Instant now) {
        reservation.setStatus(status);
        reservation.setReleasedAt(now);
    }

    /** 一对加了行锁的医院，保留 from/to 业务角色。 */
    private record HospitalPair(Hospital from, Hospital to) {
    }

    /**
     * 按医院 id 升序对两家医院加悲观写锁（同一家只锁一次），消除多医院事务的 AB-BA 死锁。
     * 返回时仍按业务角色（from=原医院，to=目标医院）暴露。
     */
    private HospitalPair lockHospitalPair(Long fromId, Long toId) {
        Long firstId = Math.min(fromId, toId);
        Hospital first = hospitalRepository.findWithLockById(firstId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HOSPITAL_NOT_FOUND));
        Hospital second;
        if (toId.equals(fromId)) {
            second = first;
        } else {
            Long secondId = Math.max(fromId, toId);
            second = hospitalRepository.findWithLockById(secondId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.HOSPITAL_NOT_FOUND));
        }
        Hospital from = first.getId().equals(fromId) ? first : second;
        Hospital to = first.getId().equals(toId) ? first : second;
        return new HospitalPair(from, to);
    }

    private DispatchResponse replayOrConflict(Dispatch existing, String fp) {
        if (!existing.getRequestFingerprint().equals(fp)) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号 " + existing.getBizNo() + " 已存在但请求内容不同");
        }
        return DispatchResponse.from(existing, true);
    }

    private DiversionResponse replayOrConflictDiversion(DiversionRecord existing, String fp) {
        if (!existing.getRequestFingerprint().equals(fp)) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号 " + existing.getBizNo() + " 已存在但请求内容不同");
        }
        return DiversionResponse.from(existing, true);
    }

    // ------------------------------------------------------------------
    // 查询：派遣详情 / 事件完整详情 / 时间线 / 抢占链 / 改派任务
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DispatchResponse getDispatch(Long dispatchId) {
        return DispatchResponse.from(dispatchRepository.findDetailedById(dispatchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPATCH_NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public EventDispatchDetailResponse getEventDispatchDetail(Long eventId) {
        EmergencyEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
        List<Dispatch> history = dispatchRepository.findByEventIdOrderByDispatchedAtDesc(eventId);
        DispatchResponse current = history.stream()
                .filter(d -> ACTIVE_STATUSES.contains(d.getStatus()))
                .findFirst()
                .map(DispatchResponse::from)
                .orElse(null);
        List<DispatchResponse> dispatches = history.stream()
                .map(DispatchResponse::from)
                .toList();

        List<ReservationResponse> reservations = reservationRepository
                .findByEventIdOrderByReservedAtAscIdAsc(eventId).stream()
                .map(ReservationResponse::from)
                .toList();
        List<DiversionResponse> diversions = diversionRepository
                .findByEventIdOrderByCreatedAtAscIdAsc(eventId).stream()
                .map(DiversionResponse::from)
                .toList();
        List<DiversionTask> taskEntities =
                diversionTaskRepository.findByEventIdOrderByCreatedAtAscIdAsc(eventId);
        List<DiversionTaskResponse> tasks = taskEntities.stream()
                .map(DiversionTaskResponse::from)
                .toList();
        java.util.Map<Long, Long> rejectionTaskMap = taskEntities.stream()
                .filter(t -> t.getRejection() != null)
                .collect(java.util.stream.Collectors.toMap(
                        t -> t.getRejection().getId(), DiversionTask::getId, (a, b) -> a));
        List<RejectionResponse> rejections = rejectionRepository
                .findByEventIdOrderByRejectedAtAscIdAsc(eventId).stream()
                .map(r -> RejectionResponse.of(r, rejectionTaskMap.get(r.getId())))
                .toList();
        List<EventTimelineEntryResponse> timeline = buildEventTimeline(
                history, reservations, diversions, rejections);

        return new EventDispatchDetailResponse(EventResponse.from(event), current, dispatches,
                reservations, diversions, rejections, tasks, timeline);
    }

    private List<EventTimelineEntryResponse> buildEventTimeline(
            List<Dispatch> dispatches,
            List<ReservationResponse> reservations,
            List<DiversionResponse> diversions,
            List<RejectionResponse> rejections) {
        List<EventTimelineEntryResponse> entries = new ArrayList<>();

        for (Dispatch d : dispatches) {
            Long did = d.getId();
            Long hid = d.getDestinationHospital().getId();
            entries.add(new EventTimelineEntryResponse(
                    d.getDispatchedAt(), "DISPATCHED", did, hid,
                    "派遣车辆 " + d.getAmbulance().getId() + " 救护组 " + d.getCrew().getId()
                            + " 至医院 " + hid));
            if (d.getArrivedAt() != null) {
                entries.add(new EventTimelineEntryResponse(
                        d.getArrivedAt(), "ARRIVED_SCENE", did, hid, "到达现场"));
            }
            if (d.getArrivedHospitalAt() != null) {
                entries.add(new EventTimelineEntryResponse(
                        d.getArrivedHospitalAt(), "ARRIVED_HOSPITAL", did, hid, "到达医院"));
            }
            if (d.getStatus() == DispatchStatus.PREEMPTED) {
                entries.add(new EventTimelineEntryResponse(
                        d.getFinishedAt(), "PREEMPTED", did, hid, "被高优先级事件抢占"));
            } else if (d.getStatus() == DispatchStatus.COMPLETED
                    || d.getStatus() == DispatchStatus.CANCELLED) {
                entries.add(new EventTimelineEntryResponse(
                        d.getFinishedAt(), d.getStatus().name(), did, hid,
                        d.getStatus() == DispatchStatus.COMPLETED ? "任务完成" : "任务取消"));
            }
        }
        for (ReservationResponse r : reservations) {
            entries.add(new EventTimelineEntryResponse(
                    r.reservedAt(), "RESERVED", r.dispatchId(), r.hospitalId(),
                    "医院 " + r.hospitalName() + " 预留名额(" + r.emergencyType() + ")"));
            if (r.status() != ReservationStatus.HELD.name()) {
                String type = r.status() == ReservationStatus.REJECTED.name()
                        ? "RESERVATION_REJECTED" : "RESERVATION_RELEASED";
                entries.add(new EventTimelineEntryResponse(
                        r.releasedAt(), type, r.dispatchId(), r.hospitalId(),
                        "医院 " + r.hospitalName() + " 名额"
                                + (r.status() == ReservationStatus.REJECTED.name() ? "被拒收" : "释放")));
            }
        }
        for (RejectionResponse r : rejections) {
            entries.add(new EventTimelineEntryResponse(
                    r.rejectedAt(), "REJECTED", r.dispatchId(), r.hospitalId(),
                    "医院拒收: " + r.reason()));
        }
        for (DiversionResponse d : diversions) {
            if (d.status() == DiversionStatus.SUCCESS.name()) {
                entries.add(new EventTimelineEntryResponse(
                        d.createdAt(), "DIVERTED", d.dispatchId(), d.toHospitalId(),
                        "改派: 医院 " + d.fromHospitalId() + " -> " + d.toHospitalId()
                                + " (" + d.reason() + ")"));
            } else {
                entries.add(new EventTimelineEntryResponse(
                        d.createdAt(), "DIVERSION_FAILED", d.dispatchId(), d.toHospitalId(),
                        "改派失败: " + d.failMessage()));
            }
        }

        entries.sort(java.util.Comparator
                .comparing(EventTimelineEntryResponse::at)
                .thenComparing(EventTimelineEntryResponse::type));
        return entries;
    }

    @Transactional(readOnly = true)
    public List<TimelineEntryResponse> getAmbulanceTimeline(Long ambulanceId) {
        if (!ambulanceRepository.existsById(ambulanceId)) {
            throw new BusinessException(ErrorCode.AMBULANCE_NOT_FOUND);
        }
        return timelineRepository.findByAmbulanceIdOrderByOccurredAtAscIdAsc(ambulanceId).stream()
                .map(this::toTimelineResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TimelineEntryResponse> getCrewTimeline(Long crewId) {
        if (!crewRepository.existsById(crewId)) {
            throw new BusinessException(ErrorCode.CREW_NOT_FOUND);
        }
        return timelineRepository.findByCrewIdOrderByOccurredAtAscIdAsc(crewId).stream()
                .map(this::toTimelineResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PreemptionChainItemResponse> getPreemptionChain(Long dispatchId) {
        Dispatch dispatch = dispatchRepository.findDetailedById(dispatchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPATCH_NOT_FOUND));
        Long rootId = dispatch.getPreemptionRoot().getId();
        return dispatchRepository.findByPreemptionRootIdOrderByDispatchedAtAsc(rootId).stream()
                .map(d -> new PreemptionChainItemResponse(
                        d.getId(),
                        d.getBizNo(),
                        d.getEvent().getId(),
                        d.getEvent().getPriority().name(),
                        d.getAmbulance().getId(),
                        d.getCrew().getId(),
                        d.getDestinationHospital().getId(),
                        d.getStatus().name(),
                        d.getPreemptedDispatch() == null ? null : d.getPreemptedDispatch().getId(),
                        d.getDispatchedAt()))
                .toList();
    }

    /** 待处理改派任务列表（医院拒收后生成）。 */
    @Transactional(readOnly = true)
    public List<DiversionTaskResponse> listPendingDiversionTasks() {
        return diversionTaskRepository
                .findByStatusOrderByCreatedAtAscIdAsc(DiversionTaskStatus.PENDING).stream()
                .map(DiversionTaskResponse::from)
                .toList();
    }

    private TimelineEntryResponse toTimelineResponse(ResourceTimelineEntry e) {
        Long ambulanceId = e.getResourceType() == ResourceTimelineEntry.ResourceType.AMBULANCE
                ? e.getAmbulanceId() : null;
        Long crewId = e.getResourceType() == ResourceTimelineEntry.ResourceType.CREW
                ? e.getCrewId() : null;
        return new TimelineEntryResponse(
                e.getId(),
                e.getResourceType().name(),
                e.getResourceType() == ResourceTimelineEntry.ResourceType.AMBULANCE
                        ? ambulanceId : crewId,
                e.getDispatch().getId(),
                String.valueOf(e.getDispatch().getEvent().getId()),
                e.getAction().name(),
                e.getOccurredAt());
    }
}
