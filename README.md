# cc-ambulance-dispatch

急救事件、车辆和救护组管理服务：支持事件对车辆与救护组的联合派遣，以及高优先级事件的安全抢占。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1（Spring MVC + Spring Data JPA + H2 内存库）

迁移项目沿用现有 Spring Boot 版本，其他项目使用上述版本。

## 常用命令

运行测试：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run

## 领域模型

- **车辆 Vehicle**：呼号、服务区域（serviceArea）、设备能力（equipment）、状态（AVAILABLE / DISPATCHED / OUT_OF_SERVICE）。
- **救护组 Crew**：名称、人员资质（qualifications）、值勤状态（ON_DUTY / DISPATCHED / OFF_DUTY）。
- **事件 Incident**：服务区域（area）、详细位置（location）、优先级（LOW / MEDIUM / HIGH）、所需能力（requiredCapabilities）、状态机（PENDING → DISPATCHED → ON_SCENE → COMPLETED；非终态可 CANCELLED）。
- **派遣 Dispatch**：一次派遣同时选定一辆车和一组救护组；携带派遣业务号（requestId，幂等键）与内容指纹。
- **抢占记录 PreemptionRecord**：一次抢占的双方事件、双方派遣与涉及资源，构成抢占链。
- **资源事件 ResourceEvent**：车辆 / 救护组在派遣生命周期中的每次状态变化，构成资源时间线。

## 主要业务规则

### 联合派遣与能力匹配

1. 一次派遣必须同时选定一辆车和一组救护组，缺一不可。
2. 车辆的服务区域必须与事件区域一致。
3. 车辆设备与救护组资质的**并集**必须覆盖事件的全部所需能力。
4. 车辆须为 AVAILABLE、救护组须为 ON_DUTY 才可被派遣；停用（OUT_OF_SERVICE）与休班（OFF_DUTY）不可派遣。

### 原子占用与并发争抢

5. 确认派遣在同一事务内原子占用车辆与救护组：通过悲观写锁按 **事件 → 车辆 → 救护组** 的固定顺序加锁，并发事件争抢同一资源时只有一个成功，其余收到 `RESOURCE_UNAVAILABLE`（409）。
6. 车辆与人员永远作为一个整体被占用 / 释放 / 转移，不会出现分别被不同事件占用的情况；若数据出现此类不一致，派遣直接拒绝。

### 高优先级抢占

7. 严格更高优先级的事件可以抢占**尚未到达现场**的低优先级派遣；同等或更低优先级不可抢占。
8. 抢占在同一事务内一次完成：原派遣终止为 PREEMPTED、原事件恢复为 PENDING（可再次派遣）、资源整体转移给新事件；任一步失败整体回滚，原派遣保持不变。
9. 只有车辆与救护组被**同一个**低优先级派遣整体占用时才能抢占；资源分属不同派遣时返回冲突。
10. 派遣确认到达现场（arrive）后不可再被抢占。

### 幂等与终态

11. 派遣业务号（requestId）保证幂等：相同内容重复提交返回原派遣（HTTP 200），内容不同返回 `IDEMPOTENCY_CONFLICT`（409）。并发重复提交由数据库唯一约束兜底，仍按幂等重放处理。
12. 完成（complete）与取消（cancel）均使派遣与事件进入不可修改的终态，并释放车辆与救护组；终态上的任何后续操作返回 `ILLEGAL_STATE`（409）。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/vehicles` | 登记车辆 |
| GET | `/api/vehicles/{id}` | 车辆详情 |
| GET | `/api/vehicles/{id}/timeline` | 车辆资源时间线 |
| POST | `/api/crews` | 登记救护组 |
| GET | `/api/crews/{id}` | 救护组详情 |
| GET | `/api/crews/{id}/timeline` | 救护组资源时间线 |
| POST | `/api/incidents` | 上报事件 |
| GET | `/api/incidents/{id}` | 事件详情 |
| GET | `/api/incidents/{id}/dispatch` | 事件最近一次派遣详情 |
| GET | `/api/incidents/{id}/preemption-chain` | 事件抢占链（按时间升序） |
| POST | `/api/dispatches` | 创建派遣（幂等；资源被低优先级派遣占用时自动触发抢占） |
| GET | `/api/dispatches/{id}` | 派遣详情 |
| POST | `/api/dispatches/{id}/arrive` | 确认到达现场 |
| POST | `/api/dispatches/{id}/complete` | 完成派遣（终态） |
| POST | `/api/dispatches/{id}/cancel` | 取消派遣（终态） |

创建派遣示例：

    POST /api/dispatches
    {"requestId": "REQ-001", "incidentId": 1, "vehicleId": 1, "crewId": 1}

## 统一错误响应

所有错误返回统一结构：

```json
{
  "timestamp": "2026-09-27T01:00:00Z",
  "status": 409,
  "code": "RESOURCE_UNAVAILABLE",
  "message": "资源被同等或更高优先级事件占用，不可抢占: dispatchId=3",
  "path": "/api/dispatches",
  "fieldErrors": null
}
```

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| VALIDATION_FAILED | 400 | 请求参数校验失败（fieldErrors 含字段级明细） |
| RESOURCE_NOT_FOUND | 404 | 引用的资源不存在 |
| IDEMPOTENCY_CONFLICT | 409 | 同一业务号提交了不同内容 |
| RESOURCE_UNAVAILABLE | 409 | 资源被占用且不满足抢占条件 |
| ILLEGAL_STATE | 409 | 当前状态不允许该操作（含终态不可变更） |
| REQUIREMENT_NOT_MET | 422 | 区域不匹配或能力不满足事件要求 |
| INTERNAL_ERROR | 500 | 未预期的服务器错误 |

## 自动化测试

- `DispatchServiceIntegrationTest`：联合占用、能力 / 区域校验、幂等重放与冲突、抢占全流程、到场后不可抢占、抢占失败回滚、完成 / 取消终态、被抢占事件再派遣、抢占链与时间线。
- `DispatchConcurrencyTest`：8 线程争抢同一车辆与救护组仅一个成功；相同业务号并发提交幂等；多高优先级事件并发抢占仅一个成功且数据一致。
- `DispatchApiTest`：REST 全流程、幂等语义（201 / 200 / 409）、统一错误响应结构、时间线与抢占链查询接口。
