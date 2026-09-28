package com.chris64233.ambulancedispatch.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chris64233.ambulancedispatch.TestFixtures;
import com.chris64233.ambulancedispatch.domain.Priority;
import com.chris64233.ambulancedispatch.service.DispatchService;
import com.chris64233.ambulancedispatch.dto.DivertRequest;
import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.dto.PreemptRequest;
import com.chris64233.ambulancedispatch.dto.RejectRequest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DispatchApiTest {

    private static final String AREA = "朝阳区";

    @Autowired private MockMvc mockMvc;
    @Autowired private TestFixtures fixtures;
    @Autowired private DispatchService dispatchService;

    private String bizNo() {
        return "BIZ-" + UUID.randomUUID();
    }

    @Test
    void full_dispatch_preempt_arrive_divert_reject_complete_flow() throws Exception {
        long amb = fixtures.ambulance("京B1", Set.of(AREA), Set.of("AED", "VENTILATOR"));
        long crew = fixtures.crew("B一组", Set.of("AED", "VENTILATOR"), true);
        long lowEvent = fixtures.event("国贸", AREA, Priority.LOW, Set.of("AED", "VENTILATOR"));
        long highEvent = fixtures.event("三里屯", AREA, Priority.CRITICAL, Set.of("AED", "VENTILATOR"));
        long hospital1 = fixtures.hospital("朝阳医院", Set.of("GENERAL"), 10);
        long hospital2 = fixtures.hospital("急救中心", Set.of("GENERAL"), 10);
        long hospital3 = fixtures.hospital("仁和医院", Set.of("GENERAL"), 10);

        String dispatchBiz = bizNo();
        String lowDispatchJson = mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","eventId":%d,"ambulanceId":%d,"crewId":%d,"hospitalId":%d}
                                """.formatted(dispatchBiz, lowEvent, amb, crew, hospital1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EN_ROUTE"))
                .andExpect(jsonPath("$.hospitalId").value(hospital1))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        long lowDispatchId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(lowDispatchJson, "$.id").toString());

        // 幂等重放
        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","eventId":%d,"ambulanceId":%d,"crewId":%d,"hospitalId":%d}
                                """.formatted(dispatchBiz, lowEvent, amb, crew, hospital1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(lowDispatchId))
                .andExpect(jsonPath("$.replayed").value(true));

        // 抢占（携带新目的医院）
        String preemptJson = mockMvc.perform(post("/api/dispatches/preempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","newEventId":%d,"targetDispatchId":%d,
                                 "ambulanceId":%d,"crewId":%d,"hospitalId":%d}
                                """.formatted(bizNo(), highEvent, lowDispatchId, amb, crew, hospital2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preemptedDispatchId").value(lowDispatchId))
                .andExpect(jsonPath("$.hospitalId").value(hospital2))
                .andReturn().getResponse().getContentAsString();
        long highDispatchId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(preemptJson, "$.id").toString());

        mockMvc.perform(post("/api/dispatches/{id}/arrive", highDispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_SCENE"));

        // 医院拒收：记录原因、生成 PENDING 改派任务，不释放车组
        String rejectJson = mockMvc.perform(post("/api/dispatches/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","dispatchId":%d,"hospitalId":%d,"reasonDetail":"无空床"}
                                """.formatted(bizNo(), highDispatchId, hospital2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("HOSPITAL_REJECTED"))
                .andExpect(jsonPath("$.reasonDetail").value("无空床"))
                .andReturn().getResponse().getContentAsString();
        long rejectionId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(rejectJson, "$.id").toString());

        // 改派到第三家医院：新院预留成功后释放原院名额
        String divertJson = mockMvc.perform(post("/api/dispatches/divert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","dispatchId":%d,"fromHospitalId":%d,"toHospitalId":%d,
                                 "reason":"HOSPITAL_CLOSED","reasonDetail":"原院停收"}
                                """.formatted(bizNo(), highDispatchId, hospital2, hospital3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.toHospitalId").value(hospital3))
                .andReturn().getResponse().getContentAsString();
        long diversionId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(divertJson, "$.id").toString());

        // 拒收任务被成功改派消化
        mockMvc.perform(get("/api/diversions/{id}", rejectionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        mockMvc.perform(get("/api/dispatches/{id}/diversions", highDispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].reason").value("HOSPITAL_REJECTED"))
                .andExpect(jsonPath("$[1].id").value(diversionId));

        // 到院后完成，释放车组与新院床位
        mockMvc.perform(post("/api/dispatches/{id}/arrive-hospital", highDispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivedAtHospitalAt").exists());
        // 已到院不能再改派
        mockMvc.perform(post("/api/dispatches/divert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","dispatchId":%d,"fromHospitalId":%d,"toHospitalId":%d,
                                 "reason":"CONDITION_CHANGED"}
                                """.formatted(bizNo(), highDispatchId, hospital3, hospital1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIVERSION_ALREADY_AT_HOSPITAL"));
        mockMvc.perform(post("/api/dispatches/{id}/complete", highDispatchId))
                .andExpect(status().is(200))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 详情 / 时间线 / 抢占链
        mockMvc.perform(get("/api/events/{id}/dispatch-detail", highEvent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDispatch").doesNotExist())
                .andExpect(jsonPath("$.dispatches[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.diversions.length()").value(2))
                .andExpect(jsonPath("$.timeline.length()").value(org.hamcrest.Matchers.greaterThan(5)))
                .andExpect(jsonPath("$.timeline[?(@.action=='REJECTED')]").exists());
        mockMvc.perform(get("/api/ambulances/{id}/timeline", amb))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("ASSIGNED"));
        mockMvc.perform(get("/api/hospitals/{id}/timeline", hospital3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='DIVERTED')]").exists())
                .andExpect(jsonPath("$[?(@.action=='ARRIVED_HOSPITAL')]").exists());
        mockMvc.perform(get("/api/dispatches/{id}/preemption-chain", highDispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].eventPriority").value("CRITICAL"))
                .andExpect(jsonPath("$[1].hospitalId").value(hospital3));
    }

    @Test
    void hospital_registration_update_and_capacity() throws Exception {
        String hospitalJson = mockMvc.perform(post("/api/hospitals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"特区医院","acceptedEmergencyTypes":["GENERAL","TRAUMA"],
                                 "bedCapacity":8,"receivingStatus":"OPEN"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bedCapacity").value(8))
                .andExpect(jsonPath("$.reservedBeds").value(0))
                .andExpect(jsonPath("$.availableBeds").value(8))
                .andExpect(jsonPath("$.receivingStatus").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        long hospitalId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(hospitalJson, "$.id").toString());

        // 关闭接收
        mockMvc.perform(put("/api/hospitals/{id}", hospitalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivingStatus\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivingStatus").value("CLOSED"));

        // 容量不能小于已预留床位
        mockMvc.perform(put("/api/hospitals/{id}", hospitalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bedCapacity\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bedCapacity").value(0));
        mockMvc.perform(put("/api/hospitals/{id}", hospitalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivingStatus\":\"OPEN\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void dispatch_to_closed_hospital_returns_409() throws Exception {
        long amb = fixtures.ambulance("京B6", Set.of(AREA), Set.of("AED"));
        long crew = fixtures.crew("B六组", Set.of("AED"), true);
        long event = fixtures.event("国贸", AREA, Priority.NORMAL, Set.of("AED"));
        long hospital = fixtures.hospital("闭院B", Set.of("GENERAL"), 5, "CLOSED");

        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","eventId":%d,"ambulanceId":%d,"crewId":%d,"hospitalId":%d}
                                """.formatted(bizNo(), event, amb, crew, hospital)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOSPITAL_CLOSED"));
    }

    @Test
    void concurrent_conflict_returns_unified_error_body() throws Exception {
        long amb = fixtures.ambulance("京B2", Set.of(AREA), Set.of("AED"));
        long crew = fixtures.crew("B二组", Set.of("AED"), true);
        long hospital = fixtures.hospital("二院B", Set.of("GENERAL"), 10);
        long event1 = fixtures.event("国贸1", AREA, Priority.NORMAL, Set.of("AED"));
        long event2 = fixtures.event("国贸2", AREA, Priority.NORMAL, Set.of("AED"));

        dispatchService.dispatch(new DispatchRequest(bizNo(), event1, amb, crew, hospital));

        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","eventId":%d,"ambulanceId":%d,"crewId":%d,"hospitalId":%d}
                                """.formatted(bizNo(), event2, amb, crew, hospital)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.path").value("/api/dispatches"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void idempotent_conflict_returns_409() throws Exception {
        long amb = fixtures.ambulance("京B3", Set.of(AREA), Set.of("AED"));
        long crew = fixtures.crew("B三组", Set.of("AED"), true);
        long hospital = fixtures.hospital("三院B", Set.of("GENERAL"), 10);
        long event1 = fixtures.event("国贸1", AREA, Priority.NORMAL, Set.of("AED"));
        long event2 = fixtures.event("国贸2", AREA, Priority.NORMAL, Set.of("AED"));
        String bizNo = bizNo();

        dispatchService.dispatch(new DispatchRequest(bizNo, event1, amb, crew, hospital));

        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","eventId":%d,"ambulanceId":%d,"crewId":%d,"hospitalId":%d}
                                """.formatted(bizNo, event2, amb, crew, hospital)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_CONFLICT"));
    }

    @Test
    void divert_failure_replays_and_returns_first_error() throws Exception {
        long amb = fixtures.ambulance("京B7", Set.of(AREA), Set.of("AED"));
        long crew = fixtures.crew("B七组", Set.of("AED"), true);
        long event = fixtures.event("国贸", AREA, Priority.NORMAL, Set.of("AED"));
        long h1 = fixtures.hospital("七院一", Set.of("GENERAL"), 10);
        long h2 = fixtures.hospital("七院二", Set.of("GENERAL"), 10, "CLOSED");
        var d = dispatchService.dispatch(new DispatchRequest(bizNo(), event, amb, crew, h1));
        dispatchService.arrive(d.id());
        String bizNo = bizNo();
        String body = """
                {"bizNo":"%s","dispatchId":%d,"fromHospitalId":%d,"toHospitalId":%d,
                 "reason":"HOSPITAL_CLOSED"}
                """.formatted(bizNo, d.id(), h1, h2);

        mockMvc.perform(post("/api/dispatches/divert").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOSPITAL_CLOSED"));
        // 同业务号同内容重放：仍是首次失败结果
        mockMvc.perform(post("/api/dispatches/divert").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOSPITAL_CLOSED"));
    }

    @Test
    void validation_error_returns_unified_400() throws Exception {
        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bizNo\":\"\",\"eventId\":1,\"ambulanceId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void not_found_returns_unified_404() throws Exception {
        mockMvc.perform(get("/api/dispatches/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DISPATCH_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(get("/api/hospitals/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOSPITAL_NOT_FOUND"));
    }

    @Test
    void preempt_after_arrive_returns_409_and_chain_intact() throws Exception {
        long amb = fixtures.ambulance("京B4", Set.of(AREA), Set.of("AED"));
        long crew = fixtures.crew("B四组", Set.of("AED"), true);
        long hospital = fixtures.hospital("四院B", Set.of("GENERAL"), 10);
        long lowEvent = fixtures.event("国贸", AREA, Priority.LOW, Set.of("AED"));
        long highEvent = fixtures.event("三里屯", AREA, Priority.CRITICAL, Set.of("AED"));

        var low = dispatchService.dispatch(new DispatchRequest(bizNo(), lowEvent, amb, crew, hospital));
        dispatchService.arrive(low.id());

        mockMvc.perform(post("/api/dispatches/preempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","newEventId":%d,"targetDispatchId":%d,
                                 "ambulanceId":%d,"crewId":%d,"hospitalId":%d}
                                """.formatted(bizNo(), highEvent, low.id(), amb, crew, hospital)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPATCH_ALREADY_ARRIVED"));

        // 原事件详情仍然是进行中的派遣，医院名额保留
        mockMvc.perform(get("/api/events/{id}/dispatch-detail", lowEvent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDispatch.status").value("ON_SCENE"))
                .andExpect(jsonPath("$.currentHospital.id").value(hospital));
    }

    @Test
    void register_entities_and_query() throws Exception {
        mockMvc.perform(post("/api/ambulances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"plateNumber":"京B5","serviceAreas":["%s"],
                                 "equipmentCapabilities":["AED"]}
                                """.formatted(AREA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.serviceAreas[0]").value(AREA));

        mockMvc.perform(post("/api/crews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"B五组","qualifications":["AED"],"onDuty":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dutyStatus").value("ON_DUTY"))
                .andExpect(jsonPath("$.assignmentStatus").value("IDLE"));

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"location":"望京","serviceArea":"%s","emergencyType":"TRAUMA",
                                 "priority":"HIGH","requiredCapabilities":["AED"]}
                                """.formatted(AREA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.emergencyType").value("TRAUMA"));
    }
}
