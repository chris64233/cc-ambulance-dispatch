package com.chris64233.ambulancedispatch;

import com.chris64233.ambulancedispatch.domain.Priority;
import com.chris64233.ambulancedispatch.dto.CreateEventRequest;
import com.chris64233.ambulancedispatch.dto.RegisterAmbulanceRequest;
import com.chris64233.ambulancedispatch.dto.RegisterCrewRequest;
import com.chris64233.ambulancedispatch.service.CatalogService;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 测试数据构造助手。
 */
@Component
public class TestFixtures {

    @Autowired
    private CatalogService catalogService;

    public Long ambulance(String plate, Set<String> areas, Set<String> equipment) {
        return catalogService.registerAmbulance(
                new RegisterAmbulanceRequest(plate, areas, equipment)).getId();
    }

    public Long crew(String name, Set<String> qualifications, boolean onDuty) {
        return catalogService.registerCrew(
                new RegisterCrewRequest(name, qualifications, onDuty)).getId();
    }

    public Long event(String location, String area, Priority priority, Set<String> capabilities) {
        return catalogService.createEvent(
                new CreateEventRequest(location, area, priority, capabilities)).getId();
    }
}
