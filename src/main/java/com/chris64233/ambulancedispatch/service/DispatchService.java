package com.chris64233.ambulancedispatch.service;

import com.chris64233.ambulancedispatch.domain.Ambulance;
import com.chris64233.ambulancedispatch.domain.AssignmentStatus;
import com.chris64233.ambulancedispatch.domain.Crew;
import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.domain.DispatchStatus;
import com.chris64233.ambulancedispatch.domain.DutyStatus;
import com.chris64233.ambulancedispatch.domain.EmergencyEvent;
import com.chris64233.ambulancedispatch.domain.EventStatus;
import com.chris64233.ambulancedispatch.domain.ResourceTimelineEntry;
import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.dto.DispatchResponse;
import com.chris64233.ambulancedispatch.dto.EventDispatchDetailResponse;
import com.chris64233.ambulancedispatch.dto.EventResponse;
import com.chris64233.ambulancedispatch.dto.PreemptRequest;
import com.chris64233.ambulancedispatch.dto.PreemptionChainItemResponse;
import com.chris64233.ambulancedispatch.dto.TimelineEntryResponse;
import com.chris64233.ambulancedispatch.exception.BusinessException;
import com.chris64233.ambulancedispatch.exception.ErrorCode;
import com.chris64233.ambulancedispatch.repository.AmbulanceRepository;
import com.chris64233.ambulancedispatch.repository.CrewRepository;
import com.chris64233.ambulancedispatch.repository.DispatchRepository;
import com.chris64233.ambulancedispatch.repository.EmergencyEventRepository;
import com.chris64233.ambulancedispatch.repository.ResourceTimelineEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 联合派遣核心服务。
 *
 * <p>并发安全：事件/派遣/资源行使用悲观行锁串行化，车辆与救护组的占用使用条件更新（CAS），
 * 任一占用失败整个事务回滚，保证车辆和救护组不会分别被不同事件占用。
 *
 * <p>抢占在单事务内完成“原单置 PREEMPTED + 原事件回 PENDING + 新事件 DISPATCHED + 新单入库”，
 * 任一步失败全部回滚，原派遣保持不变。
 */
@Service
public class DispatchService {

    private static final List<DispatchStatus> ACTIVE_STATUSES =
            List.of(DispatchStatus.EN_ROUTE, DispatchStatus.ON_SCENE);

    private final DispatchRepository dispatchRepository;
    private final EmergencyEventRepository eventRepository;
    private final AmbulanceRepository ambulanceRepository;
    private final CrewRepository crewRepository;
    private final ResourceTimelineEntryRepository timelineRepository;
    private final RequestFingerprint fingerprint;
    private final Clock clock;

    public DispatchService(DispatchRepository dispatchRepository,
                           EmergencyEventRepository eventRepository,
                           AmbulanceRepository ambulanceRepository,
                           CrewRepository crewRepository,
                           ResourceTimelineEntryRepository timelineRepository,
                           RequestFingerprint fingerprint,
                           Clock clock) {
        this.dispatchRepository = dispatchRepository;
        this.eventRepository = eventRepository;
        this.ambulanceRepository = ambulanceRepository;
        this.crewRepository = crewRepository;
        this.timelineRepository = timelineRepository;
        this.fingerprint = fingerprint;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 联合派遣
    // ------------------------------------------------------------------

    /**
     * 幂等派遣。相同业务号 + 相同内容：返回原结果（replayed=true）；
     * 相同业务号 + 不同内容：409 冲突。整个判定与占用在同一事务内完成。
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

        // 固定顺序加资源锁，随后用条件更新原子占用
        Ambulance ambulance = ambulanceRepository.findWithLockById(request.ambulanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AMBULANCE_NOT_FOUND));
        Crew crew = crewRepository.findWithLockById(request.crewId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CREW_NOT_FOUND));
        validateAssignment(event, ambulance, crew);

        if (ambulanceRepository.occupyIfAvailable(ambulance.getId()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_UNAVAILABLE, "车辆已被占用");
        }
        // 救护组占用失败：抛出后事务回滚，车辆占用一并撤销，杜绝资源拆分占用
        if (crewRepository.occupyIfIdleAndOnDuty(crew.getId()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_UNAVAILABLE, "救护组已被占用或不值勤");
        }

        event.setStatus(EventStatus.DISPATCHED);
        Instant now = clock.instant();
        Dispatch dispatch = new Dispatch(request.bizNo(), fp, event, ambulance, crew,
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

    // ------------------------------------------------------------------
    // 抢占
    // ------------------------------------------------------------------

    /**
     * 幂等抢占。整个校验与资源切换在同一事务内，任一步失败全部回滚、原派遣不变。
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
        // 资源对新事件仍需满足区域与能力要求，否则安全失败、原派遣不动
        validateAssignment(newEvent, ambulance, crew);

        // 4. 以下全部在同一事务内原子切换；任何异常都会回滚到抢占前状态
        Instant now = clock.instant();
        target.setStatus(DispatchStatus.PREEMPTED);
        target.setFinishedAt(now);
        oldEvent.setStatus(EventStatus.PENDING);
        newEvent.setStatus(EventStatus.DISPATCHED);

        Dispatch next = new Dispatch(request.bizNo(), fp, newEvent, ambulance, crew,
                target.getPreemptionRoot(), target, now);
        try {
            dispatchRepository.save(next);
            dispatchRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号已被使用且内容不一致: " + request.bizNo());
        }

        // 资源状态保持占用（车 DISPATCHED / 组 ASSIGNED），只转移属主，不产生空闲窗口
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
    // 生命周期：到达 / 完成 / 取消
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

    private DispatchResponse replayOrConflict(Dispatch existing, String fp) {
        if (!existing.getRequestFingerprint().equals(fp)) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "业务号 " + existing.getBizNo() + " 已存在但请求内容不同");
        }
        return DispatchResponse.from(existing, true);
    }

    // ------------------------------------------------------------------
    // 查询：事件派遣详情 / 资源时间线 / 抢占链
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
        return new EventDispatchDetailResponse(EventResponse.from(event), current, dispatches);
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
                        d.getStatus().name(),
                        d.getPreemptedDispatch() == null ? null : d.getPreemptedDispatch().getId(),
                        d.getDispatchedAt()))
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
