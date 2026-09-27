package com.chris64233.ambulancedispatch;

import com.chris64233.ambulancedispatch.repository.CrewRepository;
import com.chris64233.ambulancedispatch.repository.DispatchRepository;
import com.chris64233.ambulancedispatch.repository.IncidentRepository;
import com.chris64233.ambulancedispatch.repository.PreemptionRecordRepository;
import com.chris64233.ambulancedispatch.repository.ResourceEventRepository;
import com.chris64233.ambulancedispatch.repository.VehicleRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST API 集成测试：完整派遣流程、幂等语义、抢占、统一错误响应与查询接口。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DispatchApiTest {

    @Autowired
    MockMvc mockMvc;
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

    private long createVehicle(String area, String equipment) throws Exception {
        String body = """
                {"callSign": "%s", "serviceArea": "%s", "equipment": [%s]}
                """.formatted("V-" + UUID.randomUUID(), area, equipment);
        MvcResult result = mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long createCrew(String qualifications) throws Exception {
        String body = """
                {"name": "%s", "qualifications": [%s]}
                """.formatted("C-" + UUID.randomUUID(), qualifications);
        MvcResult result = mockMvc.perform(post("/api/crews")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long createIncident(String priority, String required) throws Exception {
        String body = """
                {"area": "CENTRAL", "location": "人民路 1 号", "priority": "%s", "requiredCapabilities": [%s]}
                """.formatted(priority, required);
        MvcResult result = mockMvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String dispatchBody(String requestId, long incidentId, long vehicleId, long crewId) {
        return """
                {"requestId": "%s", "incidentId": %d, "vehicleId": %d, "crewId": %d}
                """.formatted(requestId, incidentId, vehicleId, crewId);
    }

    @Test
    void fullDispatchLifecycleOverApi() throws Exception {
        long vehicleId = createVehicle("CENTRAL", "\"DEFIBRILLATOR\"");
        long crewId = createCrew("\"PARAMEDIC\"");
        long incidentId = createIncident("LOW", "\"PARAMEDIC\"");

        // 创建派遣 → 201
        String requestId = "REQ-API-1";
        MvcResult created = mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(requestId, incidentId, vehicleId, crewId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.vehicleId").value(vehicleId))
                .andExpect(jsonPath("$.crewId").value(crewId))
                .andReturn();
        long dispatchId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        // 相同业务号 + 相同内容 → 200 返回原派遣
        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(requestId, incidentId, vehicleId, crewId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dispatchId));

        // 相同业务号 + 不同内容 → 409 统一错误体
        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(requestId, incidentId, vehicleId, createCrew("\"PARAMEDIC\""))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isString());

        // 派遣详情查询
        mockMvc.perform(get("/api/dispatches/{id}", dispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId));
        mockMvc.perform(get("/api/incidents/{id}/dispatch", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dispatchId));

        // 资源时间线
        mockMvc.perform(get("/api/vehicles/{id}/timeline", vehicleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].eventType").value("DISPATCHED"));

        // 到场 → 完成
        mockMvc.perform(post("/api/dispatches/{id}/arrive", dispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivedAt").isNotEmpty());
        mockMvc.perform(post("/api/dispatches/{id}/complete", dispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 终态不可修改
        mockMvc.perform(post("/api/dispatches/{id}/cancel", dispatchId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"));
    }

    @Test
    void preemptionOverApiAndChainQuery() throws Exception {
        long vehicleId = createVehicle("CENTRAL", "\"DEFIBRILLATOR\"");
        long crewId = createCrew("\"PARAMEDIC\"");
        long lowIncident = createIncident("LOW", "\"PARAMEDIC\"");
        long highIncident = createIncident("HIGH", "\"PARAMEDIC\"");

        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody("REQ-LOW", lowIncident, vehicleId, crewId)))
                .andExpect(status().isCreated());

        // 高优先级事件抢占 → 201
        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody("REQ-HIGH", highIncident, vehicleId, crewId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 原事件恢复待派遣
        mockMvc.perform(get("/api/incidents/{id}", lowIncident))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 抢占链查询
        mockMvc.perform(get("/api/incidents/{id}/preemption-chain", lowIncident))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].preemptingIncidentId").value(highIncident))
                .andExpect(jsonPath("$[0].preemptedIncidentId").value(lowIncident));

        // 车辆时间线包含抢占转移事件
        mockMvc.perform(get("/api/vehicles/{id}/timeline", vehicleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].eventType").value("TRANSFERRED_BY_PREEMPTION"));
    }

    @Test
    void arrivedDispatchCannotBePreemptedOverApi() throws Exception {
        long vehicleId = createVehicle("CENTRAL", "\"DEFIBRILLATOR\"");
        long crewId = createCrew("\"PARAMEDIC\"");
        long lowIncident = createIncident("LOW", "\"PARAMEDIC\"");
        long highIncident = createIncident("HIGH", "\"PARAMEDIC\"");

        MvcResult created = mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody("REQ-LOW", lowIncident, vehicleId, crewId)))
                .andExpect(status().isCreated())
                .andReturn();
        long dispatchId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(post("/api/dispatches/{id}/arrive", dispatchId))
                .andExpect(status().isOk());

        // 已到场 → 不可抢占
        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody("REQ-HIGH", highIncident, vehicleId, crewId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_UNAVAILABLE"));
    }

    @Test
    void unifiedErrorResponses() throws Exception {
        // 404：资源不存在
        mockMvc.perform(get("/api/vehicles/{id}", 99999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.path").value("/api/vehicles/99999"));

        // 400：请求体校验失败
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"callSign\": \"\", \"serviceArea\": \"CENTRAL\", \"equipment\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isMap());

        // 422：资源不满足事件要求（区域不匹配）
        long vehicleId = createVehicle("EAST", "\"DEFIBRILLATOR\"");
        long crewId = createCrew("\"PARAMEDIC\"");
        long incidentId = createIncident("LOW", "\"PARAMEDIC\"");
        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody("REQ-AREA", incidentId, vehicleId, crewId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REQUIREMENT_NOT_MET"));
    }
}
