package com.chris64233.ambulancedispatch.service;

import com.chris64233.ambulancedispatch.domain.Hospital;
import com.chris64233.ambulancedispatch.domain.ReceivingStatus;
import com.chris64233.ambulancedispatch.dto.RegisterHospitalRequest;
import com.chris64233.ambulancedispatch.dto.UpdateHospitalRequest;
import com.chris64233.ambulancedispatch.exception.BusinessException;
import com.chris64233.ambulancedispatch.exception.ErrorCode;
import com.chris64233.ambulancedispatch.repository.HospitalRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 目的医院登记与上报：维护可接收急救类型、床位容量与接收状态。
 */
@Service
public class HospitalService {

    private final HospitalRepository hospitalRepository;

    public HospitalService(HospitalRepository hospitalRepository) {
        this.hospitalRepository = hospitalRepository;
    }

    @Transactional
    public Hospital register(RegisterHospitalRequest request) {
        hospitalRepository.findByName(request.name()).ifPresent(h -> {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                    "医院名称已存在: " + request.name());
        });
        Set<String> types = request.acceptedEmergencyTypes() == null
                ? Set.of() : Set.copyOf(request.acceptedEmergencyTypes());
        ReceivingStatus status = parseStatus(request.receivingStatus());
        return hospitalRepository.save(new Hospital(
                request.name(), types, request.bedCapacity(), status));
    }

    /**
     * 医院上报更新。持行锁串行化状态更新与并发预留/改派：
     * 关闭接收后进行中的改派/派遣预留都会被条件更新挡下，关闭医院不会再收到新事件。
     */
    @Transactional
    public Hospital update(Long id, UpdateHospitalRequest request) {
        Hospital hospital = hospitalRepository.findWithLockById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HOSPITAL_NOT_FOUND));
        if (request.acceptedEmergencyTypes() != null) {
            hospital.setAcceptedEmergencyTypes(Set.copyOf(request.acceptedEmergencyTypes()));
        }
        if (request.bedCapacity() != null) {
            if (request.bedCapacity() < hospital.getReservedBeds()) {
                throw new BusinessException(ErrorCode.INVALID_CAPACITY,
                        "新容量 " + request.bedCapacity() + " 小于已预留床位 "
                                + hospital.getReservedBeds());
            }
            hospital.setBedCapacity(request.bedCapacity());
        }
        if (request.receivingStatus() != null) {
            hospital.setReceivingStatus(parseStatus(request.receivingStatus()));
        }
        return hospital;
    }

    @Transactional(readOnly = true)
    public Hospital get(Long id) {
        return hospitalRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HOSPITAL_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<Hospital> list() {
        return hospitalRepository.findAll();
    }

    private ReceivingStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReceivingStatus.OPEN;
        }
        try {
            return ReceivingStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "非法的接收状态: " + raw);
        }
    }
}
