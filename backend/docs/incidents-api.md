# 事件管理 API

所有接口使用 `/api/v1/incidents` 前缀及 JWT Bearer 认证。复用现有 `incidents`、`incident_notes`、`incident_status_history` 三张表，不修改已执行的 Flyway 迁移。

## 权限与流程

- ADMINISTRATOR、DEVELOPER 可以报告事件；APPROVER 只读。所有启用的登录用户可查询。
- 只有管理员可以分配/重新分配、关闭事件。处理人必须是启用的管理员或开发者。
- 管理员或当前处理人（仍有 DEVELOPER 角色）可以开始调查、追加备注和解决事件。非当前处理人的开发者不能处理他人的事件。
- 唯一状态路径：`OPEN → ASSIGNED → INVESTIGATING → RESOLVED → CLOSED`。分配接口完成第一步；状态接口完成后三步。禁止跳步、回退、重新打开和删除。
- 已分配、调查中可以重新分配；调查中重新分配保持当前状态，分派变更写入审计。解决/关闭后不可重新分配或追加备注。
- 关联服务在报告时必须启用；以后服务停用不影响已有事件继续处理。关闭保留解决说明、处理人和所有历史。

## 接口

| 方法 | 路径 | 权限 / 作用 |
| --- | --- | --- |
| POST | `/api/v1/incidents` | 管理员/开发者报告事件，201 |
| GET | `/api/v1/incidents` | 分页列表，可按 status、severity、serviceId、assigneeId 筛选 |
| GET | `/api/v1/incidents/{id}` | 详情及当前账号可执行操作 |
| GET | `/api/v1/incidents/assignees` | 管理员分页选择启用的管理员/开发者，仅返回人员摘要 |
| POST | `/api/v1/incidents/{id}/assignment` | 管理员分派或重新分派 |
| POST | `/api/v1/incidents/{id}/transitions` | 开始调查、解决或关闭 |
| POST | `/api/v1/incidents/{id}/notes` | 管理员/当前处理人追加备注，201 |
| GET | `/api/v1/incidents/{id}/notes` | 分页备注 |
| GET | `/api/v1/incidents/{id}/history` | 分页状态历史（包括创建） |

所有分页 `page=0&size=20`，page≥0、size 1—100。事件、备注、历史均按 ID 倒序，最新记录优先；候选处理人按 ID 升序。筛选和计数在数据库完成。无匹配记录返回空页；不存在事件的备注/历史返回 404。

## 报告事件

```json
{"serviceId":1,"title":"支付接口超时","description":"请求持续超过 10 秒","severity":"HIGH"}
```

serviceId 为正整数；title 必填、非空白、最长 200 字符；description 必填、非空白、最长 10,000 字符。严重程度为 LOW/MEDIUM/HIGH/CRITICAL。首尾空白会去除。报告人从当前账号获取，初始状态 OPEN，无处理人。客户端不能指定状态、报告人、解决时间或来源变更。

响应包含 id、title、description、severity、status、service（id/code/name/enabled）、reporter/assignee（id/username/displayName/enabled，assignee 可为 null）、resolution、sourceChangeId、resolvedAt、closedAt、createdAt、updatedAt、version，以及 permissions：canAssign/canInvestigate/canResolve/canClose/canAddNote。这些操作权限基于当前用户和事件状态，后端会在写入时重新校验。

## 分派与状态更新

```json
{"assigneeId":2,"version":0,"comment":"请检查应用日志"}
```

上面是 assignment 请求。comment 可选、最多 2,000 字符。分派返回事件对象；第一次分派记录 OPEN→ASSIGNED，重新分派保持已有状态。相同处理人的重复分派不改变记录。

```json
{"status":"INVESTIGATING","version":1,"comment":"已开始排查"}
```

```json
{"status":"RESOLVED","version":2,"resolution":"修复连接池配置并验证请求恢复正常","comment":"验证完成"}
```

```json
{"status":"CLOSED","version":3,"comment":"复核后关闭"}
```

解决时 resolution 必填、非空白、最长 10,000 字符；其他状态不接受非空的 resolution。解决及关闭时间由服务器写入，客户端不能指定。所有分派/状态写入必须提交详情里的当前 version，旧版本返回 409 `CONCURRENT_UPDATE`；数据库乐观锁同时防止重叠事务覆盖。

## 备注和历史

```json
{"content":"数据库连接数已恢复，继续观察请求延迟","version":2}
```

备注 content 必填、非空白、最长 10,000 字符，version 必填。备注使事件版本增加，避免与同时发生的关闭/重新分派冲突；追加成功返回备注对象（id、incidentId、author 摘要、content、createdAt）。下一次状态操作先刷新事件详情。备注只允许追加，不能修改/删除。

历史条目包括 id、incidentId、oldStatus（创建时 null）、newStatus、changedBy 摘要、comment、changedAt。备注及历史分页均返回 `{items,page,size,totalElements,totalPages}`。写入业务、备注/历史和审计在同一事务完成。

## 错误及审计

沿用通用错误响应。400：字段无效、停用服务 `INCIDENT_SERVICE_DISABLED`、无效处理人 `INVALID_ASSIGNEE`、缺少解决说明 `RESOLUTION_REQUIRED`、错误使用解决说明 `UNEXPECTED_RESOLUTION`。401：未登录/令牌无效。403 `FORBIDDEN`：无写入权限。404：`INCIDENT_NOT_FOUND`/`SERVICE_NOT_FOUND`/`USER_NOT_FOUND`。409：`INVALID_INCIDENT_TRANSITION`、`INCIDENT_NOT_ASSIGNABLE`、`INCIDENT_NOT_WRITABLE`、`CONCURRENT_UPDATE`。失败不产生业务、历史或审计记录。

审计 action：INCIDENT_CREATED、INCIDENT_ASSIGNED、INCIDENT_STATUS_CHANGED、INCIDENT_NOTE_ADDED；entity_type=INCIDENT。审计记录事件、状态、处理人或备注 ID 等必要信息，不重复保存完整调查内容，不保存密码/令牌。

本模块暂不实现来源变更生成事件；sourceChangeId 只读，为后续变更失败自动建事件预留。

## 验证

`.\mvnw.cmd verify` 运行隔离 H2 HTTP 测试及回归。设置 `OPSFLOW_POSTGRES_TESTS=true` 后可运行 `IncidentPostgresTests`，验证真实表约束、实体映射、状态/备注及 JSONB 审计；测试记录自动回滚。前端 Chromium 测试使用隔离 H2，不写入本地业务库。
