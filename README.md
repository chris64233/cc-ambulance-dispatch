# cc-ambulance-dispatch

急救事件、车辆和救护组管理服务，支持**车辆与救护组的联合派遣**以及**高优先级事件安全抢占**。

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
| 事件 `EmergencyEvent` | 位置、所在服务区域、优先级（LOW < NORMAL < HIGH < CRITICAL）、所需能力集合、状态（PENDING / DISPATCHED / COMPLETED / CANCELLED） |
| 派遣单 `Dispatch` | 业务号、请求指纹、绑定事件/车辆/救护组、状态（EN_ROUTE / ON_SCENE / COMPLETED / CANCELLED / PREEMPTED）、抢占来源、抢占链根 |
| 时间线 `ResourceTimelineEntry` | 资源（车/组）、动作（ASSIGNED / ARRIVED / RELEASED / PREEMPTED）、发生时间、所属派遣 |

## 主要业务规则

### 1. 联合派遣

- 一次派遣必须**同时**指定一辆车和一个救护组，二者缺一不可。
- 派遣前校验：车辆服务区域必须覆盖事件区域；车辆设备能力与救护组资质都必须包含事件的全部所需能力；救护组必须在岗（ON_DUTY）。
- 派遣确认在**单个数据库事务**内完成：车辆状态置为 DISPATCHED、救护组置为 ASSIGNED、事件置为 DISPATCHED、写入派遣单与时间线。
- **并发安全**：事件行悲观锁串行化同一事件的操作；车辆/救护组采用条件更新（CAS，`AVAILABLE→DISPATCHED`、`IDLE→ASSIGNED`）。任一资源占用失败，整个事务回滚，已占用的另一资源一并释放——不会出现车被事件 A 占用、人被事件 B 占用的拆分状态。并发争抢同一组资源时只有一个提交成功。

### 2. 高优先级抢占

- 仅当新事件优先级**严格高于**目标派遣所属事件时才允许抢占（CRITICAL > HIGH > NORMAL > LOW）。
- 只有**尚未到达现场**（EN_ROUTE）的派遣可被抢占；一旦到达（ON_SCENE）即不可抢占。
- 抢占必须接管目标派遣原有的车辆与救护组，且这些资源仍需满足新事件的区域/能力要求。
- 抢占在**单事务内原子完成**：
  1. 原派遣单置为 PREEMPTED；
  2. 原事件恢复为 PENDING（可再次派遣）；
  3. 新事件置为 DISPATCHED，生成新派遣单并挂到同一条抢占链；
  4. 资源不经过空闲窗口，直接转移属主，写入 PREEMPTED/ASSIGNED 时间线。
- 任一步失败（优先级不足、已到场、能力不匹配等）全部回滚，**原派遣保持不变**。

### 3. 幂等与终态

- 派遣与抢占都通过请求头/请求体中的 **`bizNo` 业务号**保证幂等；服务端对请求内容计算 SHA-256 指纹：
  - 相同业务号 + 相同内容：重复提交返回原结果（响应中 `replayed=true`），不重复占用资源；
  - 相同业务号 + 不同内容：返回 **409 IDEMPOTENT_CONFLICT**。
- 派遣到达现场后不可被抢占；**完成（COMPLETED）与取消（CANCELLED）是不可修改终态**，终态派遣再做任何生命周期操作均返回 409，终态事件不可再次派遣。
- 生命周期：派遣（EN_ROUTE）→ 到达（ON_SCENE）→ 完成（COMPLETED，释放资源）；在途或在场均可取消（CANCELLED，释放资源）。

### 4. 查询能力

- **事件派遣详情** `GET /api/events/{eventId}/dispatch-detail`：事件信息、当前进行中的派遣（无则为 null）与全部派遣历史。
- **资源时间线** `GET /api/ambulances/{id}/timeline`、`GET /api/crews/{id}/timeline`：按时间顺序返回该资源的 ASSIGNED / PREEMPTED / ARRIVED / RELEASED 记录。
- **抢占链** `GET /api/dispatches/{id}/preemption-chain`：按时间顺序返回同一条抢占链上的全部派遣，从链上任意节点查询结果一致。

### 5. 统一错误响应

所有错误均返回统一结构（HTTP 状态码与业务错误码对应）：

```json
{
  "timestamp": "2026-09-27T01:27:21.166Z",
  "status": 409,
  "error": "Conflict",
  "code": "RESOURCE_UNAVAILABLE",
  "message": "车辆已被占用",
  "path": "/api/dispatches"
}
```

主要错误码：`VALIDATION_ERROR`(400)、`*_NOT_FOUND`(404)、`IDEMPOTENT_CONFLICT`、`RESOURCE_UNAVAILABLE`、
`SERVICE_AREA_MISMATCH`、`CAPABILITY_MISMATCH`、`CREW_OFF_DUTY`、`DISPATCH_ALREADY_ACTIVE`、
`DISPATCH_ALREADY_ARRIVED`、`PREEMPTION_NOT_HIGHER_PRIORITY`、`PREEMPTION_RESOURCE_MISMATCH`、
`EVENT_TERMINAL`、`DISPATCH_TERMINAL`（均为 409）。

## HTTP 接口

### 登记查询

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/ambulances` | 登记车辆 `{plateNumber, serviceAreas[], equipmentCapabilities[]}` |
| GET | `/api/ambulances`、`/api/ambulances/{id}` | 车辆列表/详情 |
| POST | `/api/crews` | 登记救护组 `{name, qualifications[], onDuty}` |
| GET | `/api/crews`、`/api/crews/{id}` | 救护组列表/详情 |
| POST | `/api/events` | 上报事件 `{location, serviceArea, priority, requiredCapabilities[]}` |
| GET | `/api/events`、`/api/events/{id}` | 事件列表/详情 |

### 派遣与抢占

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/dispatches` | 联合派遣 `{bizNo, eventId, ambulanceId, crewId}` |
| POST | `/api/dispatches/preempt` | 抢占 `{bizNo, newEventId, targetDispatchId, ambulanceId, crewId}` |
| GET | `/api/dispatches/{id}` | 派遣单详情 |
| POST | `/api/dispatches/{id}/arrive` | 到达现场（此后不可抢占） |
| POST | `/api/dispatches/{id}/complete` | 完成（需先到达，释放资源，终态） |
| POST | `/api/dispatches/{id}/cancel` | 取消（释放资源，终态） |
| GET | `/api/events/{eventId}/dispatch-detail` | 事件派遣详情 |
| GET | `/api/ambulances/{id}/timeline` | 车辆时间线 |
| GET | `/api/crews/{id}/timeline` | 救护组时间线 |
| GET | `/api/dispatches/{id}/preemption-chain` | 抢占链 |

### 快速示例

```bash
# 登记资源
curl -X POST localhost:8080/api/ambulances -H 'Content-Type: application/json' \
  -d '{"plateNumber":"京A1234","serviceAreas":["东城区"],"equipmentCapabilities":["AED","VENTILATOR"]}'
curl -X POST localhost:8080/api/crews -H 'Content-Type: application/json' \
  -d '{"name":"一组","qualifications":["AED","VENTILATOR"],"onDuty":true}'
# 上报并派遣
curl -X POST localhost:8080/api/events -H 'Content-Type: application/json' \
  -d '{"location":"东单路口","serviceArea":"东城区","priority":"LOW","requiredCapabilities":["AED"]}'
curl -X POST localhost:8080/api/dispatches -H 'Content-Type: application/json' \
  -d '{"bizNo":"BIZ-20260927-0001","eventId":1,"ambulanceId":1,"crewId":1}'
```

## 测试

`src/test` 共 28 个自动化测试，覆盖：

- 联合派遣成功与区域/能力/值勤校验失败（失败不占用资源）；
- **并发争抢同一车+组**：双线程同时派遣，仅一个成功，且车与组归属同一条派遣；
- **抢占与到达竞争**：双线程并发抢占/到达，仅一个生效，事件与派遣状态保持一致；
- 幂等重放返回原结果（`replayed=true`）、同业务号不同内容 409；
- 高优先级抢占成功、到场后不可抢占、平级/低优先级不可抢占、能力不满足时抢占安全回滚原派遣不变；
- 抢占幂等与冲突、被抢占事件释放后可重新派遣；
- 到达/完成/取消终态行为与资源释放、终态不可修改；
- 事件派遣详情、资源时间线（ASSIGNED→PREEMPTED→ASSIGNED→ARRIVED→RELEASED）、多级抢占链查询；
- 统一错误响应结构（400/404/409）。
