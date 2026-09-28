package com.chris64233.ambulancedispatch;

import com.chris64233.ambulancedispatch.domain.Priority;
import com.chris64233.ambulancedispatch.dto.CreateEventRequest;
import com.chris64233.ambulancedispatch.dto.RegisterAmbulanceRequest;
import com.chris64233.ambulancedispatch.dto.RegisterCrewRequest;
import com.chris64233.ambulancedispatch.dto.RegisterHospitalRequest;
import com.chris64233.ambulancedispatch.dto.UpdateHospitalRequest;
import com.chris64233.ambulancedispatch.service.CatalogService;
import com.chris64233.ambulancedispatch.service.HospitalService;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 测试数据构造助手。
 */
@Component
public class TestFixtures {

    public static final String TYPE_TRAUMA = "TRAUMA";
    public static final String TYPE_CARDIAC = "CARDIAC";
    public static final String TYPE_GENERAL = "GENERAL";

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private HospitalService hospitalService;

    public Long ambulance(String plate, Set<String> areas, Set<String> equipment) {
        return catalogService.registerAmbulance(
                new RegisterAmbulanceRequest(plate, areas, equipment)).getId();
    }

    public Long crew(String name, Set<String> qualifications, boolean onDuty) {
        return catalogService.registerCrew(
                new RegisterCrewRequest(name, qualifications, onDuty)).getId();
    }

    public Long event(String location, String area, Priority priority, Set<String> capabilities) {
        return event(location, area, TYPE_GENERAL, priority, capabilities);
    }

    public Long event(String location, String area, String emergencyType,
                      Priority priority, Set<String> capabilities) {
        return catalogService.createEvent(
                new CreateEventRequest(location, area, emergencyType, priority, capabilities)).getId();
    }

    public Long hospital(String name, Set<String> types, int capacity) {
        return hospital(name, types, capacity, "OPEN");
    }

    public Long hospital(String name, Set<String> types, int capacity, String receivingStatus) {
        // 测试共享同一个内存库，追加唯一后缀避免跨测试重名
        String uniqueName = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        return hospitalService.register(
                new RegisterHospitalRequest(uniqueName, types, capacity, receivingStatus)).getId();
    }

    public void updateHospital(Long id, Set<String> types, Integer capacity, String status) {
        hospitalService.update(id, new UpdateHospitalRequest(types, capacity, status));
    }
}
