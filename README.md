# cc-ambulance-dispatch

急救事件、车辆、救护组与目的医院管理服务，支持**车辆 + 救护组 + 医院名额的联合派遣**、
**高优先级事件安全抢占**、**目的医院床位预留**与**途中改派 / 医院拒收**。

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
| 事件 `EmergencyEvent` | 位置、所在服务区域、**急救类型 emergencyType**、优先级（LOW < NORMAL < HIGH < CRITICAL）、所需能力集合、状态（PENDING / DISPATCHED / COMPLETED / CANCELLED） |
| 医院 `Hospital` | **可接收急救类型集合、床位容量 bedCapacity、已预留床位 reservedBeds、接收状态（OPEN / CLOSED）** |
| 派遣单 `Dispatch` | 业务号、请求指纹、绑定事件/车辆/救护组/**当前目的医院**、状态（EN_ROUTE / ON_SCENE / COMPLETED / CANCELLED / PREEMPTED）、到场/到院时间、抢占来源、抢占链根 |
| 改派记录 `Diversion` | 业务号、请求指纹、所属派遣/事件、原/新医院、原因与明细、状态（PENDING / SUCCESS / FAILED / RESOLVED）、失败错误码 |
| 时间线 `ResourceTimelineEntry` | 资源（车/组/**医院**）、动作、发生时间、所属派遣、备注 |

医院预留容量恒等式：`0 <= reservedBeds <= bedCapacity`，任何并发下都不为负、不超额。

## 主要业务规则

### 1. 联合派遣（车 + 组 + 医院名额原子预留）

- 一次派遣必须**同时**指定一辆车、一个救护组和一个目的医院，三者缺一不可。
- 派遣前校验：车辆服务区域必须覆盖事件区域；车辆设备能力与救护组资质都必须包含事件全部所需能力；
  救护组必须在岗（ON_DUTY）；医院必须**开放接收（OPEN）**、可接收事件的**急救类型**且**有空余床位**。
- 派遣确认在**单个数据库事务**内完成：车辆置 DISPATCHED、救护组置 ASSIGNED、
  **医院 `reservedBeds + 1`**、事件置 DISPATCHED、写入派遣单与时间线。
- **并发安全 / 不得部分派遣**：事件行悲观锁串行化同一事件的操作；车辆占用（`AVAILABLE→DISPATCHED`）、
  救护组占用（`IDLE→ASSIGNED`）与医院床位预留（`OPEN 且 reservedBeds < bedCapacity 时 +1`）
  均为条件更新（CAS）。**任一资源不足整个事务回滚**，已占用的车/组/床位一并撤销——
  不会出现车被 A 占用、人被 B 占用、或床位预留成功但派遣失败的拆分状态。
- 两个事件并发争抢最后一个名额时只有一个提交成功，失败一方 409 且不占用任何资源。

### 2. 高优先级抢占

- 仅当新事件优先级**严格高于**目标派遣所属事件时才允许抢占（CRITICAL > HIGH > NORMAL > LOW）。
- 只有**尚未到达现场**（EN_ROUTE）的派遣可被抢占；一旦到达（ON_SCENE）即不可抢占。
- 抢占必须接管目标派遣原有的车辆与救护组（仍需满足新事件区域/能力要求），并指定目的医院
  （必须开放接收、接收该急救类型且有空余床位）。
- 抢占在**单事务内原子完成**：原单置 PREEMPTED、原事件回 PENDING、**原医院名额释放**、
  **新医院名额预留**、新事件置 DISPATCHED、生成新派遣单挂到同一条抢占链；
  车组资源不经过空闲窗口直接转移属主。任一步失败全部回滚，**原派遣与原医院名额保持不变**。

### 3. 目的医院上报与床位预留

- 医院通过登记/更新接口上报：可接收急救类型集合、床位容量、接收状态（OPEN / CLOSED）。
- **关闭接收**与派遣/改派在医院行悲观锁 + 预留条件更新的双重保护下互斥：
  关闭提交后进行中的新预留全部失败，**关闭的医院不会再收到新事件**；已经预留的名额不受影响。
- 更新床位容量不得小于当前 `reservedBeds`，否则返回 409 `INVALID_CAPACITY`。

### 4. 途中改派

- 救护车**到达现场（ON_SCENE）后**，可因**医院关闭接收（HOSPITAL_CLOSED）**或
  **患者病情变化（CONDITION_CHANGED）**申请改派；尚未到场返回 `DIVERSION_NOT_ARRIVED`。
- **到达目的医院后不可再改派**：`POST /api/dispatches/{id}/arrive-hospital` 记录到院时刻后，
  任何改派/拒收返回 409 `DIVERSION_ALREADY_AT_HOSPITAL`。
- **先预留后释放**：新医院名额完整预留成功后，才释放原医院名额并切换 `dispatch.hospital`；
  新医院关闭、不接收该类型或满床导致预留失败时，**原目的地、原派遣、原名额全部保持不变**。
- **旧改派请求不得覆盖后来确认的目的地**：改派请求必须携带 `fromHospitalId`，
  与派遣当前目的医院不一致时返回 409 `DIVERSION_DESTINATION_MISMATCH`。
- 改派在派遣行与医院行锁下与到院、医院状态更新、其他改派串行；新旧医院按 id 全局顺序加锁，
  杜绝 A→B 与 B→A 并发的锁顺序死锁。

### 5. 医院拒收

- 当前目的医院可在到场后、到院前拒收：`POST /api/dispatches/reject`。
- 拒收**记录拒收原因**并生成一条 **PENDING 改派任务**，但**不自行释放车辆、救护组，
  也不释放原医院名额**——资源继续占用，等待后续成功改派（先预留新名额、再释放原名额）。
- 拒收医院必须是派遣当前目的医院，后来确认的新目的地不能被旧医院的迟到拒收覆盖
  （409 `REJECTION_DESTINATION_MISMATCH`）。
- 后续成功改派会把对应的 PENDING 拒收任务置为 **RESOLVED** 并关联到该成功改派。

### 6. 幂等与终态

- 派遣、抢占、改派、拒收均通过 **`bizNo` 业务号**保证幂等；服务端对请求内容计算 SHA-256 指纹：
  - 相同业务号 + 相同内容：重复提交返回**首次结果**（响应 `replayed=true`；失败改派重放返回首次失败错误，
    失败记录以独立事务持久化，主业务回滚不影响记录）；
  - 相同业务号 + 不同内容：返回 **409 IDEMPOTENT_CONFLICT**。
- **完成（COMPLETED）与取消（CANCELLED）是不可修改终态**，终态派遣再做生命周期操作返回 409，
  终态事件不可再次派遣。
- 生命周期：派遣（EN_ROUTE）→ 到达现场（ON_SCENE，可改派/拒收）→ 到达医院（到院后锁定目的地）
  → 完成（COMPLETED，释放车组与医院床位）；在途/在场均可取消（CANCELLED，同样释放全部资源）。

### 7. 查询能力

- **事件完整详情** `GET /api/events/{eventId}/dispatch-detail`：事件信息、当前进行中的派遣、
  **当前目的医院预留情况**、全部派遣历史、**历次改派/拒收记录（含原因与结果）**与
  **事件完整时间线（车/组/医院全部动作）**。
- **事件时间线** `GET /api/events/{eventId}/timeline`。
- **资源时间线** `GET /api/ambulances/{id}/timeline`、`/api/crews/{id}/timeline`、
  **`/api/hospitals/{id}/timeline`**。
- **派遣的改派历史** `GET /api/dispatches/{id}/diversions`、**改派记录详情** `GET /api/diversions/{id}`。
- **抢占链** `GET /api/dispatches/{id}/preemption-chain`。
- 时间线动作：车/组 `ASSIGNED / ARRIVED / RELEASED / PREEMPTED`；
  医院 `RESERVED / RESERVATION_RELEASED / ARRIVED_HOSPITAL / DIVERTED / REJECTED`
  （DIVERTED / REJECTED 的 `note` 携带原因与明细）。

### 8. 统一错误响应

所有错误均返回统一结构（HTTP 状态码与业务错误码对应）：

```json
{
  "timestamp": "2026-09-28T01:27:21.166Z",
  "status": 409,
  "error": "Conflict",
  "code": "HOSPITAL_NO_CAPACITY",
  "message": "医院 满院 床位已满，无法预留接收名额",
  "path": "/api/dispatches"
}
```

主要错误码：`VALIDATION_ERROR`(400)、`*_NOT_FOUND`(404)、`IDEMPOTENT_CONFLICT`、`RESOURCE_UNAVAILABLE`、
`SERVICE_AREA_MISMATCH`、`CAPABILITY_MISMATCH`、`CREW_OFF_DUTY`、`DISPATCH_ALREADY_ACTIVE`、
`DISPATCH_ALREADY_ARRIVED`、`PREEMPTION_NOT_HIGHER_PRIORITY`、`PREEMPTION_RESOURCE_MISMATCH`、
`EVENT_TERMINAL`、`DISPATCH_TERMINAL`、
`HOSPITAL_CLOSED`（医院关闭接收）、`HOSPITAL_NO_CAPACITY`（床位容量不足）、
`HOSPITAL_TYPE_NOT_ACCEPTED`（不接收该急救类型）、`INVALID_CAPACITY`（容量小于已预留）、
`DIVERSION_NOT_ARRIVED`（未到场不能改派）、`DIVERSION_ALREADY_AT_HOSPITAL`（已到院不能改派）、
`DIVERSION_DESTINATION_MISMATCH`（旧请求目的地过期）、`DIVERSION_SAME_HOSPITAL`、
`REJECTION_DESTINATION_MISMATCH`（拒收医院非当前目的地）（均为 409）。

## HTTP 接口

### 登记查询

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/ambulances` | 登记车辆 `{plateNumber, serviceAreas[], equipmentCapabilities[]}` |
| GET | `/api/ambulances`、`/api/ambulances/{id}` | 车辆列表/详情 |
| POST | `/api/crews` | 登记救护组 `{name, qualifications[], onDuty}` |
| GET | `/api/crews`、`/api/crews/{id}` | 救护组列表/详情 |
| POST | `/api/hospitals` | 登记医院 `{name, acceptedEmergencyTypes[], bedCapacity, receivingStatus}` |
| PUT | `/api/hospitals/{id}` | 更新医院上报（字段为 null 不修改）`{acceptedEmergencyTypes[], bedCapacity, receivingStatus}` |
| GET | `/api/hospitals`、`/api/hospitals/{id}` | 医院列表/详情（含 reservedBeds、availableBeds） |
| POST | `/api/events` | 上报事件 `{location, serviceArea, emergencyType, priority, requiredCapabilities[]}` |
| GET | `/api/events`、`/api/events/{id}` | 事件列表/详情 |

### 派遣、抢占、改派与拒收

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/dispatches` | 联合派遣 `{bizNo, eventId, ambulanceId, crewId, hospitalId}` |
| POST | `/api/dispatches/preempt` | 抢占 `{bizNo, newEventId, targetDispatchId, ambulanceId, crewId, hospitalId}` |
| POST | `/api/dispatches/divert` | 途中改派 `{bizNo, dispatchId, fromHospitalId, toHospitalId, reason, reasonDetail}` |
| POST | `/api/dispatches/reject` | 医院拒收 `{bizNo, dispatchId, hospitalId, reasonDetail}` |
| GET | `/api/dispatches/{id}` | 派遣单详情 |
| POST | `/api/dispatches/{id}/arrive` | 到达现场（此后可改派/拒收） |
| POST | `/api/dispatches/{id}/arrive-hospital` | 到达医院（此后不可改派/拒收） |
| POST | `/api/dispatches/{id}/complete` | 完成（需先到场，释放车组与床位，终态） |
| POST | `/api/dispatches/{id}/cancel` | 取消（释放车组与床位，终态） |
| GET | `/api/dispatches/{id}/diversions` | 该派遣历次改派/拒收 |
| GET | `/api/diversions/{id}` | 改派/拒收记录详情 |
| GET | `/api/events/{eventId}/dispatch-detail` | 事件完整详情（资源+预留+改派+时间线） |
| GET | `/api/events/{eventId}/timeline` | 事件完整时间线 |
| GET | `/api/ambulances/{id}/timeline` | 车辆时间线 |
| GET | `/api/crews/{id}/timeline` | 救护组时间线 |
| GET | `/api/hospitals/{id}/timeline` | 医院名额时间线 |
| GET | `/api/dispatches/{id}/preemption-chain` | 抢占链 |

### 快速示例

```bash
# 登记医院、车辆、救护组
curl -X POST localhost:8080/api/hospitals -H 'Content-Type: application/json' \
  -d '{"name":"第一医院","acceptedEmergencyTypes":["GENERAL","TRAUMA"],"bedCapacity":10,"receivingStatus":"OPEN"}'
curl -X POST localhost:8080/api/ambulances -H 'Content-Type: application/json' \
  -d '{"plateNumber":"京A1234","serviceAreas":["东城区"],"equipmentCapabilities":["AED","VENTILATOR"]}'
curl -X POST localhost:8080/api/crews -H 'Content-Type: application/json' \
  -d '{"name":"一组","qualifications":["AED","VENTILATOR"],"onDuty":true}'
# 上报事件（带急救类型）并联合派遣（预留医院名额）
curl -X POST localhost:8080/api/events -H 'Content-Type: application/json' \
  -d '{"location":"东单路口","serviceArea":"东城区","emergencyType":"TRAUMA","priority":"LOW","requiredCapabilities":["AED"]}'
curl -X POST localhost:8080/api/dispatches -H 'Content-Type: application/json' \
  -d '{"bizNo":"BIZ-20260928-0001","eventId":1,"ambulanceId":1,"crewId":1,"hospitalId":1}'
# 到场 -> 医院拒收（生成改派任务，不释放车组）-> 改派到第二医院 -> 到院 -> 完成
curl -X POST localhost:8080/api/dispatches/1/arrive
curl -X POST localhost:8080/api/dispatches/reject -H 'Content-Type: application/json' \
  -d '{"bizNo":"BIZ-20260928-0002","dispatchId":1,"hospitalId":1,"reasonDetail":"无创伤专科床位"}'
curl -X POST localhost:8080/api/dispatches/divert -H 'Content-Type: application/json' \
  -d '{"bizNo":"BIZ-20260928-0003","dispatchId":1,"fromHospitalId":1,"toHospitalId":2,"reason":"HOSPITAL_CLOSED","reasonDetail":"原院停收"}'
curl -X POST localhost:8080/api/dispatches/1/arrive-hospital
curl -X POST localhost:8080/api/dispatches/1/complete
```

## 测试

`src/test` 共 50 个自动化测试，覆盖：

- 联合派遣成功（车 + 组 + 医院名额）与区域/能力/值勤/**医院关闭/满床/急救类型**校验失败（失败不占用任何资源）；
- **并发争抢同一车+组**：双线程同时派遣仅一个成功，且车与组归属同一条派遣、床位只预留 1 个；
- **8 线程并发争抢医院最后 3 个床位**：恰好 3 个成功、5 个失败，`reservedBeds == 3` 永不超额；
- **医院关闭与派遣并发**：关闭先生效则关闭医院收不到新事件、床位为空；派遣先生效则保留 1 床后关闭；
- **改派与到院并发**：两种提交顺序下名额总数恒为 1、到院记录落在当前目的医院、容量恒等式成立；
- 改派成功（新院预留后才释放原院名额、车组不释放）；未到场/已到院不可改派；
  满床/关闭/类型不符改派失败时原目的地与名额不变，且同业务号重放返回首次失败；
- **旧改派请求（fromHospitalId 过期）不能覆盖后来确认的目的地**；同号异内容 409；改派幂等重放；
- 医院拒收记录原因、生成 PENDING 任务且不释放车组/床位；成功改派消化拒收任务为 RESOLVED；
  非当前目的地医院的迟到拒收 409；拒收幂等与冲突；
- 幂等重放（派遣/抢占/改派/拒收，`replayed=true`）、同业务号不同内容 409；
- 高优先级抢占成功（原医院名额释放、新医院名额预留）、到场后不可抢占、平级/低优先级不可抢占、
  能力不满足时抢占安全回滚原派遣与原名额不变；抢占幂等与冲突、被抢占事件释放后可重新派遣；
- 到达/到院/完成/取消终态行为与资源释放、终态不可修改；
- 事件完整详情（当前医院预留、历次改派/拒收、完整时间线）、车/组/医院时间线
  （RESERVED→REJECTED→RESERVATION_RELEASED / RESERVED→DIVERTED→ARRIVED_HOSPITAL→RESERVATION_RELEASED）、
  多级抢占链查询；
- 医院登记/更新（关闭接收、容量校验）、统一错误响应结构（400/404/409）。
