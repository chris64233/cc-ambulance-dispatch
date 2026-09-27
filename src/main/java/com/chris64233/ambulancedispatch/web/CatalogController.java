package com.chris64233.ambulancedispatch.web;

import com.chris64233.ambulancedispatch.dto.AmbulanceResponse;
import com.chris64233.ambulancedispatch.dto.CrewResponse;
import com.chris64233.ambulancedispatch.dto.EventResponse;
import com.chris64233.ambulancedispatch.dto.RegisterAmbulanceRequest;
import com.chris64233.ambulancedispatch.dto.RegisterCrewRequest;
import com.chris64233.ambulancedispatch.dto.CreateEventRequest;
import com.chris64233.ambulancedispatch.service.CatalogService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车辆、救护组、事件的登记与查询。
 */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // ----- 车辆 -----

    @PostMapping("/ambulances")
    @ResponseStatus(HttpStatus.CREATED)
    public AmbulanceResponse registerAmbulance(@Valid @RequestBody RegisterAmbulanceRequest request) {
        return AmbulanceResponse.from(catalogService.registerAmbulance(request));
    }

    @GetMapping("/ambulances/{id}")
    public AmbulanceResponse getAmbulance(@PathVariable Long id) {
        return AmbulanceResponse.from(catalogService.getAmbulance(id));
    }

    @GetMapping("/ambulances")
    public List<AmbulanceResponse> listAmbulances() {
        return catalogService.listAmbulances().stream().map(AmbulanceResponse::from).toList();
    }

    // ----- 救护组 -----

    @PostMapping("/crews")
    @ResponseStatus(HttpStatus.CREATED)
    public CrewResponse registerCrew(@Valid @RequestBody RegisterCrewRequest request) {
        return CrewResponse.from(catalogService.registerCrew(request));
    }

    @GetMapping("/crews/{id}")
    public CrewResponse getCrew(@PathVariable Long id) {
        return CrewResponse.from(catalogService.getCrew(id));
    }

    @GetMapping("/crews")
    public List<CrewResponse> listCrews() {
        return catalogService.listCrews().stream().map(CrewResponse::from).toList();
    }

    // ----- 事件 -----

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse createEvent(@Valid @RequestBody CreateEventRequest request) {
        return EventResponse.from(catalogService.createEvent(request));
    }

    @GetMapping("/events/{id}")
    public EventResponse getEvent(@PathVariable Long id) {
        return EventResponse.from(catalogService.getEvent(id));
    }

    @GetMapping("/events")
    public List<EventResponse> listEvents() {
        return catalogService.listEvents().stream().map(EventResponse::from).toList();
    }
}
