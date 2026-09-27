package com.chris64233.ambulancedispatch.api;

import com.chris64233.ambulancedispatch.api.dto.CreateCrewRequest;
import com.chris64233.ambulancedispatch.domain.Crew;
import com.chris64233.ambulancedispatch.domain.ResourceEvent;
import com.chris64233.ambulancedispatch.domain.ResourceType;
import com.chris64233.ambulancedispatch.service.CatalogService;
import com.chris64233.ambulancedispatch.service.DispatchService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 救护组登记、查询与资源时间线。
 */
@RestController
@RequestMapping("/api/crews")
public class CrewController {

    private final CatalogService catalogService;
    private final DispatchService dispatchService;

    public CrewController(CatalogService catalogService, DispatchService dispatchService) {
        this.catalogService = catalogService;
        this.dispatchService = dispatchService;
    }

    @PostMapping
    public ResponseEntity<Crew> register(@Valid @RequestBody CreateCrewRequest request) {
        Crew crew = catalogService.registerCrew(request.name(), request.qualifications());
        return ResponseEntity.status(HttpStatus.CREATED).body(crew);
    }

    @GetMapping("/{id}")
    public Crew get(@PathVariable Long id) {
        return catalogService.getCrew(id);
    }

    /** 救护组资源时间线。 */
    @GetMapping("/{id}/timeline")
    public List<ResourceEvent> timeline(@PathVariable Long id) {
        return dispatchService.resourceTimeline(ResourceType.CREW, id);
    }
}
