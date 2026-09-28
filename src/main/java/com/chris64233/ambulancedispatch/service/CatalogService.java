package com.chris64233.ambulancedispatch.service;

import com.chris64233.ambulancedispatch.domain.Ambulance;
import com.chris64233.ambulancedispatch.domain.Crew;
import com.chris64233.ambulancedispatch.domain.DutyStatus;
import com.chris64233.ambulancedispatch.domain.EmergencyEvent;
import com.chris64233.ambulancedispatch.domain.Priority;
import com.chris64233.ambulancedispatch.dto.CreateEventRequest;
import com.chris64233.ambulancedispatch.dto.RegisterAmbulanceRequest;
import com.chris64233.ambulancedispatch.dto.RegisterCrewRequest;
import com.chris64233.ambulancedispatch.exception.BusinessException;
import com.chris64233.ambulancedispatch.exception.ErrorCode;
import com.chris64233.ambulancedispatch.repository.AmbulanceRepository;
import com.chris64233.ambulancedispatch.repository.CrewRepository;
import com.chris64233.ambulancedispatch.repository.EmergencyEventRepository;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 车辆、救护组与事件的登记查询。
 */
@Service
public class CatalogService {

    private final AmbulanceRepository ambulanceRepository;
    private final CrewRepository crewRepository;
    private final EmergencyEventRepository eventRepository;
    private final Clock clock;

    public CatalogService(AmbulanceRepository ambulanceRepository,
                          CrewRepository crewRepository,
                          EmergencyEventRepository eventRepository,
                          Clock clock) {
        this.ambulanceRepository = ambulanceRepository;
        this.crewRepository = crewRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    @Transactional
    public Ambulance registerAmbulance(RegisterAmbulanceRequest request) {
        ambulanceRepository.findByPlateNumber(request.plateNumber()).ifPresent(a -> {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                    "车牌已存在: " + request.plateNumber());
        });
        return ambulanceRepository.save(new Ambulance(
                request.plateNumber(),
                Set.copyOf(request.serviceAreas()),
                Set.copyOf(request.equipmentCapabilities())));
    }

    @Transactional(readOnly = true)
    public Ambulance getAmbulance(Long id) {
        return ambulanceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.AMBULANCE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<Ambulance> listAmbulances() {
        return ambulanceRepository.findAll();
    }

    @Transactional
    public Crew registerCrew(RegisterCrewRequest request) {
        crewRepository.findByName(request.name()).ifPresent(c -> {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                    "救护组名称已存在: " + request.name());
        });
        DutyStatus duty = Boolean.FALSE.equals(request.onDuty())
                ? DutyStatus.OFF_DUTY : DutyStatus.ON_DUTY;
        Set<String> qualifications = request.qualifications() == null
                ? Set.of() : Set.copyOf(request.qualifications());
        return crewRepository.save(new Crew(request.name(), qualifications, duty));
    }

    @Transactional(readOnly = true)
    public Crew getCrew(Long id) {
        return crewRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CREW_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<Crew> listCrews() {
        return crewRepository.findAll();
    }

    @Transactional
    public EmergencyEvent createEvent(CreateEventRequest request) {
        Set<String> required = request.requiredCapabilities() == null
                ? Set.of() : Set.copyOf(request.requiredCapabilities());
        return eventRepository.save(new EmergencyEvent(
                request.location(),
                request.serviceArea(),
                request.priority() == null ? Priority.NORMAL : request.priority(),
                request.emergencyType() == null
                        ? com.chris64233.ambulancedispatch.domain.EmergencyType.GENERAL
                        : request.emergencyType(),
                required,
                clock.instant()));
    }

    @Transactional(readOnly = true)
    public EmergencyEvent getEvent(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<EmergencyEvent> listEvents() {
        return eventRepository.findAll();
    }
}
