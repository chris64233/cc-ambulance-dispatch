package com.chris64233.ambulancedispatch.api;

import com.chris64233.ambulancedispatch.api.dto.CreateDispatchRequest;
import com.chris64233.ambulancedispatch.domain.Dispatch;
import com.chris64233.ambulancedispatch.service.DispatchExecutor;
import com.chris64233.ambulancedispatch.service.DispatchOutcome;
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

/**
 * 派遣创建、详情查询与生命周期操作（到场 / 完成 / 取消）。
 */
@RestController
@RequestMapping("/api/dispatches")
public class DispatchController {

    private final DispatchService dispatchService;
    private final DispatchExecutor dispatchExecutor;

    public DispatchController(DispatchService dispatchService, DispatchExecutor dispatchExecutor) {
        this.dispatchService = dispatchService;
        this.dispatchExecutor = dispatchExecutor;
    }

    /**
     * 创建派遣。幂等规则：同一业务号内容相同返回原派遣（200），内容不同返回 409。
     */
    @PostMapping
    public ResponseEntity<Dispatch> create(@Valid @RequestBody CreateDispatchRequest request) {
        DispatchOutcome outcome = dispatchExecutor.execute(
                request.requestId(), request.incidentId(), request.vehicleId(), request.crewId());
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(outcome.dispatch());
    }

    @GetMapping("/{id}")
    public Dispatch get(@PathVariable Long id) {
        return dispatchService.getDispatch(id);
    }

    /** 确认到达现场。 */
    @PostMapping("/{id}/arrive")
    public Dispatch arrive(@PathVariable Long id) {
        return dispatchService.arrive(id);
    }

    /** 完成派遣（终态，不可再修改）。 */
    @PostMapping("/{id}/complete")
    public Dispatch complete(@PathVariable Long id) {
        return dispatchService.complete(id);
    }

    /** 取消派遣（终态，不可再修改）。 */
    @PostMapping("/{id}/cancel")
    public Dispatch cancel(@PathVariable Long id) {
        return dispatchService.cancel(id);
    }
}
