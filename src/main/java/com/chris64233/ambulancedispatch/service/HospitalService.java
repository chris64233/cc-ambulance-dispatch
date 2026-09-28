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
 * 医院登记、信息上报与接收状态更新。
 * 更新在持医院行锁的事务内完成，保证与床位预留/释放串行，关闭后不会再收到新事件。
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
        Set<com.chris64233.ambulancedispatch.domain.EmergencyType> types =
                Set.copyOf(request.acceptedEmergencyTypes());
        return hospitalRepository.save(new Hospital(
                request.name(), types, request.bedCapacity(), ReceivingStatus.OPEN));
    }

    @Transactional
    public Hospital update(Long id, UpdateHospitalRequest request) {
        Hospital hospital = hospitalRepository.findWithLockById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HOSPITAL_NOT_FOUND));
        if (request.acceptedEmergencyTypes() != null) {
            hospital.setAcceptedEmergencyTypes(Set.copyOf(request.acceptedEmergencyTypes()));
        }
        if (request.bedCapacity() != null) {
            // 容量不能收缩到当前已占用名额以下，保证 reservedCount <= bedCapacity
            if (request.bedCapacity() < hospital.getReservedCount()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "床位容量不能小于已预留床位数 " + hospital.getReservedCount());
            }
            hospital.setBedCapacity(request.bedCapacity());
        }
        if (request.receivingStatus() != null) {
            hospital.setReceivingStatus(request.receivingStatus());
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
}
