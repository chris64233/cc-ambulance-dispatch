package com.chris64233.ambulancedispatch.service;

import com.chris64233.ambulancedispatch.api.error.ApiException;
import com.chris64233.ambulancedispatch.domain.Crew;
import com.chris64233.ambulancedispatch.domain.Incident;
import com.chris64233.ambulancedispatch.domain.Priority;
import com.chris64233.ambulancedispatch.domain.Vehicle;
import com.chris64233.ambulancedispatch.repository.CrewRepository;
import com.chris64233.ambulancedispatch.repository.IncidentRepository;
import com.chris64233.ambulancedispatch.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/**
 * 资源目录服务：车辆、救护组、事件的登记与查询。
 */
@Service
public class CatalogService {

    private final VehicleRepository vehicleRepository;
    private final CrewRepository crewRepository;
    private final IncidentRepository incidentRepository;

    public CatalogService(VehicleRepository vehicleRepository,
                          CrewRepository crewRepository,
                          IncidentRepository incidentRepository) {
        this.vehicleRepository = vehicleRepository;
        this.crewRepository = crewRepository;
        this.incidentRepository = incidentRepository;
    }

    @Transactional
    public Vehicle registerVehicle(String callSign, String serviceArea, Set<String> equipment) {
        return vehicleRepository.save(new Vehicle(callSign, serviceArea, equipment));
    }

    @Transactional
    public Crew registerCrew(String name, Set<String> qualifications) {
        return crewRepository.save(new Crew(name, qualifications));
    }

    @Transactional
    public Incident reportIncident(String area, String location, Priority priority, Set<String> requiredCapabilities) {
        return incidentRepository.save(new Incident(area, location, priority, requiredCapabilities, Instant.now()));
    }

    @Transactional(readOnly = true)
    public Vehicle getVehicle(Long id) {
        return vehicleRepository.findById(id).orElseThrow(() -> ApiException.notFound("车辆", id));
    }

    @Transactional(readOnly = true)
    public Crew getCrew(Long id) {
        return crewRepository.findById(id).orElseThrow(() -> ApiException.notFound("救护组", id));
    }

    @Transactional(readOnly = true)
    public Incident getIncident(Long id) {
        return incidentRepository.findById(id).orElseThrow(() -> ApiException.notFound("事件", id));
    }
}
