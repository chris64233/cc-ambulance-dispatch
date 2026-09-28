package com.chris64233.ambulancedispatch.web;

import com.chris64233.ambulancedispatch.dto.HospitalResponse;
import com.chris64233.ambulancedispatch.dto.RegisterHospitalRequest;
import com.chris64233.ambulancedispatch.dto.UpdateHospitalRequest;
import com.chris64233.ambulancedispatch.service.HospitalService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医院登记、查询与接收状态/容量/可接收类型上报。
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

    @GetMapping("/{id}")
    public HospitalResponse get(@PathVariable Long id) {
        return HospitalResponse.from(hospitalService.get(id));
    }

    @GetMapping
    public List<HospitalResponse> list() {
        return hospitalService.list().stream().map(HospitalResponse::from).toList();
    }

    /** 部分更新医院的可接收类型、床位容量或接收状态。 */
    @PatchMapping("/{id}")
    public HospitalResponse update(@PathVariable Long id,
                                   @Valid @RequestBody UpdateHospitalRequest request) {
        return HospitalResponse.from(hospitalService.update(id, request));
    }
}
