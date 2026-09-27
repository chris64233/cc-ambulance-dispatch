package com.chris64233.ambulancedispatch.api;

import com.chris64233.ambulancedispatch.api.dto.CreateIncidentRequest;
import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.domain.Incident;
import com.chris64233.ambulancedispatch.domain.PreemptionRecord;
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
 * 事件上报、查询、事件派遣详情与抢占链。
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final CatalogService catalogService;
    private final DispatchService dispatchService;

    public IncidentController(CatalogService catalogService, DispatchService dispatchService) {
        this.catalogService = catalogService;
        this.dispatchService = dispatchService;
    }

    @PostMapping
    public ResponseEntity<Incident> report(@Valid @RequestBody CreateIncidentRequest request) {
        Incident incident = catalogService.reportIncident(
                request.area(), request.location(), request.priority(), request.requiredCapabilities());
        return ResponseEntity.status(HttpStatus.CREATED).body(incident);
    }

    @GetMapping("/{id}")
    public Incident get(@PathVariable Long id) {
        return catalogService.getIncident(id);
    }

    /** 事件最近一次派遣详情。 */
    @GetMapping("/{id}/dispatch")
    public Dispatch latestDispatch(@PathVariable Long id) {
        return dispatchService.latestDispatchOfIncident(id);
    }

    /** 事件抢占链（作为抢占方或被抢占方参与的全部抢占记录，按时间升序）。 */
    @GetMapping("/{id}/preemption-chain")
    public List<PreemptionRecord> preemptionChain(@PathVariable Long id) {
        return dispatchService.preemptionChain(id);
    }
}
