package com.chris64233.ambulancedispatch.service;

import com.chris64233.ambulancedispatch.domain.Ambulance;
import com.chris64233.ambulancedispatch.domain.Crew;
import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.domain.DispatchStatus;
import com.chris64233.ambulancedispatch.domain.Diversion;
import com.chris64233.ambulancedispatch.domain.DiversionReason;
import com.chris64233.ambulancedispatch.domain.DiversionStatus;
import com.chris64233.ambulancedispatch.domain.DutyStatus;
import com.chris64233.ambulancedispatch.domain.EmergencyEvent;
import com.chris64233.ambulancedispatch.domain.EventStatus;
import com.chris64233.ambulancedispatch.domain.Hospital;
import com.chris64233.ambulancedispatch.domain.ReceivingStatus;
import com.chris64233.ambulancedispatch.domain.ResourceTimelineEntry;
import com.chris64233.ambulancedispatch.dto.DivertRequest;
import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.dto.DispatchResponse;
import com.chris64233.ambulancedispatch.dto.DiversionResponse;
import com.chris64233.ambulancedispatch.dto.EventDispatchDetailResponse;
import com.chris64233.ambulancedispatch.dto.EventResponse;
import com.chris64233.ambulancedispatch.dto.HospitalResponse;
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
import com.chris64233.ambulancedispatch.repository.ResourceTimelineEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 联合派遣核心服务：车辆 + 救护组 + 目的医院名额三者在单事务内原子预留。
 *
 * <p>并发安全：事件/派遣/医院行使用悲观行锁串行化；车辆、救护组占用与医院床位预留
 * 使用条件更新（CAS）。任一资源不足整个事务回滚，不会出现部分派遣，
 * 医院 reservedBeds 永远满足 {@code 0 <= reservedBeds <= bedCapacity}。
 *
 * <p>改派只允许在到达现场后、到达医院前发起；新医院名额预留成功后才释放原医院名额，
 * 失败时原目的地与派遣保持不变。旧改派请求携带的原目的地与当前目的地不一致时拒绝，
 * 防止过期请求覆盖后来确认的目的地。
 */
@Service
public class DispatchService {

    private static final List<DispatchStatus> ACTIVE_STATUSES =
            List.of(DispatchStatus.EN_ROUTE, DispatchStatus.ON_SCENE);

    private final DispatchRepository dispatchRepository;
    private final EmergencyEventRepository eventRepository;
    private final AmbulanceRepository ambulanceRepository;
    private final CrewRepository crewRepository;
    private final HospitalRepository hospitalRepository;
    private final DiversionRepository diversionRepository;
    private final ResourceTimelineEntryRepository timelineRepository;
    private final RequestFingerprint fingerprint;
    private final DiversionFailureRecorder failureRecorder;
    private final Clock clock;

    public DispatchService(DispatchRepository dispatchRepository,
                           EmergencyEventRepository eventRepository,
                           AmbulanceRepository ambulanceRepository,
                           CrewRepository crewRepository,
                           HospitalRepository hospitalRepository,
                           DiversionRepository diversionRepository,
                           ResourceTimelineEntryRepository timelineRepository,
                           RequestFingerprint fingerprint,
                           DiversionFailureRecorder failureRecorder,
                           Clock clock) {
        this.dispatchRepository = dispatchRepository;
        this.eventRepository = eventRepository;
        this.ambulanceRepository = ambulanceRepository;
        this.crewRepository = crewRepository;
        this.hospitalRepository = hospitalRepository;
        this.diversionRepository = diversionRepository;
        this.timelineRepository = timelineRepository;
        this.fingerprint = fingerprint;
        this.failureRecorder = failureRecorder;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 联合派遣（车 + 组 + 医院名额原子预留）
    // ------------------------------------------------------------------

    /**
     * 幂等派遣。相同业务号 + 相同内容：返回原结果（replayed=true）；
     * 相同业务号 + 不同内容：409 冲突。车、组、医院名额任一不足，整体回滚。
     */
    @Transactional
    public DispatchResponse dispatch(DispatchRequest request) {
        String fp = fingerprint.dispatch(request);
        Dispatch existing = dispatchRepository.findDetailedByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, fp);
        }

        // 锁事件行：同一事件的并发派遣在此串行
        EmergencyEvent event = eventRepository.findWithLockById(request.eventId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));

        // 双重幂等检查（持锁后）
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

        // 固定 id 顺序加资源行锁，避免与改派/医院上报交叉时死锁
        Ambulance ambulance = ambulanceRepository.findWithLockById(request.ambulanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AMBULANCE_NOT_FOUND));
        Crew crew = crewRepository.findWithLockById(request.crewId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CREW_NOT_FOUND));
        Hospital hospital = hospitalRepository.findWithLockById(request.hospitalId())
                .orElseThrow(() -> new BusinessException(ErrorCode.HOSPITAL_NOT_FOUND));
        validateAssignment(event, ambulance, crew, hospital);

        // 三类资源条件更新原子占用；任一失败抛异常回滚，前序占用一并撤销，杜绝部分派遣
        if (ambulanceRepository.occupyIfAvailable(ambulance.getId()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_UNAVAILABLE, "车辆已被占用");
        }
        if (crewRepository.occupyIfIdleAndOnDuty(crew.getId()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_UNAVAILABLE, "救护组已被占用或不值勤");
        }
        if (hospitalRepository.reserveBedIfOpen(hospital.getId()) != 1) {
            // 关闭接收或无空余床位：异常导致事务回滚，车/组占用同步撤销
            throw reserveFailure(hospital);
        }

        event.setStatus(EventStatus.DISPATCHED);
        Instant now = clock.instant();
        Dispatch dispatch = new Dispatch(request.bizNo(), fp, event, ambulance, crew, hospital,
                null, null, now);
        try {
            dispatchRepository.save(dispatch);
            dispatchRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 业务号唯一约束兜底跨事件的并发提交
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号已被使用且内容不一致: " + request.bizNo());
        }
        // 抢占链根初始指向自身
        dispatch.setPreemptionRoot(dispatch);
        dispatchRepository.save(dispatch);

        saveAssignedTimelines(dispatch, now);
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.HOSPITAL, null, null, hospital.getId(),
                dispatch, ResourceTimelineEntry.Action.RESERVED, null, now));

        return DispatchResponse.from(dispatch);
    }

    private void validateAssignment(EmergencyEvent event, Ambulance ambulance, Crew crew,
                                    Hospital hospital) {
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
        if (hospital.getReceivingStatus() != ReceivingStatus.OPEN) {
            throw new BusinessException(ErrorCode.HOSPITAL_CLOSED,
                    "医院 " + hospital.getName() + " 已关闭接收");
        }
        if (!hospital.getAcceptedEmergencyTypes().contains(event.getEmergencyType())) {
            throw new BusinessException(ErrorCode.HOSPITAL_TYPE_NOT_ACCEPTED,
                    "医院 " + hospital.getName() + " 不接收急救类型 " + event.getEmergencyType());
        }
    }

    private BusinessException reserveFailure(Hospital hospital) {
        if (hospital.getReceivingStatus() != ReceivingStatus.OPEN) {
            return new BusinessException(ErrorCode.HOSPITAL_CLOSED,
                    "医院 " + hospital.getName() + " 已关闭接收");
        }
        return new BusinessException(ErrorCode.HOSPITAL_NO_CAPACITY,
                "医院 " + hospital.getName() + " 床位已满，无法预留接收名额");
    }

    // ------------------------------------------------------------------
    // 抢占（同步预留新目的医院名额，原医院名额随原单结束释放）
    // ------------------------------------------------------------------

    /**
     * 幂等抢占。校验、资源切换与医院名额切换在同一事务内，任一步失败全部回滚、原派遣不变。
     */
    @Transactional
    public DispatchResponse preempt(PreemptRequest request) {
        String fp = fingerprint.preempt(request);
        Dispatch existing = dispatchRepository.findDetailedByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, fp);
        }

        // 1. 锁新高优先级事件
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

        // 2. 锁目标派遣（在途才可抢占）
        Dispatch target = dispatchRepository.findWithLockById(request.targetDispatchId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPATCH_NOT_FOUND));
        Ambulance ambulance = ambulanceRepository.findById(request.ambulanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AMBULANCE_NOT_FOUND));
        Crew crew = crewRepository.findById(request.crewId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CREW_NOT_FOUND));
        Long oldHospitalId = target.getHospital().getId();

        if (target.getStatus() != DispatchStatus.EN_ROUTE) {
            if (target.getStatus() == DispatchStatus.ON_SCENE) {
                throw new BusinessException(ErrorCode.DISPATCH_ALREADY_ARRIVED,
                        "派遣已到达现场，不可抢占");
            }
            throw new BusinessException(ErrorCode.PREEMPTION_TARGET_NOT_ACTIVE);
        }
        if (!target.getAmbulance().getId().equals(ambulance.getId())
                || !target.getCrew().getId().equals(crew.getId())) {
            throw new BusinessException(ErrorCode.PREEMPTION_RESOURCE_MISMATCH);
        }

        // 3. 锁原事件并校验优先级
        EmergencyEvent oldEvent = eventRepository.findWithLockById(target.getEvent().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
        if (newEvent.getPriority().getLevel() <= oldEvent.getPriority().getLevel()) {
            throw new BusinessException(ErrorCode.PREEMPTION_NOT_HIGHER_PRIORITY,
                    "新事件优先级必须严格高于原事件优先级");
        }

        // 新旧医院按 id 全局顺序加行锁，避免并发抢占/改派交叉时的锁顺序死锁
        List<Hospital> hospitals = lockHospitalsOrdered(oldHospitalId, request.hospitalId());
        Hospital oldHospital = hospitals.stream()
                .filter(h -> h.getId().equals(oldHospitalId)).findFirst().orElseThrow();
        Hospital hospital = hospitals.stream()
                .filter(h -> h.getId().equals(request.hospitalId())).findFirst().orElseThrow();
        // 资源与医院对新事件仍需满足区域/能力/急救类型要求，否则安全失败、原派遣不动
        validateAssignment(newEvent, ambulance, crew, hospital);

        // 4. 新医院先预留名额；失败则整体回滚，原派遣与原医院名额不变
        if (hospitalRepository.reserveBedIfOpen(hospital.getId()) != 1) {
            throw reserveFailure(hospital);
        }

        // 5. 以下全部在同一事务内原子切换；任何异常都会回滚到抢占前状态
        Instant now = clock.instant();
        target.setStatus(DispatchStatus.PREEMPTED);
        target.setFinishedAt(now);
        // 原医院名额随原单结束释放（资源不经过空闲窗口，仅转移属主）
        hospitalRepository.releaseBed(oldHospital.getId());
        oldEvent.setStatus(EventStatus.PENDING);
        newEvent.setStatus(EventStatus.DISPATCHED);

        Dispatch next = new Dispatch(request.bizNo(), fp, newEvent, ambulance, crew, hospital,
                target.getPreemptionRoot(), target, now);
        try {
            dispatchRepository.save(next);
            dispatchRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号已被使用且内容不一致: " + request.bizNo());
        }

        // 车/组状态保持占用（DISPATCHED / ASSIGNED），只转移属主，不产生空闲窗口
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.AMBULANCE, ambulance.getId(), null,
                target, ResourceTimelineEntry.Action.PREEMPTED, now));
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.CREW, null, crew.getId(),
                target, ResourceTimelineEntry.Action.PREEMPTED, now));
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.HOSPITAL, null, null, oldHospital.getId(),
                target, ResourceTimelineEntry.Action.RESERVATION_RELEASED,
                "抢占释放原医院名额", now));
        saveAssignedTimelines(next, now);
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.HOSPITAL, null, null, hospital.getId(),
                next, ResourceTimelineEntry.Action.RESERVED, null, now));

        return DispatchResponse.from(next);
    }

    private void saveAssignedTimelines(Dispatch dispatch, Instant at) {
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.AMBULANCE, dispatch.getAmbulance().getId(), null,
                dispatch, ResourceTimelineEntry.Action.ASSIGNED, at));
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.CREW, null, dispatch.getCrew().getId(),
                dispatch, ResourceTimelineEntry.Action.ASSIGNED, at));
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
     * 到达目的医院：记录到院时刻，此后不可再改派。要求先到达现场。
     */
    @Transactional
    public DispatchResponse arriveAtHospital(Long dispatchId) {
        Dispatch dispatch = lockActive(dispatchId);
        if (dispatch.getStatus() != DispatchStatus.ON_SCENE) {
            throw new BusinessException(ErrorCode.DISPATCH_IMMUTABLE, "尚未到达现场，不能到达医院");
        }
        Instant now = clock.instant();
        dispatch.setArrivedAtHospitalAt(now);
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.HOSPITAL, null, null,
                dispatch.getHospital().getId(), dispatch,
                ResourceTimelineEntry.Action.ARRIVED_HOSPITAL, null, now));
        return DispatchResponse.from(dispatch);
    }

    @Transactional
    public DispatchResponse complete(Long dispatchId) {
        Dispatch dispatch = lockActive(dispatchId);
        if (dispatch.getStatus() != DispatchStatus.ON_SCENE) {
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
        EmergencyEvent event = dispatch.getEvent();
        event.setStatus(eventStatus);
        ambulanceRepository.releaseIfDispatched(dispatch.getAmbulance().getId());
        crewRepository.releaseIfAssigned(dispatch.getCrew().getId());
        hospitalRepository.releaseBed(dispatch.getHospital().getId());
        saveTimelinePair(dispatch, ResourceTimelineEntry.Action.RELEASED, now);
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.HOSPITAL, null, null,
                dispatch.getHospital().getId(), dispatch,
                ResourceTimelineEntry.Action.RESERVATION_RELEASED, null, now));
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
    // 途中改派
    // ------------------------------------------------------------------

    /**
     * 幂等改派。救护车到达现场（ON_SCENE）后、到达医院前可因医院关闭或病情变化申请。
     * 新医院名额预留成功后才释放原医院名额并切换目的地；预留失败时原目的地与派遣不变，
     * 但失败记录持久化，同业务号重放返回首次失败结果。
     */
    @Transactional
    public DiversionResponse divert(DivertRequest request) {
        String fp = fingerprint.divert(request);
        Diversion existing = diversionRepository.findDetailedByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayDiversionOrConflict(existing, fp);
        }

        Dispatch dispatch = dispatchRepository.findWithLockById(request.dispatchId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPATCH_NOT_FOUND));
        existing = diversionRepository.findDetailedByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayDiversionOrConflict(existing, fp);
        }

        DiversionReason reason = parseDivertReason(request.reason());
        Hospital current = dispatch.getHospital();

        // 状态闸口：仅到场后、到院前可改派
        if (dispatch.getStatus() == DispatchStatus.EN_ROUTE) {
            throw new BusinessException(ErrorCode.DIVERSION_NOT_ARRIVED);
        }
        if (dispatch.getStatus() != DispatchStatus.ON_SCENE) {
            throw new BusinessException(ErrorCode.DIVERSION_NOT_ALLOWED);
        }
        if (dispatch.getArrivedAtHospitalAt() != null) {
            throw new BusinessException(ErrorCode.DIVERSION_ALREADY_AT_HOSPITAL);
        }
        // 旧请求防护：请求声明的原目的地必须等于当前目的地，否则是过期请求
        if (!current.getId().equals(request.fromHospitalId())) {
            throw new BusinessException(ErrorCode.DIVERSION_DESTINATION_MISMATCH);
        }
        if (current.getId().equals(request.toHospitalId())) {
            throw new BusinessException(ErrorCode.DIVERSION_SAME_HOSPITAL);
        }

        // 新旧医院按 id 全局顺序加行锁，避免并发改派（A→B 与 B→A）锁顺序死锁
        List<Hospital> hospitals = lockHospitalsOrdered(current.getId(), request.toHospitalId());
        current = hospitals.stream()
                .filter(h -> h.getId().equals(request.fromHospitalId())).findFirst().orElseThrow();
        Hospital target = hospitals.stream()
                .filter(h -> h.getId().equals(request.toHospitalId())).findFirst().orElseThrow();
        EmergencyEvent event = dispatch.getEvent();
        if (target.getReceivingStatus() != ReceivingStatus.OPEN) {
            return divertFailed(request, fp, dispatch, event, current, target, reason,
                    ErrorCode.HOSPITAL_CLOSED, "医院 " + target.getName() + " 已关闭接收");
        }
        if (!target.getAcceptedEmergencyTypes().contains(event.getEmergencyType())) {
            return divertFailed(request, fp, dispatch, event, current, target, reason,
                    ErrorCode.HOSPITAL_TYPE_NOT_ACCEPTED,
                    "医院 " + target.getName() + " 不接收急救类型 " + event.getEmergencyType());
        }
        // 先预留新医院名额：条件更新保证并发争抢最后一个名额只有一方成功
        if (hospitalRepository.reserveBedIfOpen(target.getId()) != 1) {
            return divertFailed(request, fp, dispatch, event, current, target, reason,
                    ErrorCode.HOSPITAL_NO_CAPACITY,
                    "医院 " + target.getName() + " 床位已满，改派预留失败");
        }

        // 新名额预留成功，才释放原医院名额并切换目的地
        Instant now = clock.instant();
        hospitalRepository.releaseBed(current.getId());
        dispatch.setHospital(target);

        Diversion diversion = new Diversion(request.bizNo(), fp, dispatch, event, current, target,
                reason, request.reasonDetail(), DiversionStatus.SUCCESS, now);
        diversion.setFinishedAt(now);
        try {
            diversionRepository.save(diversion);
            diversionRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号已被使用且内容不一致: " + request.bizNo());
        }
        resolvePendingRejections(dispatch, diversion, now);

        String note = reason.name() + (request.reasonDetail() == null ? "" : ": " + request.reasonDetail());
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.HOSPITAL, null, null, current.getId(),
                dispatch, ResourceTimelineEntry.Action.RESERVATION_RELEASED,
                "改派释放原医院名额: " + note, now));
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.HOSPITAL, null, null, target.getId(),
                dispatch, ResourceTimelineEntry.Action.RESERVED,
                "改派预留新医院名额: " + note, now));
        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.HOSPITAL, null, null, target.getId(),
                dispatch, ResourceTimelineEntry.Action.DIVERTED, note, now));

        return DiversionResponse.from(diversion);
    }

    /**
     * 改派预留失败：主事务回滚（目的地、车组、名额均不变），失败记录在独立事务中持久化，
     * 同业务号重放返回首次失败结果。
     */
    private DiversionResponse divertFailed(DivertRequest request, String fp, Dispatch dispatch,
                                           EmergencyEvent event, Hospital current, Hospital target,
                                           DiversionReason reason, ErrorCode code, String detail) {
        Instant now = clock.instant();
        Diversion failed = new Diversion(request.bizNo(), fp, dispatch, event, current, target,
                reason, detail, DiversionStatus.FAILED, now);
        failed.setFailureCode(code.getCode());
        failed.setFinishedAt(now);
        failureRecorder.recordFailed(failed);
        // 触发主事务回滚：丢弃新医院预留（本例预留未发生）与任何脏状态
        throw new BusinessException(code, detail);
    }

    /**
     * 按医院 id 全局升序加悲观行锁，返回已加锁的医院（去重，保持加锁顺序）。
     * 保证所有事务对医院行的加锁顺序一致，杜绝交叉预留/释放时的锁顺序死锁。
     */
    private List<Hospital> lockHospitalsOrdered(Long firstId, Long secondId) {
        return (firstId.equals(secondId) ? List.of(firstId)
                        : firstId < secondId ? List.of(firstId, secondId)
                        : List.of(secondId, firstId)).stream()
                .map(id -> hospitalRepository.findWithLockById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.HOSPITAL_NOT_FOUND)))
                .toList();
    }

    /** 成功改派消化该派遣此前未处理的拒收任务（拒收不释放车组/名额，由本次成功改派完成切换）。 */    private void resolvePendingRejections(Dispatch dispatch, Diversion successful, Instant at) {
        diversionRepository
                .findByDispatchIdAndStatusOrderByCreatedAtAscIdAsc(
                        dispatch.getId(), DiversionStatus.PENDING)
                .forEach(pending -> {
                    pending.setStatus(DiversionStatus.RESOLVED);
                    pending.setFinishedAt(at);
                    pending.setResolvedRejection(successful);
                });
    }

    private DiversionResponse replayDiversionOrConflict(Diversion existing, String fp) {
        if (!existing.getRequestFingerprint().equals(fp)) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号 " + existing.getBizNo() + " 已存在但请求内容不同");
        }
        if (existing.getStatus() == DiversionStatus.FAILED) {
            ErrorCode code = ErrorCode.HOSPITAL_NO_CAPACITY;
            if (existing.getFailureCode() != null) {
                for (ErrorCode c : ErrorCode.values()) {
                    if (c.getCode().equals(existing.getFailureCode())) {
                        code = c;
                        break;
                    }
                }
            }
            throw new BusinessException(code, existing.getReasonDetail());
        }
        return DiversionResponse.from(existing, true);
    }

    private DiversionReason parseDivertReason(String raw) {
        DiversionReason reason;
        try {
            reason = DiversionReason.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "非法的改派原因: " + raw);
        }
        if (reason == DiversionReason.HOSPITAL_REJECTED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "改派原因不能为 HOSPITAL_REJECTED，请使用拒收接口");
        }
        return reason;
    }

    // ------------------------------------------------------------------
    // 医院拒收
    // ------------------------------------------------------------------

    /**
     * 幂等拒收。记录拒收原因并生成 PENDING 改派任务；不释放车辆、救护组，也不主动释放
     * 原医院名额（等待后续成功改派时先预留新名额再释放）。
     */
    @Transactional
    public DiversionResponse reject(RejectRequest request) {
        String fp = fingerprint.reject(request);
        Diversion existing = diversionRepository.findDetailedByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayDiversionOrConflict(existing, fp);
        }

        Dispatch dispatch = dispatchRepository.findWithLockById(request.dispatchId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPATCH_NOT_FOUND));
        existing = diversionRepository.findDetailedByBizNo(request.bizNo()).orElse(null);
        if (existing != null) {
            return replayDiversionOrConflict(existing, fp);
        }

        if (dispatch.getStatus() != DispatchStatus.ON_SCENE) {
            if (dispatch.getStatus() == DispatchStatus.EN_ROUTE) {
                throw new BusinessException(ErrorCode.DIVERSION_NOT_ARRIVED,
                        "尚未到达现场，医院不能拒收");
            }
            throw new BusinessException(ErrorCode.DIVERSION_NOT_ALLOWED);
        }
        if (dispatch.getArrivedAtHospitalAt() != null) {
            throw new BusinessException(ErrorCode.DIVERSION_ALREADY_AT_HOSPITAL,
                    "事件已到达医院，不能再拒收");
        }
        // 拒收医院必须是当前目的医院：后来确认的新目的地不能被旧医院拒收
        if (!dispatch.getHospital().getId().equals(request.hospitalId())) {
            throw new BusinessException(ErrorCode.REJECTION_DESTINATION_MISMATCH);
        }

        Instant now = clock.instant();
        Diversion rejection = new Diversion(request.bizNo(), fp, dispatch, dispatch.getEvent(),
                dispatch.getHospital(), null, DiversionReason.HOSPITAL_REJECTED,
                request.reasonDetail(), DiversionStatus.PENDING, now);
        try {
            diversionRepository.save(rejection);
            diversionRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号已被使用且内容不一致: " + request.bizNo());
        }

        timelineRepository.save(new ResourceTimelineEntry(
                ResourceTimelineEntry.ResourceType.HOSPITAL, null, null,
                dispatch.getHospital().getId(), dispatch,
                ResourceTimelineEntry.Action.REJECTED, request.reasonDetail(), now));

        // 车辆、救护组与原医院名额均保持占用，等待后续改派
        return DiversionResponse.from(rejection);
    }

    // ------------------------------------------------------------------
    // 查询
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
        Dispatch current = history.stream()
                .filter(d -> ACTIVE_STATUSES.contains(d.getStatus()))
                .findFirst()
                .orElse(null);
        List<DispatchResponse> dispatches = history.stream()
                .map(DispatchResponse::from)
                .toList();
        List<DiversionResponse> diversions = diversionRepository
                .findByEventIdOrderByCreatedAtAscIdAsc(eventId).stream()
                .map(DiversionResponse::from)
                .toList();
        List<TimelineEntryResponse> timeline = timelineRepository
                .findByEventIdOrderByOccurredAtAscIdAsc(eventId).stream()
                .map(this::toTimelineResponse)
                .toList();
        HospitalResponse currentHospital = current == null ? null
                : HospitalResponse.from(current.getHospital());
        DispatchResponse currentResponse = current == null ? null
                : DispatchResponse.from(current);
        return new EventDispatchDetailResponse(EventResponse.from(event), currentResponse,
                currentHospital, dispatches, diversions, timeline);
    }

    @Transactional(readOnly = true)
    public List<DiversionResponse> getDispatchDiversions(Long dispatchId) {
        if (!dispatchRepository.existsById(dispatchId)) {
            throw new BusinessException(ErrorCode.DISPATCH_NOT_FOUND);
        }
        return diversionRepository.findByDispatchIdOrderByCreatedAtAscIdAsc(dispatchId).stream()
                .map(DiversionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DiversionResponse getDiversion(Long diversionId) {
        return DiversionResponse.from(diversionRepository.findDetailedById(diversionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DIVERSION_NOT_FOUND)));
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
    public List<TimelineEntryResponse> getHospitalTimeline(Long hospitalId) {
        if (!hospitalRepository.existsById(hospitalId)) {
            throw new BusinessException(ErrorCode.HOSPITAL_NOT_FOUND);
        }
        return timelineRepository.findByHospitalIdOrderByOccurredAtAscIdAsc(hospitalId).stream()
                .map(this::toTimelineResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TimelineEntryResponse> getEventTimeline(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new BusinessException(ErrorCode.EVENT_NOT_FOUND);
        }
        return timelineRepository.findByEventIdOrderByOccurredAtAscIdAsc(eventId).stream()
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
                        d.getHospital().getId(),
                        d.getStatus().name(),
                        d.getPreemptedDispatch() == null ? null : d.getPreemptedDispatch().getId(),
                        d.getDispatchedAt()))
                .toList();
    }

    private TimelineEntryResponse toTimelineResponse(ResourceTimelineEntry e) {
        Long resourceId = switch (e.getResourceType()) {
            case AMBULANCE -> e.getAmbulanceId();
            case CREW -> e.getCrewId();
            case HOSPITAL -> e.getHospitalId();
        };
        return new TimelineEntryResponse(
                e.getId(),
                e.getResourceType().name(),
                resourceId,
                e.getDispatch().getId(),
                String.valueOf(e.getDispatch().getEvent().getId()),
                e.getHospitalId(),
                e.getAction().name(),
                e.getNote(),
                e.getOccurredAt());
    }

    private DispatchResponse replayOrConflict(Dispatch existing, String fp) {
        if (!existing.getRequestFingerprint().equals(fp)) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号 " + existing.getBizNo() + " 已存在但请求内容不同");
        }
        return DispatchResponse.from(existing, true);
    }
}
