package com.chris64233.ambulancedispatch;

import com.chris64233.ambulancedispatch.api.error.ApiException;
import com.chris64233.ambulancedispatch.domain.Crew;
import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.domain.DispatchStatus;
import com.chris64233.ambulancedispatch.domain.DutyStatus;
import com.chris64233.ambulancedispatch.domain.Incident;
import com.chris64233.ambulancedispatch.domain.IncidentStatus;
import com.chris64233.ambulancedispatch.domain.Priority;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发争抢测试：多个事件同时争抢同一车辆与救护组时，
 * 只能有一个成功，且不会出现车辆与人员分别被不同事件占用。
 */
@SpringBootTest
class DispatchConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    CatalogService catalog;
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

    private Vehicle newVehicle() {
        return catalog.registerVehicle("V-" + UUID.randomUUID(), "CENTRAL", Set.of("DEFIBRILLATOR"));
    }

    private Crew newCrew() {
        return catalog.registerCrew("C-" + UUID.randomUUID(), Set.of("PARAMEDIC"));
    }

    private Incident newIncident(Priority priority) {
        return catalog.reportIncident("CENTRAL", "人民路 1 号", priority, Set.of("PARAMEDIC"));
    }

    /** 并发执行任务（统一放行闸门），成功返回 DispatchOutcome，失败返回抛出的异常。 */
    private List<Object> runConcurrently(List<java.util.concurrent.Callable<DispatchOutcome>> tasks)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (var task : tasks) {
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return results;
    }

    @Test
    void concurrentDispatchesOnSameResourcesOnlyOneSucceeds() throws Exception {
        Vehicle vehicle = newVehicle();
        Crew crew = newCrew();
        List<java.util.concurrent.Callable<DispatchOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Incident incident = newIncident(Priority.LOW);
            String requestId = "REQ-RACE-" + i;
            tasks.add(() -> dispatchExecutor.execute(requestId, incident.getId(), vehicle.getId(), crew.getId()));
        }

        List<Object> results = runConcurrently(tasks);

        List<DispatchOutcome> successes = results.stream()
                .filter(DispatchOutcome.class::isInstance)
                .map(DispatchOutcome.class::cast)
                .toList();
        List<Object> failures = results.stream()
                .filter(ApiException.class::isInstance)
                .toList();
        // 只能有一个成功，其余全部因资源被占用而失败
        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(THREADS - 1);

        // 最终状态一致：车辆与救护组被同一个派遣整体占用
        Dispatch winner = successes.get(0).dispatch();
        assertThat(vehicleRepository.findById(vehicle.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleStatus.DISPATCHED);
        assertThat(crewRepository.findById(crew.getId()).orElseThrow().getDutyStatus())
                .isEqualTo(DutyStatus.DISPATCHED);
        assertThat(dispatchRepository.findByVehicleIdAndStatus(vehicle.getId(), DispatchStatus.ACTIVE))
                .hasValueSatisfying(d -> assertThat(d.getId()).isEqualTo(winner.getId()));
        assertThat(dispatchRepository.findByCrewIdAndStatus(crew.getId(), DispatchStatus.ACTIVE))
                .hasValueSatisfying(d -> assertThat(d.getId()).isEqualTo(winner.getId()));
        assertThat(dispatchRepository.findAll()).hasSize(1);
    }

    @Test
    void concurrentSameRequestIdIsIdempotent() throws Exception {
        Vehicle vehicle = newVehicle();
        Crew crew = newCrew();
        Incident incident = newIncident(Priority.LOW);
        String requestId = "REQ-SAME";
        List<java.util.concurrent.Callable<DispatchOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> dispatchExecutor.execute(requestId, incident.getId(), vehicle.getId(), crew.getId()));
        }

        List<Object> results = runConcurrently(tasks);

        // 相同内容并发提交：全部成功且返回同一派遣
        List<DispatchOutcome> outcomes = results.stream()
                .filter(DispatchOutcome.class::isInstance)
                .map(DispatchOutcome.class::cast)
                .toList();
        assertThat(outcomes)
                .as("结果类型: %s", results.stream().map(r -> r.getClass().getName() + ": " + r).toList())
                .hasSize(THREADS);
        assertThat(outcomes).allSatisfy(o ->
                assertThat(o.dispatch().getId()).isEqualTo(outcomes.get(0).dispatch().getId()));
        assertThat(dispatchRepository.findAll()).hasSize(1);
    }

    @Test
    void concurrentPreemptionsOnlyOneSucceeds() throws Exception {
        Vehicle vehicle = newVehicle();
        Crew crew = newCrew();
        Incident low = newIncident(Priority.LOW);
        dispatchExecutor.execute("REQ-LOW", low.getId(), vehicle.getId(), crew.getId());

        List<Incident> highIncidents = new ArrayList<>();
        List<java.util.concurrent.Callable<DispatchOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Incident high = newIncident(Priority.HIGH);
            highIncidents.add(high);
            String requestId = "REQ-HIGH-" + i;
            tasks.add(() -> dispatchExecutor.execute(requestId, high.getId(), vehicle.getId(), crew.getId()));
        }

        List<Object> results = runConcurrently(tasks);

        // 多个高优先级事件同时抢占：只有一个成功
        List<DispatchOutcome> successes = results.stream()
                .filter(DispatchOutcome.class::isInstance)
                .map(DispatchOutcome.class::cast)
                .toList();
        assertThat(successes).hasSize(1);
        // 数据一致：同一时刻只有一个活跃派遣，车辆与救护组归属同一派遣
        assertThat(dispatchRepository.findAll().stream()
                .filter(d -> d.getStatus() == DispatchStatus.ACTIVE))
                .hasSize(1);
        Dispatch active = dispatchRepository.findByVehicleIdAndStatus(vehicle.getId(), DispatchStatus.ACTIVE)
                .orElseThrow();
        assertThat(dispatchRepository.findByCrewIdAndStatus(crew.getId(), DispatchStatus.ACTIVE))
                .hasValueSatisfying(d -> assertThat(d.getId()).isEqualTo(active.getId()));
        // 原低优先级事件被抢占回待派遣，且只有一条抢占记录
        assertThat(incidentRepository.findById(low.getId()).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.PENDING);
        assertThat(preemptionRecordRepository.findAll()).hasSize(1);
        // 未抢到资源的高优先级事件仍处于待派遣
        long dispatchedHigh = highIncidents.stream()
                .map(i -> incidentRepository.findById(i.getId()).orElseThrow().getStatus())
                .filter(s -> s == IncidentStatus.DISPATCHED)
                .count();
        assertThat(dispatchedHigh).isEqualTo(1);
    }
}
