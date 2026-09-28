# cc-ambulance-dispatch

急救事件、车辆、救护组与医院管理服务，支持**车辆与救护组联合派遣 + 目的医院床位预留**、
**高优先级事件安全抢占**，以及**医院拒收与途中改派**。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1
- Spring Data JPA + H2（默认内存库）

迁移项目沿用现有 Spring Boot 版本，其他项目使用上述版本。

## 常用命令

运行测试：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run

## 领域模型

| 实体 | 关键字段 |
| --- | --- |
| 车辆 `Ambulance` | 服务区域集合、车载设备能力集合、状态（AVAILABLE / DISPATCHED） |
| 救护组 `Crew` | 人员资质集合、值勤状态（ON_DUTY / OFF_DUTY）、派勤状态（IDLE / ASSIGNED） |
| 医院 `Hospital` | 可接收急救类型集合、床位容量 `bedCapacity`、已预留数 `reservedCount`、接收状态（OPEN / CLOSED） |
| 事件 `EmergencyEvent` | 位置、服务区域、优先级、**急救类型 `emergencyType`**、所需能力集合、状态（PENDING / DISPATCHED / COMPLETED / CANCELLED） |
| 派遣单 `Dispatch` | 业务号、指纹、事件/车/组/**当前目的医院**、状态（EN_ROUTE / ON_SCENE / **AT_HOSPITAL** / COMPLETED / CANCELLED / PREEMPTED）、抢占链 |
| 名额预留 `HospitalReservation` | 医院/派遣/事件、急救类型、状态（HELD / RELEASED / REJECTED），只增留痕 |
| 拒收记录 `HospitalRejection` | 拒收医院、派遣、关联预留、拒收原因与时间 |
| 改派任务 `DiversionTask` | 拒收后生成（PENDING），改派成功后 FULFILLED；派遣在改派前终态则 CLOSED |
| 改派记录 `DiversionRecord` | 业务号、指纹、原/新医院、原因、急救类型、结果（SUCCESS / FAILED）、失败原因 |
| 时间线 `ResourceTimelineEntry` | 资源（车/组）、动作（ASSIGNED / ARRIVED / **ARRIVED_HOSPITAL** / RELEASED / PREEMPTED） |

## 主要业务规则

### 1. 联合派遣（车 + 救护组 + 医院名额，原子）

- 一次派遣必须**同时**指定一辆车、一个救护组和一家**目的医院**，三者缺一不可。
- 派遣前校验：车辆服务区域覆盖事件区域；车辆设备与救护组资质包含事件全部所需能力；
  救护组在岗（ON_DUTY）；医院处于 OPEN、可接收事件急救类型且仍有空床。
- 派遣确认在**单个数据库事务**内完成：车置 DISPATCHED、组置 ASSIGNED、**医院预留名额 +1**、
  事件置 DISPATCHED，写入派遣单、名额预留与时间线。
- **任何资源不足都不形成部分派遣**：事件/车/组/医院行均加悲观锁，车组采用条件更新（CAS），
  医院名额在持锁事务内校验并占用。任一失败整个事务回滚，已占用的其他资源一并释放。
- 并发争抢同一组资源或医院**最后一个名额**时只有一个提交成功；床位 `reservedCount`
  靠行锁 + `0 ≤ reservedCount ≤ bedCapacity` 不变量保证**永不为负、绝不超卖**。

### 2. 高优先级抢占

- 仅当新事件优先级**严格高于**目标派遣所属事件时才允许抢占。
- 只有**尚未到达现场**（EN_ROUTE）的派遣可被抢占；到达现场（ON_SCENE）或到达医院后不可抢占。
- 抢占必须接管原派遣的车辆与救护组，且仍需满足新事件的区域/能力要求。
- 抢占在**单事务内原子完成**：原单 PREEMPTED、原事件回 PENDING、**原医院名额释放**、
  新事件 DISPATCHED、**新医院名额预留**、新单挂同一抢占链；资源不经过空闲窗口直接转移。
  任一步失败全部回滚，原派遣与原名额保持不变。

### 3. 医院接收状态上报

- 医院通过登记/更新接口上报**可接收急救类型、床位容量、接收状态**。
- 更新在持医院行锁的事务内进行，与床位预留/释放/改派串行：
  - 关闭接收（CLOSED）后**不能再收到新事件**，也不能作为改派目标；已有名额继续占用。
  - 床位容量不能下调到当前已预留数以下。
- “医院状态更新与改派同时发生”由同一把医院行锁串行，二者不会交错导致计数异常。

### 4. 途中改派

- 救护车**到达现场后**（ON_SCENE），可因医院关闭接收或患者病情变化申请改派；
  病情变化可在改派请求中携带新的急救类型用于匹配目标医院。
- **已到达医院（AT_HOSPITAL）的事件不能再改派**；EN_ROUTE 阶段也不允许改派。
- 改派严格按“**新医院名额完整预留成功后才释放原医院名额**”执行：
  - 成功：新院 HELD、原院释放、派遣 `destinationHospital` 切换，车与组保持不变；
  - 失败（新院关闭/满床/不接收类型）：只记录一次 `FAILED` 改派，
    **原目的地、原名额与派遣完全不变**。
- **旧改派请求不得覆盖后来确认的目的地**：请求带 `expectedHospitalId` 乐观守卫，
  与派遣当前目的地不一致时返回 `DIVERSION_DESTINATION_CONFLICT`。
- 改派通过业务号幂等：**同号同内容返回首次结果**（成功或失败都原样返回，`replayed=true`），
  **同号异内容返回 409 IDEMPOTENT_CONFLICT**。

### 5. 医院拒收

- 仅 ON_SCENE（到场后、到院前）可拒收。拒收在单事务内：名额置 REJECTED 并归还床位、
  记录拒收原因、生成一条 PENDING 改派任务。
- **拒收不自行释放车辆或救护组**，派遣目的地也不变；后续改派成功时任务置 FULFILLED。
- 拒收后再改派时，原名额已归还，只需占用新院名额，不会重复释放原院。
- **拒收后必须先成功改派才能“到达医院”**：原医院名额已归还，未拿到新名额前不允许交接，
  防止向已拒收医院完成交付。
- 多医院加锁一律按医院 id 升序，反向改派/抢占交叉也不会产生 AB-BA 死锁；
  锁竞争失败返回 409 可安全重试。
- 派遣在改派前完成/取消时，遗留的待处理改派任务置 CLOSED，不会永久滞留 PENDING。

### 6. 幂等与终态

- 派遣、抢占、改派均以 **`bizNo` 业务号**幂等，服务端对请求内容计算 SHA-256 指纹：
  同号同内容返回原结果（`replayed=true`），同号异内容返回 409。
- 生命周期：派遣（EN_ROUTE）→ 到达现场（ON_SCENE）→ 到达医院（**AT_HOSPITAL**）→
  完成（COMPLETED，释放车组与名额）；在途/在场/到院均可取消（CANCELLED，同样释放）。
- 完成与取消是不可修改终态；终态派遣的任何生命周期操作返回 409，终态事件不可再次派遣。

### 7. 查询能力

- **事件完整详情** `GET /api/events/{eventId}/dispatch-detail`：事件信息、当前进行中派遣、
  全部派遣历史、**医院预留、历次改派（含失败）、拒收原因、改派任务与事件完整时间线**。
- 资源时间线：`GET /api/ambulances/{id}/timeline`、`GET /api/crews/{id}/timeline`。
- 抢占链：`GET /api/dispatches/{id}/preemption-chain`（节点含目的医院）。
- 待处理改派任务：`GET /api/diversion-tasks`。

### 8. 统一错误响应

```json
{
  "timestamp": "2026-09-28T01:27:21.166Z",
  "status": 409,
  "error": "Conflict",
  "code": "HOSPITAL_BED_UNAVAILABLE",
  "message": "医院 床位已满",
  "path": "/api/dispatches"
}
```

主要错误码：`VALIDATION_ERROR`(400)、`*_NOT_FOUND`(404)，以及 409 类——
`IDEMPOTENT_CONFLICT`、`RESOURCE_UNAVAILABLE`、`SERVICE_AREA_MISMATCH`、`CAPABILITY_MISMATCH`、
`CREW_OFF_DUTY`、`DISPATCH_ALREADY_ACTIVE`、`DISPATCH_ALREADY_ARRIVED`、
`PREEMPTION_NOT_HIGHER_PRIORITY`、`PREEMPTION_RESOURCE_MISMATCH`、
`EVENT_TERMINAL`、`DISPATCH_TERMINAL`、
`HOSPITAL_NOT_RECEIVING`、`HOSPITAL_BED_UNAVAILABLE`、`HOSPITAL_TYPE_UNSUPPORTED`、
`DIVERSION_NOT_ALLOWED`、`DIVERSION_ALREADY_ARRIVED`、`DIVERSION_DESTINATION_CONFLICT`、
`REJECTION_NOT_ALLOWED`。

> 改派目标医院不可用时**不返回错误**，而是创建一条 `status=FAILED` 的改派记录（HTTP 201），
> 原目的地与派遣保持不变；只有时机非法（未到场/已到院/终态）、守卫冲突等才返回 4xx。

## HTTP 接口

### 登记查询

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/ambulances` | 登记车辆 `{plateNumber, serviceAreas[], equipmentCapabilities[]}` |
| GET | `/api/ambulances`、`/api/ambulances/{id}` | 车辆列表/详情 |
| POST | `/api/crews` | 登记救护组 `{name, qualifications[], onDuty}` |
| GET | `/api/crews`、`/api/crews/{id}` | 救护组列表/详情 |
| POST | `/api/hospitals` | 登记医院 `{name, acceptedEmergencyTypes[], bedCapacity}` |
| GET | `/api/hospitals`、`/api/hospitals/{id}` | 医院列表/详情（含 reservedCount、availableBeds） |
| PATCH | `/api/hospitals/{id}` | 更新医院 `{acceptedEmergencyTypes[], bedCapacity, receivingStatus}`（字段可空=不改） |
| POST | `/api/events` | 上报事件 `{location, serviceArea, priority, emergencyType, requiredCapabilities[]}` |
| GET | `/api/events`、`/api/events/{id}` | 事件列表/详情 |

### 派遣、抢占与改派

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/dispatches` | 联合派遣 `{bizNo, eventId, ambulanceId, crewId, hospitalId}` |
| POST | `/api/dispatches/preempt` | 抢占 `{bizNo, newEventId, targetDispatchId, ambulanceId, crewId, hospitalId}` |
| POST | `/api/dispatches/{id}/arrive` | 到达现场（此后不可抢占，可拒收/改派） |
| POST | `/api/dispatches/{id}/arrive-hospital` | 到达医院（此后不可再改派） |
| POST | `/api/dispatches/{id}/complete` | 完成（需先到场，释放车组与名额，终态） |
| POST | `/api/dispatches/{id}/cancel` | 取消（释放车组与名额，终态） |
| POST | `/api/dispatches/{id}/reject` | 医院拒收 `{reason}`：归还名额、记录原因、生成改派任务，不释放车组 |
| POST | `/api/dispatches/divert` | 途中改派 `{bizNo, dispatchId, toHospitalId, reason, emergencyType?, expectedHospitalId?}` |
| GET | `/api/dispatches/{id}` | 派遣单详情（含 hospitalId） |
| GET | `/api/diversion-tasks` | 待处理（PENDING）改派任务列表 |
| GET | `/api/events/{eventId}/dispatch-detail` | 事件完整详情与时间线 |
| GET | `/api/ambulances/{id}/timeline` | 车辆时间线 |
| GET | `/api/crews/{id}/timeline` | 救护组时间线 |
| GET | `/api/dispatches/{id}/preemption-chain` | 抢占链 |

### 快速示例

```bash
# 登记资源与医院
curl -X POST localhost:8080/api/ambulances -H 'Content-Type: application/json' \
  -d '{"plateNumber":"京A1234","serviceAreas":["东城区"],"equipmentCapabilities":["AED","VENTILATOR"]}'
curl -X POST localhost:8080/api/crews -H 'Content-Type: application/json' \
  -d '{"name":"一组","qualifications":["AED","VENTILATOR"],"onDuty":true}'
curl -X POST localhost:8080/api/hospitals -H 'Content-Type: application/json' \
  -d '{"name":"协和医院","acceptedEmergencyTypes":["GENERAL","CARDIAC"],"bedCapacity":20}'
# 上报事件并联合派遣（含医院名额预留）
curl -X POST localhost:8080/api/events -H 'Content-Type: application/json' \
  -d '{"location":"东单路口","serviceArea":"东城区","priority":"LOW","emergencyType":"GENERAL","requiredCapabilities":["AED"]}'
curl -X POST localhost:8080/api/dispatches -H 'Content-Type: application/json' \
  -d '{"bizNo":"BIZ-0001","eventId":1,"ambulanceId":1,"crewId":1,"hospitalId":1}'
# 到场 -> 拒收（生成改派任务）-> 改派到其他医院 -> 到达医院 -> 完成
curl -X POST localhost:8080/api/dispatches/1/arrive
curl -X POST localhost:8080/api/dispatches/1/reject -H 'Content-Type: application/json' -d '{"reason":"ICU 满床"}'
curl -X POST localhost:8080/api/dispatches/divert -H 'Content-Type: application/json' \
  -d '{"bizNo":"BIZ-0002","dispatchId":1,"toHospitalId":2,"reason":"REJECTED","expectedHospitalId":1}'
curl -X POST localhost:8080/api/dispatches/1/arrive-hospital
curl -X POST localhost:8080/api/dispatches/1/complete
```

## 测试

`src/test` 共 56 个自动化测试。除既有的联合派遣、抢占、幂等、终态、时间线与抢占链用例外，
本轮新增覆盖：

- 派遣原子预留医院名额；医院关闭 / 满床 / 不接收类型时派遣失败且不留任何部分占用；
- **两个事件并发争抢医院最后一个名额**：仅一个成功，容量恰为 1 不为负，输家车仍可用、组仍空闲、事件仍待派遣；
- **医院关闭与改派并发**：计数始终合法（原院+目标院合计恰为 1，无负值）；
- **两家医院派遣反向往对方改派（H1⇄H2）无死锁**，两单都成功且床位守恒；
- 容量不能下调到已预留数以下；并发同名医院登记返回 409 而非 500；
- **拒收后未成功改派不能到达医院**；取消后遗留改派任务置 CLOSED 不滞留；
- 改派成功（新院预留后才释放原院，历史预留留痕）、满床/关闭/类型不符时失败且原目的地不变；
- 病情变化按新急救类型改派；到场后可改派、**到达医院后不可改派**；
- **旧改派请求（expectedHospitalId 过期）不能覆盖后来确认的目的地**；
- 改派同号同内容返回首次结果（含 FAILED 结果重放）、同号异内容 409；
- 拒收记录原因、归还名额、生成 PENDING 任务且**不释放车组**；拒收后改派成功任务置 FULFILLED；
  未到场/已到院拒收被拒；
- 事件完整详情含派遣资源、医院预留、历次改派、拒收原因、改派任务与完整时间线；
- 医院登记/更新、拒收、改派、待处理任务的 HTTP 接口与统一错误响应。
