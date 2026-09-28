package com.chris64233.ambulancedispatch.web;

import com.chris64233.ambulancedispatch.dto.DivertRequest;
import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.dto.DispatchResponse;
import com.chris64233.ambulancedispatch.dto.DiversionResponse;
import com.chris64233.ambulancedispatch.dto.EventDispatchDetailResponse;
import com.chris64233.ambulancedispatch.dto.PreemptRequest;
import com.chris64233.ambulancedispatch.dto.PreemptionChainItemResponse;
import com.chris64233.ambulancedispatch.dto.RejectRequest;
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
 * 派遣、抢占、改派/拒收、生命周期与查询接口。
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

    /** 途中改派：到场后、到院前，因医院关闭或病情变化改往新医院。 */
    @PostMapping("/api/dispatches/divert")
    @ResponseStatus(HttpStatus.CREATED)
    public DiversionResponse divert(@Valid @RequestBody DivertRequest request) {
        return dispatchService.divert(request);
    }

    /** 医院拒收：记录原因并生成待处理改派任务，不释放车辆与救护组。 */
    @PostMapping("/api/dispatches/reject")
    @ResponseStatus(HttpStatus.CREATED)
    public DiversionResponse reject(@Valid @RequestBody RejectRequest request) {
        return dispatchService.reject(request);
    }

    @GetMapping("/api/dispatches/{id}")
    public DispatchResponse getDispatch(@PathVariable Long id) {
        return dispatchService.getDispatch(id);
    }

    @PostMapping("/api/dispatches/{id}/arrive")
    public DispatchResponse arrive(@PathVariable Long id) {
        return dispatchService.arrive(id);
    }

    /** 到达目的医院（此后不可再改派/拒收）。 */
    @PostMapping("/api/dispatches/{id}/arrive-hospital")
    public DispatchResponse arriveAtHospital(@PathVariable Long id) {
        return dispatchService.arriveAtHospital(id);
    }

    @PostMapping("/api/dispatches/{id}/complete")
    public DispatchResponse complete(@PathVariable Long id) {
        return dispatchService.complete(id);
    }

    @PostMapping("/api/dispatches/{id}/cancel")
    public DispatchResponse cancel(@PathVariable Long id) {
        return dispatchService.cancel(id);
    }

    /** 一条派遣的历次改派/拒收记录。 */
    @GetMapping("/api/dispatches/{id}/diversions")
    public List<DiversionResponse> dispatchDiversions(@PathVariable Long id) {
        return dispatchService.getDispatchDiversions(id);
    }

    @GetMapping("/api/diversions/{id}")
    public DiversionResponse getDiversion(@PathVariable Long id) {
        return dispatchService.getDiversion(id);
    }

    /** 事件派遣详情：派遣资源、医院预留、历次改派/拒收、完整时间线。 */
    @GetMapping("/api/events/{eventId}/dispatch-detail")
    public EventDispatchDetailResponse eventDispatchDetail(@PathVariable Long eventId) {
        return dispatchService.getEventDispatchDetail(eventId);
    }

    /** 事件完整时间线（车/组/医院全部动作）。 */
    @GetMapping("/api/events/{eventId}/timeline")
    public List<TimelineEntryResponse> eventTimeline(@PathVariable Long eventId) {
        return dispatchService.getEventTimeline(eventId);
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

    /** 医院接收名额时间线（预留/释放/改派/拒收/到院）。 */
    @GetMapping("/api/hospitals/{id}/timeline")
    public List<TimelineEntryResponse> hospitalTimeline(@PathVariable Long id) {
        return dispatchService.getHospitalTimeline(id);
    }
}
