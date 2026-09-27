package com.chris64233.ambulancedispatch;

import com.chris64233.ambulancedispatch.api.error.ApiException;
import com.chris64233.ambulancedispatch.api.error.ErrorCode;
import com.chris64233.ambulancedispatch.domain.Crew;
import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.domain.DispatchStatus;
import com.chris64233.ambulancedispatch.domain.DutyStatus;
import com.chris64233.ambulancedispatch.domain.Incident;
import com.chris64233.ambulancedispatch.domain.IncidentStatus;
import com.chris64233.ambulancedispatch.domain.PreemptionRecord;
import com.chris64233.ambulancedispatch.domain.Priority;
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
import com.chris64233.ambulancedispatch.service.CatalogService;
import com.chris64233.ambulancedispatch.service.DispatchExecutor;
import com.chris64233.ambulancedispatch.service.DispatchOutcome;
import com.chris64233.ambulancedispatch.service.DispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 派遣核心流程集成测试：联合占用、能力校验、幂等、抢占、终态与查询。
 */
@SpringBootTest
class DispatchServiceIntegrationTest {

    @Autowired
    CatalogService catalog;
    @Autowired
    DispatchService dispatchService;
    @Autowired
    DispatchExecutor dispatchExecutor;
    @Autowired
    VehicleRepository vehicleRepository;
    @Autowired
    CrewRepository crewRepository;
    @Autowired
    IncidentRepository incidentRepository;
    @Autowired
    DispatchRepository dispatchRepository;
    @Autowired
    ResourceEventRepository resourceEventRepository;
    @Autowired
    PreemptionRecordRepository preemptionRecordRepository;

    @BeforeEach
    void clean() {
        resourceEventRepository.deleteAll();
        preemptionRecordRepository.deleteAll();
        dispatchRepository.deleteAll();
        incidentRepository.deleteAll();
        crewRepository.deleteAll();
        vehicleRepository.deleteAll();
    }

    private Vehicle newVehicle(String area, String... equipment) {
        return catalog.registerVehicle("V-" + UUID.randomUUID(), area, Set.of(equipment));
    }

    private Crew newCrew(String... qualifications) {
        return catalog.registerCrew("C-" + UUID.randomUUID(), Set.of(qualifications));
    }

    private Incident newIncident(Priority priority, String... required) {
        return catalog.reportIncident("CENTRAL", "人民路 1 号", priority, Set.of(required));
    }

    private DispatchOutcome dispatch(Incident incident, Vehicle vehicle, Crew crew) {
        return dispatchExecutor.execute("REQ-" + UUID.randomUUID(),
                incident.getId(), vehicle.getId(), crew.getId());
    }

    @Test
    void dispatchOccupiesVehicleAndCrewAtomically() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Incident incident = newIncident(Priority.LOW, "DEFIBRILLATOR", "PARAMEDIC");

        DispatchOutcome outcome = dispatch(incident, vehicle, crew);

        assertThat(outcome.replayed()).isFalse();
        Dispatch dispatch = outcome.dispatch();
        assertThat(dispatch.getStatus()).isEqualTo(DispatchStatus.ACTIVE);
        assertThat(dispatch.getVehicleId()).isEqualTo(vehicle.getId());
        assertThat(dispatch.getCrewId()).isEqualTo(crew.getId());
        // 车辆与救护组在同一事务内被原子占用
        assertThat(vehicleRepository.findById(vehicle.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleStatus.DISPATCHED);
        assertThat(crewRepository.findById(crew.getId()).orElseThrow().getDutyStatus())
                .isEqualTo(DutyStatus.DISPATCHED);
        assertThat(incidentRepository.findById(incident.getId()).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.DISPATCHED);
        // 时间线记录两类资源的占用事件
        List<ResourceEvent> vehicleTimeline = dispatchService.resourceTimeline(ResourceType.VEHICLE, vehicle.getId());
        assertThat(vehicleTimeline).extracting(ResourceEvent::getEventType)
                .containsExactly(ResourceEvent.EventType.DISPATCHED);
        assertThat(dispatchService.resourceTimeline(ResourceType.CREW, crew.getId()))
                .extracting(ResourceEvent::getEventType)
                .containsExactly(ResourceEvent.EventType.DISPATCHED);
    }

    @Test
    void dispatchRequiresMatchingServiceArea() {
        Vehicle vehicle = newVehicle("EAST", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Incident incident = newIncident(Priority.LOW, "PARAMEDIC");

        assertThatThrownBy(() -> dispatch(incident, vehicle, crew))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.REQUIREMENT_NOT_MET));
    }

    @Test
    void dispatchRequiresCapabilityCoverageByVehicleAndCrewUnion() {
        Vehicle vehicle = newVehicle("CENTRAL", "STRETCHER");
        Crew crew = newCrew("DRIVER");
        Incident incident = newIncident(Priority.LOW, "DEFIBRILLATOR");

        assertThatThrownBy(() -> dispatch(incident, vehicle, crew))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.REQUIREMENT_NOT_MET));
    }

    @Test
    void dispatchFailsWhenResourcesBusy() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Incident first = newIncident(Priority.LOW, "PARAMEDIC");
        Incident second = newIncident(Priority.LOW, "PARAMEDIC");
        dispatch(first, vehicle, crew);

        assertThatThrownBy(() -> dispatch(second, vehicle, crew))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_UNAVAILABLE));
        // 失败的派遣不影响已有占用
        assertThat(dispatchRepository.findByVehicleIdAndStatus(vehicle.getId(), DispatchStatus.ACTIVE))
                .isPresent();
    }

    @Test
    void idempotentReplayReturnsOriginalResult() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Incident incident = newIncident(Priority.LOW, "PARAMEDIC");
        String requestId = "REQ-IDEMPOTENT-1";

        DispatchOutcome first = dispatchExecutor.execute(requestId, incident.getId(), vehicle.getId(), crew.getId());
        DispatchOutcome replay = dispatchExecutor.execute(requestId, incident.getId(), vehicle.getId(), crew.getId());

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.dispatch().getId()).isEqualTo(first.dispatch().getId());
        assertThat(dispatchRepository.findByIncidentIdOrderByIdAsc(incident.getId())).hasSize(1);
    }

    @Test
    void sameRequestIdWithDifferentContentConflicts() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Crew otherCrew = newCrew("PARAMEDIC");
        Incident incident = newIncident(Priority.LOW, "PARAMEDIC");
        Incident otherIncident = newIncident(Priority.LOW, "PARAMEDIC");
        String requestId = "REQ-CONFLICT-1";
        dispatchExecutor.execute(requestId, incident.getId(), vehicle.getId(), crew.getId());

        // 相同业务号、不同内容（换了救护组）→ 冲突
        assertThatThrownBy(() -> dispatchExecutor.execute(requestId, incident.getId(), vehicle.getId(), otherCrew.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        // 相同业务号、不同事件 → 冲突
        assertThatThrownBy(() -> dispatchExecutor.execute(requestId, otherIncident.getId(), vehicle.getId(), crew.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void highPriorityPreemptsLowerPriorityDispatch() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Incident low = newIncident(Priority.LOW, "PARAMEDIC");
        Incident high = newIncident(Priority.HIGH, "PARAMEDIC");
        Dispatch lowDispatch = dispatch(low, vehicle, crew).dispatch();

        DispatchOutcome outcome = dispatch(high, vehicle, crew);

        Dispatch highDispatch = outcome.dispatch();
        assertThat(highDispatch.getStatus()).isEqualTo(DispatchStatus.ACTIVE);
        // 原派遣一次性终止，原事件恢复待派遣
        assertThat(dispatchRepository.findById(lowDispatch.getId()).orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.PREEMPTED);
        assertThat(incidentRepository.findById(low.getId()).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.PENDING);
        // 资源整体转移给新事件
        assertThat(incidentRepository.findById(high.getId()).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.DISPATCHED);
        assertThat(dispatchRepository.findByVehicleIdAndStatus(vehicle.getId(), DispatchStatus.ACTIVE))
                .hasValueSatisfying(d -> assertThat(d.getId()).isEqualTo(highDispatch.getId()));
        // 抢占记录
        List<PreemptionRecord> chain = dispatchService.preemptionChain(high.getId());
        assertThat(chain).hasSize(1);
        PreemptionRecord record = chain.get(0);
        assertThat(record.getPreemptingIncidentId()).isEqualTo(high.getId());
        assertThat(record.getPreemptedIncidentId()).isEqualTo(low.getId());
        assertThat(record.getPreemptedDispatchId()).isEqualTo(lowDispatch.getId());
        // 资源时间线记录抢占转移
        assertThat(dispatchService.resourceTimeline(ResourceType.VEHICLE, vehicle.getId()))
                .extracting(ResourceEvent::getEventType)
                .containsExactly(ResourceEvent.EventType.DISPATCHED,
                        ResourceEvent.EventType.TRANSFERRED_BY_PREEMPTION);
    }

    @Test
    void preemptionChainSpansMultiplePreemptions() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Incident low = newIncident(Priority.LOW, "PARAMEDIC");
        Incident medium = newIncident(Priority.MEDIUM, "PARAMEDIC");
        Incident high = newIncident(Priority.HIGH, "PARAMEDIC");
        dispatch(low, vehicle, crew);
        dispatch(medium, vehicle, crew);
        dispatch(high, vehicle, crew);

        // medium 既抢占了 low，又被 high 抢占 → 链上有两条记录
        List<PreemptionRecord> chain = dispatchService.preemptionChain(medium.getId());
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).getPreemptingIncidentId()).isEqualTo(medium.getId());
        assertThat(chain.get(0).getPreemptedIncidentId()).isEqualTo(low.getId());
        assertThat(chain.get(1).getPreemptingIncidentId()).isEqualTo(high.getId());
        assertThat(chain.get(1).getPreemptedIncidentId()).isEqualTo(medium.getId());
        assertThat(incidentRepository.findById(medium.getId()).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.PENDING);
    }

    @Test
    void arrivedDispatchCannotBePreempted() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Incident low = newIncident(Priority.LOW, "PARAMEDIC");
        Incident high = newIncident(Priority.HIGH, "PARAMEDIC");
        Dispatch lowDispatch = dispatch(low, vehicle, crew).dispatch();
        dispatchService.arrive(lowDispatch.getId());

        assertThatThrownBy(() -> dispatch(high, vehicle, crew))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_UNAVAILABLE));
        assertThat(dispatchRepository.findById(lowDispatch.getId()).orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.ACTIVE);
        assertThat(incidentRepository.findById(low.getId()).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.ON_SCENE);
    }

    @Test
    void equalOrLowerPriorityCannotPreempt() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Incident medium = newIncident(Priority.MEDIUM, "PARAMEDIC");
        Incident anotherMedium = newIncident(Priority.MEDIUM, "PARAMEDIC");
        Incident low = newIncident(Priority.LOW, "PARAMEDIC");
        dispatch(medium, vehicle, crew);

        assertThatThrownBy(() -> dispatch(anotherMedium, vehicle, crew))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_UNAVAILABLE));
        assertThatThrownBy(() -> dispatch(low, vehicle, crew))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_UNAVAILABLE));
    }

    @Test
    void failedPreemptionKeepsOriginalDispatchUnchanged() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Incident low = newIncident(Priority.LOW, "PARAMEDIC");
        Dispatch lowDispatch = dispatch(low, vehicle, crew).dispatch();
        // 高优先级事件要求的能力不满足 → 抢占整体失败
        Incident high = catalog.reportIncident("CENTRAL", "人民路 2 号", Priority.HIGH, Set.of("PARAMEDIC", "ICU"));

        assertThatThrownBy(() -> dispatch(high, vehicle, crew))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.REQUIREMENT_NOT_MET));
        // 任一步失败都保持原派遣不变
        assertThat(dispatchRepository.findById(lowDispatch.getId()).orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.ACTIVE);
        assertThat(incidentRepository.findById(low.getId()).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.DISPATCHED);
        assertThat(incidentRepository.findById(high.getId()).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.PENDING);
        assertThat(preemptionRecordRepository.findAll()).isEmpty();
    }

    @Test
    void completeReleasesResourcesAndIsTerminal() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Incident incident = newIncident(Priority.LOW, "PARAMEDIC");
        Dispatch dispatch = dispatch(incident, vehicle, crew).dispatch();
        dispatchService.arrive(dispatch.getId());

        dispatchService.complete(dispatch.getId());

        assertThat(dispatchRepository.findById(dispatch.getId()).orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.COMPLETED);
        assertThat(incidentRepository.findById(incident.getId()).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.COMPLETED);
        assertThat(vehicleRepository.findById(vehicle.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleStatus.AVAILABLE);
        assertThat(crewRepository.findById(crew.getId()).orElseThrow().getDutyStatus())
                .isEqualTo(DutyStatus.ON_DUTY);
        // 终态不可修改
        assertThatThrownBy(() -> dispatchService.complete(dispatch.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ILLEGAL_STATE));
        assertThatThrownBy(() -> dispatchService.cancel(dispatch.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ILLEGAL_STATE));
        assertThatThrownBy(() -> dispatchService.arrive(dispatch.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ILLEGAL_STATE));
        // 完成的事件不可再派遣
        assertThatThrownBy(() -> dispatch(incident, vehicle, crew))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ILLEGAL_STATE));
        // 时间线包含完整生命周期
        assertThat(dispatchService.resourceTimeline(ResourceType.VEHICLE, vehicle.getId()))
                .extracting(ResourceEvent::getEventType)
                .containsExactly(ResourceEvent.EventType.DISPATCHED,
                        ResourceEvent.EventType.ARRIVED_ON_SCENE,
                        ResourceEvent.EventType.RELEASED_BY_COMPLETION);
    }

    @Test
    void cancelReleasesResourcesAndIsTerminal() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Incident incident = newIncident(Priority.LOW, "PARAMEDIC");
        Dispatch dispatch = dispatch(incident, vehicle, crew).dispatch();

        dispatchService.cancel(dispatch.getId());

        assertThat(dispatchRepository.findById(dispatch.getId()).orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.CANCELLED);
        assertThat(incidentRepository.findById(incident.getId()).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.CANCELLED);
        assertThat(vehicleRepository.findById(vehicle.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleStatus.AVAILABLE);
        assertThat(crewRepository.findById(crew.getId()).orElseThrow().getDutyStatus())
                .isEqualTo(DutyStatus.ON_DUTY);
        assertThatThrownBy(() -> dispatchService.cancel(dispatch.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ILLEGAL_STATE));
        assertThatThrownBy(() -> dispatchService.complete(dispatch.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ILLEGAL_STATE));
    }

    @Test
    void preemptedIncidentCanBeDispatchedAgain() {
        Vehicle vehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew crew = newCrew("PARAMEDIC");
        Vehicle otherVehicle = newVehicle("CENTRAL", "DEFIBRILLATOR");
        Crew otherCrew = newCrew("PARAMEDIC");
        Incident low = newIncident(Priority.LOW, "PARAMEDIC");
        Incident high = newIncident(Priority.HIGH, "PARAMEDIC");
        dispatch(low, vehicle, crew);
        dispatch(high, vehicle, crew);

        // 被抢占的 low 恢复待派遣后，可用其他资源重新派遣
        DispatchOutcome redispatch = dispatchExecutor.execute("REQ-REDISPATCH",
                low.getId(), otherVehicle.getId(), otherCrew.getId());

        assertThat(redispatch.dispatch().getStatus()).isEqualTo(DispatchStatus.ACTIVE);
        assertThat(incidentRepository.findById(low.getId()).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.DISPATCHED);
        assertThat(dispatchService.latestDispatchOfIncident(low.getId()).getId())
                .isEqualTo(redispatch.dispatch().getId());
    }
}
