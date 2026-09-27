package com.chris64233.ambulancedispatch.api;

import com.chris64233.ambulancedispatch.api.dto.CreateVehicleRequest;
import com.chris64233.ambulancedispatch.domain.ResourceEvent;
import com.chris64233.ambulancedispatch.domain.ResourceType;
import com.chris64233.ambulancedispatch.domain.Vehicle;
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
 * 车辆登记、查询与资源时间线。
 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final CatalogService catalogService;
    private final DispatchService dispatchService;

    public VehicleController(CatalogService catalogService, DispatchService dispatchService) {
        this.catalogService = catalogService;
        this.dispatchService = dispatchService;
    }

    @PostMapping
    public ResponseEntity<Vehicle> register(@Valid @RequestBody CreateVehicleRequest request) {
        Vehicle vehicle = catalogService.registerVehicle(request.callSign(), request.serviceArea(), request.equipment());
        return ResponseEntity.status(HttpStatus.CREATED).body(vehicle);
    }

    @GetMapping("/{id}")
    public Vehicle get(@PathVariable Long id) {
        return catalogService.getVehicle(id);
    }

    /** 车辆资源时间线。 */
    @GetMapping("/{id}/timeline")
    public List<ResourceEvent> timeline(@PathVariable Long id) {
        return dispatchService.resourceTimeline(ResourceType.VEHICLE, id);
    }
}
