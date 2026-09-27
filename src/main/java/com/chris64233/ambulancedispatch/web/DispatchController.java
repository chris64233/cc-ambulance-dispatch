package com.chris64233.ambulancedispatch.web;

import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.dto.DispatchResponse;
import com.chris64233.ambulancedispatch.dto.EventDispatchDetailResponse;
import com.chris64233.ambulancedispatch.dto.PreemptRequest;
import com.chris64233.ambulancedispatch.dto.PreemptionChainItemResponse;
import com.chris64233.ambulancedispatch.dto.TimelineEntryResponse;
import com.chris64233.ambulancedispatch.service.DispatchService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 派遣、抢占、生命周期与查询接口。
 */
@RestController
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @PostMapping("/api/dispatches")
    @ResponseStatus(HttpStatus.CREATED)
    public DispatchResponse dispatch(@Valid @RequestBody DispatchRequest request) {
        return dispatchService.dispatch(request);
    }

    @PostMapping("/api/dispatches/preempt")
    @ResponseStatus(HttpStatus.CREATED)
    public DispatchResponse preempt(@Valid @RequestBody PreemptRequest request) {
        return dispatchService.preempt(request);
    }

    @GetMapping("/api/dispatches/{id}")
    public DispatchResponse getDispatch(@PathVariable Long id) {
        return dispatchService.getDispatch(id);
    }

    @PostMapping("/api/dispatches/{id}/arrive")
    public DispatchResponse arrive(@PathVariable Long id) {
        return dispatchService.arrive(id);
    }

    @PostMapping("/api/dispatches/{id}/complete")
    public DispatchResponse complete(@PathVariable Long id) {
        return dispatchService.complete(id);
    }

    @PostMapping("/api/dispatches/{id}/cancel")
    public DispatchResponse cancel(@PathVariable Long id) {
        return dispatchService.cancel(id);
    }

    /** 事件派遣详情：当前进行中的派遣与全部派遣历史。 */
    @GetMapping("/api/events/{eventId}/dispatch-detail")
    public EventDispatchDetailResponse eventDispatchDetail(@PathVariable Long eventId) {
        return dispatchService.getEventDispatchDetail(eventId);
    }

    /** 抢占链：按时间顺序返回同一条抢占链上的全部派遣。 */
    @GetMapping("/api/dispatches/{id}/preemption-chain")
    public List<PreemptionChainItemResponse> preemptionChain(@PathVariable Long id) {
        return dispatchService.getPreemptionChain(id);
    }

    /** 车辆资源时间线。 */
    @GetMapping("/api/ambulances/{id}/timeline")
    public List<TimelineEntryResponse> ambulanceTimeline(@PathVariable Long id) {
        return dispatchService.getAmbulanceTimeline(id);
    }

    /** 救护组资源时间线。 */
    @GetMapping("/api/crews/{id}/timeline")
    public List<TimelineEntryResponse> crewTimeline(@PathVariable Long id) {
        return dispatchService.getCrewTimeline(id);
    }
}
