package com.chris64233.ambulancedispatch.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chris64233.ambulancedispatch.TestFixtures;
import com.chris64233.ambulancedispatch.domain.Priority;
import com.chris64233.ambulancedispatch.service.DispatchService;
import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.dto.PreemptRequest;
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
    private static final String CAPS_JSON = "[\"AED\",\"VENTILATOR\"]";

    @Autowired private MockMvc mockMvc;
    @Autowired private TestFixtures fixtures;
    @Autowired private DispatchService dispatchService;

    private String bizNo() {
        return "BIZ-" + UUID.randomUUID();
    }

    @Test
    void full_dispatch_preempt_arrive_complete_flow() throws Exception {
        long amb = fixtures.ambulance("京B1", Set.of(AREA), Set.of("AED", "VENTILATOR"));
        long crew = fixtures.crew("B一组", Set.of("AED", "VENTILATOR"), true);
        long lowEvent = fixtures.event("国贸", AREA, Priority.LOW, Set.of("AED", "VENTILATOR"));
        long highEvent = fixtures.event("三里屯", AREA, Priority.CRITICAL, Set.of("AED", "VENTILATOR"));

        String dispatchBiz = bizNo();
        String lowDispatchJson = mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","eventId":%d,"ambulanceId":%d,"crewId":%d}
                                """.formatted(dispatchBiz, lowEvent, amb, crew)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EN_ROUTE"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        long lowDispatchId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(lowDispatchJson, "$.id").toString());

        // 幂等重放
        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","eventId":%d,"ambulanceId":%d,"crewId":%d}
                                """.formatted(dispatchBiz, lowEvent, amb, crew)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(lowDispatchId))
                .andExpect(jsonPath("$.replayed").value(true));

        // 抢占
        String preemptJson = mockMvc.perform(post("/api/dispatches/preempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","newEventId":%d,"targetDispatchId":%d,
                                 "ambulanceId":%d,"crewId":%d}
                                """.formatted(bizNo(), highEvent, lowDispatchId, amb, crew)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preemptedDispatchId").value(lowDispatchId))
                .andReturn().getResponse().getContentAsString();
        long highDispatchId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(preemptJson, "$.id").toString());

        mockMvc.perform(post("/api/dispatches/{id}/arrive", highDispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_SCENE"));
        mockMvc.perform(post("/api/dispatches/{id}/complete", highDispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 详情 / 时间线 / 抢占链
        mockMvc.perform(get("/api/events/{id}/dispatch-detail", highEvent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDispatch").doesNotExist())
                .andExpect(jsonPath("$.dispatches[0].status").value("COMPLETED"));
        mockMvc.perform(get("/api/ambulances/{id}/timeline", amb))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].action").value("ASSIGNED"))
                .andExpect(jsonPath("$[4].action").value("RELEASED"));
        mockMvc.perform(get("/api/dispatches/{id}/preemption-chain", highDispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].eventPriority").value("CRITICAL"));
    }

    @Test
    void concurrent_conflict_returns_unified_error_body() throws Exception {
        long amb = fixtures.ambulance("京B2", Set.of(AREA), Set.of("AED"));
        long crew = fixtures.crew("B二组", Set.of("AED"), true);
        long event1 = fixtures.event("国贸1", AREA, Priority.NORMAL, Set.of("AED"));
        long event2 = fixtures.event("国贸2", AREA, Priority.NORMAL, Set.of("AED"));

        dispatchService.dispatch(new DispatchRequest(bizNo(), event1, amb, crew));

        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","eventId":%d,"ambulanceId":%d,"crewId":%d}
                                """.formatted(bizNo(), event2, amb, crew)))
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
        long event1 = fixtures.event("国贸1", AREA, Priority.NORMAL, Set.of("AED"));
        long event2 = fixtures.event("国贸2", AREA, Priority.NORMAL, Set.of("AED"));
        String bizNo = bizNo();

        dispatchService.dispatch(new DispatchRequest(bizNo, event1, amb, crew));

        mockMvc.perform(post("/api/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","eventId":%d,"ambulanceId":%d,"crewId":%d}
                                """.formatted(bizNo, event2, amb, crew)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_CONFLICT"));
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
    }

    @Test
    void preempt_after_arrive_returns_409_and_chain_intact() throws Exception {
        long amb = fixtures.ambulance("京B4", Set.of(AREA), Set.of("AED"));
        long crew = fixtures.crew("B四组", Set.of("AED"), true);
        long lowEvent = fixtures.event("国贸", AREA, Priority.LOW, Set.of("AED"));
        long highEvent = fixtures.event("三里屯", AREA, Priority.CRITICAL, Set.of("AED"));

        var low = dispatchService.dispatch(new DispatchRequest(bizNo(), lowEvent, amb, crew));
        dispatchService.arrive(low.id());

        mockMvc.perform(post("/api/dispatches/preempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizNo":"%s","newEventId":%d,"targetDispatchId":%d,
                                 "ambulanceId":%d,"crewId":%d}
                                """.formatted(bizNo(), highEvent, low.id(), amb, crew)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPATCH_ALREADY_ARRIVED"));

        // 原事件详情仍然是进行中的派遣
        mockMvc.perform(get("/api/events/{id}/dispatch-detail", lowEvent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDispatch.status").value("ON_SCENE"));
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
                                {"location":"望京","serviceArea":"%s","priority":"HIGH",
                                 "requiredCapabilities":%s}
                                """.formatted(AREA, CAPS_JSON)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }
}
