package com.chris64233.ambulancedispatch.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chris64233.ambulancedispatch.TestFixtures;
import com.chris64233.ambulancedispatch.domain.DiversionReason;
import com.chris64233.ambulancedispatch.domain.EmergencyType;
import com.chris64233.ambulancedispatch.domain.Priority;
import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.service.DispatchService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 医院上报、拒收、途中改派与事件完整时间线的 HTTP 接口测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HospitalDiversionApiTest {

    private static final String AREA = "西城区";
    private static final String CAPS = "[\"AED\"]";

    @Autowired private MockMvc mockMvc;
    @Autowired private TestFixtures fixtures;
    @Autowired private DispatchService dispatchService;

    private String bizNo() {
        return "BIZ-" + UUID.randomUUID();
    }

    private long registerHospital(String name, int capacity, String status) throws Exception {
        String body = """
                {"name":"%s","acceptedEmergencyTypes":["GENERAL","CARDIAC"],
                 "bedCapacity":%d}""".formatted(name, capacity);
        String json = mockMvc.perform(post("/api/hospitals")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receivingStatus").value("OPEN"))
                .andExpect(jsonPath("$.availableBeds").value(capacity))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(com.jayway.jsonpath.JsonPath.read(json, "$.id").toString());
        if (status != null) {
            mockMvc.perform(patch("/api/hospitals/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"receivingStatus\":\"%s\"}".formatted(status)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.receivingStatus").value(status));
        }
        return id;
    }

    private long dispatchedEventOnScene(long hospitalId) {
        long amb = fixtures.ambulance("京HD-" + UUID.randomUUID(), Set.of(AREA), Set.of("AED"));
        long crew = fixtures.crew("HD组" + UUID.randomUUID(), Set.of("AED"), true);
        long event = fixtures.event("西直门", AREA, Priority.NORMAL,
                EmergencyType.GENERAL, Set.of("AED"));
        var d = dispatchService.dispatch(new DispatchRequest(bizNo(), event, amb, crew, hospitalId));
        dispatchService.arrive(d.id());
        return d.id();
    }

    @Test
    void dispatch_to_closed_hospital_returns_409() throws Exception {
        long closed = registerHospital("关闭API院", 3, "CLOSED");
        long amb = fixtures.ambulance("京HX", Set.of(AREA), Set.of("AED"));
        long crew = fixtures.crew("HX组", Set.of("AED"), true);
        long event = fixtures.event("西直门", AREA, Priority.NORMAL, Set.of("AED"));

        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","eventId":%d,"ambulanceId":%d,
                                 "crewId":%d,"hospitalId":%d}"""
                                .formatted(bizNo(), event, amb, crew, closed)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOSPITAL_NOT_RECEIVING"));
    }

    @Test
    void reject_then_divert_full_http_flow() throws Exception {
        long h1 = registerHospital("拒收API院", 2, null);
        long h2 = registerHospital("改派API院", 2, null);
        long dispatchId = dispatchedEventOnScene(h1);

        // 拒收：生成改派任务
        String rejectJson = mockMvc.perform(post("/api/dispatches/{id}/reject", dispatchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"影像设备故障\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reason").value("影像设备故障"))
                .andExpect(jsonPath("$.diversionTaskId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long taskId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(rejectJson, "$.diversionTaskId").toString());

        mockMvc.perform(get("/api/diversion-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)].status", taskId).value("PENDING"));

        // 改派成功
        mockMvc.perform(post("/api/dispatches/divert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","dispatchId":%d,"toHospitalId":%d,
                                 "reason":"REJECTED","expectedHospitalId":%d}"""
                                .formatted(bizNo(), dispatchId, h2, h1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.toHospitalId").value(h2));

        // 任务关闭
        mockMvc.perform(get("/api/diversion-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]", taskId).doesNotExist());

        // 到达医院后再改派被拒
        mockMvc.perform(post("/api/dispatches/{id}/arrive-hospital", dispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AT_HOSPITAL"));
        long h3 = registerHospital("迟到院", 2, null);
        mockMvc.perform(post("/api/dispatches/divert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","dispatchId":%d,"toHospitalId":%d,
                                 "reason":"HOSPITAL_CLOSED","expectedHospitalId":%d}"""
                                .formatted(bizNo(), dispatchId, h3, h2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIVERSION_ALREADY_ARRIVED"));
    }

    @Test
    void divert_to_full_hospital_returns_failed_record_not_error() throws Exception {
        long h1 = registerHospital("原满院", 2, null);
        long h2 = registerHospital("零床院", 0, null);
        long dispatchId = dispatchedEventOnScene(h1);
        String biz = bizNo();

        mockMvc.perform(post("/api/dispatches/divert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","dispatchId":%d,"toHospitalId":%d,
                                 "reason":"CONDITION_CHANGED","emergencyType":"GENERAL",
                                 "expectedHospitalId":%d}"""
                                .formatted(biz, dispatchId, h2, h1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failMessage").isNotEmpty());

        // 同号一致重放返回首次失败结果
        mockMvc.perform(post("/api/dispatches/divert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","dispatchId":%d,"toHospitalId":%d,
                                 "reason":"CONDITION_CHANGED","emergencyType":"GENERAL",
                                 "expectedHospitalId":%d}"""
                                .formatted(biz, dispatchId, h2, h1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void stale_diversion_returns_destination_conflict() throws Exception {
        long h1 = registerHospital("旧院", 2, null);
        long h2 = registerHospital("新院", 2, null);
        long h3 = registerHospital("旧请求院", 2, null);
        long dispatchId = dispatchedEventOnScene(h1);

        dispatchService.divert(new com.chris64233.ambulancedispatch.dto.DiversionRequest(
                bizNo(), dispatchId, h2, DiversionReason.HOSPITAL_CLOSED, null, h1));

        // 旧请求仍携带 expectedHospitalId=h1
        mockMvc.perform(post("/api/dispatches/divert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","dispatchId":%d,"toHospitalId":%d,
                                 "reason":"HOSPITAL_CLOSED","expectedHospitalId":%d}"""
                                .formatted(bizNo(), dispatchId, h3, h1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIVERSION_DESTINATION_CONFLICT"));
    }

    @Test
    void update_hospital_capacity_below_reserved_returns_400() throws Exception {
        long h = registerHospital("容量API院", 5, null);
        dispatchedEventOnScene(h); // 占 1

        mockMvc.perform(patch("/api/hospitals/{id}", h)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bedCapacity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void duplicate_hospital_name_returns_409_not_500() throws Exception {
        String name = "同名院" + UUID.randomUUID();
        mockMvc.perform(post("/api/hospitals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","acceptedEmergencyTypes":["GENERAL"],"bedCapacity":3}"""
                                .formatted(name)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/hospitals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","acceptedEmergencyTypes":["GENERAL"],"bedCapacity":3}"""
                                .formatted(name)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }
}
