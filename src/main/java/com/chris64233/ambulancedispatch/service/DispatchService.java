package com.chris64233.ambulancedispatch.service;

import com.chris64233.ambulancedispatch.api.error.ApiException;
import com.chris64233.ambulancedispatch.api.error.ErrorCode;
import com.chris64233.ambulancedispatch.domain.Crew;
import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.domain.DispatchStatus;
import com.chris64233.ambulancedispatch.domain.DutyStatus;
import com.chris64233.ambulancedispatch.domain.Incident;
import com.chris64233.ambulancedispatch.domain.IncidentStatus;
import com.chris64233.ambulancedispatch.domain.PreemptionRecord;
import com.chris64233.ambulancedispatch.domain.ResourceEvent;
import com.chris64233.ambulancedispatch.domain.ResourceType;
import com.chris64233.ambulancedispatch.domain.Vehicle;
import com.chris64233.ambulancedispatch.domain.VehicleStatus;
import com.chris64233.ambulancedispatch.repository.CrewRepository;
import com.chris64233.ambulancedispatch.repository.DispatchRepository;
import com.chris64233.ambulancedispatch.repository.IncidentRepository;
import com.chris64233.ambulancedispatch.repository.PreemptionRecordRepository;
import com.chris64233.ambulancedispatch.repository.ResourceEventRepository;
import com.chris64233.ambulancedispatch.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 派遣核心服务。
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>一次派遣同时选定一辆车和一组救护组，二者在同一事务内被原子占用，
 *       不会出现车辆与人员分别被不同事件占用的情况。</li>
 *   <li>通过悲观写锁（事件 → 车辆 → 救护组，固定加锁顺序）串行化并发争抢，
 *       同一资源并发派遣只有一个成功。</li>
 *   <li>高优先级事件可抢占尚未到达现场的低优先级派遣；抢占在同一事务内
 *       释放原派遣、恢复原事件为待派遣并把资源分配给新事件，任一步失败整体回滚。</li>
 *   <li>派遣业务号（requestId）保证幂等：内容相同返回原结果，内容不同返回冲突。</li>
 * </ul>
 */
@Service
public class DispatchService {

    private final IncidentRepository incidentRepository;
    private final VehicleRepository vehicleRepository;
    private final CrewRepository crewRepository;
    private final DispatchRepository dispatchRepository;
    private final PreemptionRecordRepository preemptionRecordRepository;
    private final ResourceEventRepository resourceEventRepository;

    public DispatchService(IncidentRepository incidentRepository,
                           VehicleRepository vehicleRepository,
                           CrewRepository crewRepository,
                           DispatchRepository dispatchRepository,
                           PreemptionRecordRepository preemptionRecordRepository,
                           ResourceEventRepository resourceEventRepository) {
        this.incidentRepository = incidentRepository;
        this.vehicleRepository = vehicleRepository;
        this.crewRepository = crewRepository;
        this.dispatchRepository = dispatchRepository;
        this.preemptionRecordRepository = preemptionRecordRepository;
        this.resourceEventRepository = resourceEventRepository;
    }

    /**
     * 创建派遣。资源空闲时直接占用；资源被同一低优先级派遣整体占用且未到场时执行抢占。
     */
    @Transactional
    public DispatchOutcome dispatch(String requestId, Long incidentId, Long vehicleId, Long crewId) {
        String fingerprint = DispatchExecutor.fingerprint(incidentId, vehicleId, crewId);

        Instant now = Instant.now();

        // 固定加锁顺序：事件 → 车辆 → 救护组，避免死锁。
        Incident incident = incidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> ApiException.notFound("事件", incidentId));

        // 幂等检查必须在事件锁内进行：并发重放在此串行，
        // 内容相同返回原结果，内容不同返回冲突。
        var existing = dispatchRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            Dispatch found = existing.get();
            if (found.getContentFingerprint().equals(fingerprint)) {
                return new DispatchOutcome(found, true);
            }
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "派遣业务号已存在且内容不同: requestId=" + requestId);
        }

        if (incident.getStatus() != IncidentStatus.PENDING) {
            throw ApiException.illegalState("事件当前状态不允许派遣: " + incident.getStatus());
        }
        Vehicle vehicle = vehicleRepository.findByIdForUpdate(vehicleId)
                .orElseThrow(() -> ApiException.notFound("车辆", vehicleId));
        Crew crew = crewRepository.findByIdForUpdate(crewId)
                .orElseThrow(() -> ApiException.notFound("救护组", crewId));

        validateRequirements(incident, vehicle, crew);

        Dispatch occupying = findOccupyingDispatch(vehicle, crew);
        Dispatch created = occupying == null
                ? occupy(requestId, fingerprint, incident, vehicle, crew, now)
                : preempt(requestId, fingerprint, incident, vehicle, crew, occupying, now);
        // 事务内立即落库，让 requestId 唯一约束冲突在此抛出（可被翻译为
        // DataIntegrityViolationException），而不是延迟到提交阶段。
        dispatchRepository.flush();
        return new DispatchOutcome(created, false);
    }

    /** 确认到达现场。到达后派遣不可再被抢占。 */
    @Transactional
    public Dispatch arrive(Long dispatchId) {
        Instant now = Instant.now();
        Dispatch dispatch = lockActiveDispatch(dispatchId);
        if (dispatch.hasArrived()) {
            throw ApiException.illegalState("派遣已到达现场: dispatchId=" + dispatchId);
        }
        Incident incident = lockIncident(dispatch.getIncidentId());
        if (incident.getStatus() != IncidentStatus.DISPATCHED) {
            throw ApiException.illegalState("事件当前状态不允许到场确认: " + incident.getStatus());
        }
        dispatch.markArrived(now);
        incident.transitionTo(IncidentStatus.ON_SCENE, now);
        recordResourceEvents(dispatch, ResourceEvent.EventType.ARRIVED_ON_SCENE, null, now);
        return dispatch;
    }

    /** 完成派遣：释放车辆与救护组，事件与派遣进入不可修改的终态。 */
    @Transactional
    public Dispatch complete(Long dispatchId) {
        return closeDispatch(dispatchId, DispatchStatus.COMPLETED,
                IncidentStatus.COMPLETED, ResourceEvent.EventType.RELEASED_BY_COMPLETION);
    }

    /** 取消派遣：释放车辆与救护组，事件与派遣进入不可修改的终态。 */
    @Transactional
    public Dispatch cancel(Long dispatchId) {
        return closeDispatch(dispatchId, DispatchStatus.CANCELLED,
                IncidentStatus.CANCELLED, ResourceEvent.EventType.RELEASED_BY_CANCELLATION);
    }

    @Transactional(readOnly = true)
    public Dispatch getDispatch(Long dispatchId) {
        return dispatchRepository.findById(dispatchId)
                .orElseThrow(() -> ApiException.notFound("派遣", dispatchId));
    }

    /** 事件最近一次派遣（含被抢占的历史派遣之外的最新一单）。 */
    @Transactional(readOnly = true)
    public Dispatch latestDispatchOfIncident(Long incidentId) {
        if (!incidentRepository.existsById(incidentId)) {
            throw ApiException.notFound("事件", incidentId);
        }
        List<Dispatch> dispatches = dispatchRepository.findByIncidentIdOrderByIdAsc(incidentId);
        if (dispatches.isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "事件尚无派遣: incidentId=" + incidentId);
        }
        return dispatches.get(dispatches.size() - 1);
    }

    @Transactional(readOnly = true)
    public List<ResourceEvent> resourceTimeline(ResourceType resourceType, Long resourceId) {
        boolean exists = switch (resourceType) {
            case VEHICLE -> vehicleRepository.existsById(resourceId);
            case CREW -> crewRepository.existsById(resourceId);
        };
        if (!exists) {
            throw ApiException.notFound(resourceType == ResourceType.VEHICLE ? "车辆" : "救护组", resourceId);
        }
        return resourceEventRepository.findByResourceTypeAndResourceIdOrderByOccurredAtAscIdAsc(resourceType, resourceId);
    }

    /** 抢占链：事件作为抢占方或被抢占方参与的全部抢占记录，按时间升序。 */
    @Transactional(readOnly = true)
    public List<PreemptionRecord> preemptionChain(Long incidentId) {
        if (!incidentRepository.existsById(incidentId)) {
            throw ApiException.notFound("事件", incidentId);
        }
        return preemptionRecordRepository
                .findByPreemptingIncidentIdOrPreemptedIncidentIdOrderByOccurredAtAscIdAsc(incidentId, incidentId);
    }

    // ---- 内部实现 ----

    private Dispatch occupy(String requestId, String fingerprint, Incident incident,
                            Vehicle vehicle, Crew crew, Instant now) {
        vehicle.setStatus(VehicleStatus.DISPATCHED);
        crew.setDutyStatus(DutyStatus.DISPATCHED);
        Dispatch dispatch = new Dispatch(requestId, fingerprint, incident.getId(),
                vehicle.getId(), crew.getId(), now);
        dispatchRepository.save(dispatch);
        incident.transitionTo(IncidentStatus.DISPATCHED, now);
        recordResourceEvents(dispatch, ResourceEvent.EventType.DISPATCHED, null, now);
        return dispatch;
    }

    /**
     * 抢占：一次性释放原派遣资源并分配给新事件，原事件恢复为待派遣。
     * 全部写操作在同一事务内，任一步失败整体回滚，原派遣保持不变。
     */
    private Dispatch preempt(String requestId, String fingerprint, Incident incident,
                             Vehicle vehicle, Crew crew, Dispatch occupying, Instant now) {
        if (occupying.hasArrived()) {
            throw ApiException.unavailable("资源所属派遣已到达现场，不可抢占: dispatchId=" + occupying.getId());
        }
        Incident occupyingIncident = lockIncident(occupying.getIncidentId());
        if (incident.getPriority().compareTo(occupyingIncident.getPriority()) <= 0) {
            throw ApiException.unavailable("资源被同等或更高优先级事件占用，不可抢占: dispatchId=" + occupying.getId());
        }

        // 释放原派遣：原派遣终止为 PREEMPTED，原事件恢复待派遣。
        occupying.close(DispatchStatus.PREEMPTED, now);
        occupyingIncident.transitionTo(IncidentStatus.PENDING, now);

        // 资源整体转移给新事件（车辆/救护组保持 DISPATCHED 状态，仅变更归属）。
        Dispatch dispatch = new Dispatch(requestId, fingerprint, incident.getId(),
                vehicle.getId(), crew.getId(), now);
        dispatchRepository.save(dispatch);
        incident.transitionTo(IncidentStatus.DISPATCHED, now);

        preemptionRecordRepository.save(new PreemptionRecord(
                incident.getId(), dispatch.getId(),
                occupyingIncident.getId(), occupying.getId(),
                vehicle.getId(), crew.getId(), now));
        recordResourceEvents(dispatch, ResourceEvent.EventType.TRANSFERRED_BY_PREEMPTION,
                occupyingIncident.getId(), now);
        return dispatch;
    }

    private Dispatch closeDispatch(Long dispatchId, DispatchStatus terminal,
                                   IncidentStatus incidentTerminal, ResourceEvent.EventType eventType) {
        Instant now = Instant.now();
        Dispatch dispatch = lockActiveDispatch(dispatchId);
        Incident incident = lockIncident(dispatch.getIncidentId());
        Vehicle vehicle = vehicleRepository.findByIdForUpdate(dispatch.getVehicleId())
                .orElseThrow(() -> ApiException.notFound("车辆", dispatch.getVehicleId()));
        Crew crew = crewRepository.findByIdForUpdate(dispatch.getCrewId())
                .orElseThrow(() -> ApiException.notFound("救护组", dispatch.getCrewId()));

        dispatch.close(terminal, now);
        incident.transitionTo(incidentTerminal, now);
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        crew.setDutyStatus(DutyStatus.ON_DUTY);
        recordResourceEvents(dispatch, eventType, null, now);
        return dispatch;
    }

    private Dispatch lockActiveDispatch(Long dispatchId) {
        Dispatch dispatch = dispatchRepository.findByIdForUpdate(dispatchId)
                .orElseThrow(() -> ApiException.notFound("派遣", dispatchId));
        if (dispatch.getStatus() != DispatchStatus.ACTIVE) {
            throw ApiException.illegalState("派遣已形成不可修改的终态: " + dispatch.getStatus());
        }
        return dispatch;
    }

    private Incident lockIncident(Long incidentId) {
        return incidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> ApiException.notFound("事件", incidentId));
    }

    /**
     * 校验资源是否满足事件要求：
     * 车辆服务区域须与事件区域一致；车辆设备与救护组资质的并集须覆盖事件所需能力。
     */
    private void validateRequirements(Incident incident, Vehicle vehicle, Crew crew) {
        if (!vehicle.getServiceArea().equals(incident.getArea())) {
            throw ApiException.requirementNotMet(
                    "车辆服务区域不匹配: 车辆区域=" + vehicle.getServiceArea() + ", 事件区域=" + incident.getArea());
        }
        Set<String> covered = new HashSet<>(vehicle.getEquipment());
        covered.addAll(crew.getQualifications());
        Set<String> missing = new TreeSet<>(incident.getRequiredCapabilities());
        missing.removeAll(covered);
        if (!missing.isEmpty()) {
            throw ApiException.requirementNotMet("资源能力不满足事件所需能力, 缺少: " + String.join(",", missing));
        }
    }

    /**
     * 查找整体占用目标车辆与救护组的活跃派遣。
     * 资源空闲返回 null；资源被不同派遣分别占用或处于停用/休班状态时抛出冲突。
     */
    private Dispatch findOccupyingDispatch(Vehicle vehicle, Crew crew) {
        boolean vehicleFree = vehicle.getStatus() == VehicleStatus.AVAILABLE;
        boolean crewFree = crew.getDutyStatus() == DutyStatus.ON_DUTY;
        if (vehicleFree && crewFree) {
            return null;
        }
        if (vehicle.getStatus() == VehicleStatus.OUT_OF_SERVICE) {
            throw ApiException.unavailable("车辆已停用: vehicleId=" + vehicle.getId());
        }
        if (crew.getDutyStatus() == DutyStatus.OFF_DUTY) {
            throw ApiException.unavailable("救护组休班中: crewId=" + crew.getId());
        }
        Dispatch vehicleDispatch = null;
        Dispatch crewDispatch = null;
        if (vehicle.getStatus() == VehicleStatus.DISPATCHED) {
            vehicleDispatch = dispatchRepository.findByVehicleIdAndStatus(vehicle.getId(), DispatchStatus.ACTIVE)
                    .orElseThrow(() -> ApiException.illegalState(
                            "车辆状态与派遣记录不一致: vehicleId=" + vehicle.getId()));
        }
        if (crew.getDutyStatus() == DutyStatus.DISPATCHED) {
            crewDispatch = dispatchRepository.findByCrewIdAndStatus(crew.getId(), DispatchStatus.ACTIVE)
                    .orElseThrow(() -> ApiException.illegalState(
                            "救护组状态与派遣记录不一致: crewId=" + crew.getId()));
        }
        if (vehicleDispatch != null && crewDispatch != null
                && vehicleDispatch.getId().equals(crewDispatch.getId())) {
            return vehicleDispatch;
        }
        throw ApiException.unavailable("车辆与救护组未同时空闲，且不属于同一派遣，无法整体占用或抢占");
    }

    private void recordResourceEvents(Dispatch dispatch, ResourceEvent.EventType eventType,
                                      Long relatedIncidentId, Instant now) {
        resourceEventRepository.save(new ResourceEvent(ResourceType.VEHICLE, dispatch.getVehicleId(),
                eventType, dispatch.getIncidentId(), dispatch.getId(), relatedIncidentId, now));
        resourceEventRepository.save(new ResourceEvent(ResourceType.CREW, dispatch.getCrewId(),
                eventType, dispatch.getIncidentId(), dispatch.getId(), relatedIncidentId, now));
    }

}
