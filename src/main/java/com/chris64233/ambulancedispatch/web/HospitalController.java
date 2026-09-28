package com.chris64233.ambulancedispatch.web;

import com.chris64233.ambulancedispatch.dto.HospitalResponse;
import com.chris64233.ambulancedispatch.dto.RegisterHospitalRequest;
import com.chris64233.ambulancedispatch.dto.UpdateHospitalRequest;
import com.chris64233.ambulancedispatch.service.HospitalService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目的医院登记与上报：可接收急救类型、床位容量、接收状态。
 */
@RestController
@RequestMapping("/api/hospitals")
public class HospitalController {

    private final HospitalService hospitalService;

    public HospitalController(HospitalService hospitalService) {
        this.hospitalService = hospitalService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HospitalResponse register(@Valid @RequestBody RegisterHospitalRequest request) {
        return HospitalResponse.from(hospitalService.register(request));
    }

    /** 更新医院上报的急救类型/床位容量/接收状态（null 字段不修改）。 */
    @PutMapping("/{id}")
    public HospitalResponse update(@PathVariable Long id,
                                   @Valid @RequestBody UpdateHospitalRequest request) {
        return HospitalResponse.from(hospitalService.update(id, request));
    }

    @GetMapping("/{id}")
    public HospitalResponse get(@PathVariable Long id) {
        return HospitalResponse.from(hospitalService.get(id));
    }

    @GetMapping
    public List<HospitalResponse> list() {
        return hospitalService.list().stream().map(HospitalResponse::from).toList();
    }
}
