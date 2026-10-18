# 审计日志查询 API

所有接口仅允许启用且持有 ADMINISTRATOR 角色的 JWT 用户访问；匿名为 401，其他角色为 403。复用已有 audit_logs 表，不增加迁移，不提供创建、修改或删除接口。业务操作仍由原业务事务写入审计；查询本身不产生新日志。

## 列表

`GET /api/v1/audit-logs`

| 参数 | 规则 |
| --- | --- |
| page / size | 默认 0 / 20；page ≥ 0，size 为 1—100 |
| actorId | 可选，正整数，操作人 ID |
| action | 可选，精确匹配，1—80 字符、非空白；区分大小写，例如 SERVICE_CREATED |
| entityType | 可选，USER / ROLE / SERVICE / INCIDENT / CHANGE_REQUEST |
| entityId | 可选，正整数；建议与 entityType 一起使用 |
| from | 可选，含时区的 ISO 8601 时间，包含该时刻 |
| to | 可选，含时区的 ISO 8601 时间，不包含该时刻；提供两个边界时必须 from < to |

筛选条件按 AND 组合，由数据库执行。固定 occurredAt 倒序，再按 id 倒序；同一时刻的记录顺序稳定。不接受自定义排序。分页返回 items/page/size/totalElements/totalPages，越界页或没有匹配项返回空列表（200）。页码分页不是跨请求快照；并发新增日志时建议重新加载第一页。

示例：`GET /api/v1/audit-logs?entityType=SERVICE&entityId=1&action=SERVICE_CREATED&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z`

items 中每条记录为：

```json
{
  "id": 42,
  "actor": {"id": 1, "username": "admin", "displayName": "Administrator"},
  "action": "SERVICE_CREATED",
  "entityType": "SERVICE",
  "entityId": 1,
  "occurredAt": "2026-10-18T08:00:00Z"
}
```

actor 为当前账号的安全摘要，不是历史姓名快照；系统日志可为 null。列表不含 details，避免加载给调用方的完整详情。未匹配的 actorId/entityId/action 返回空列表，不要求目标业务记录仍存在。

## 详情

`GET /api/v1/audit-logs/{id}`：id 必须为正整数。返回上述字段及 details（JSON 对象，可包含嵌套对象和 null）。不序列化用户实体、邮箱、密码散列或访问令牌。现有写入端不会保存密码或令牌；details 内容沿用各业务写入端的事件载荷，不生成虚构历史。request_id/client_ip 尚未由应用采集，本接口不返回这些列。

400：INVALID_REQUEST（参数格式、范围、未知 entityType）、INVALID_TIME_RANGE（from ≥ to）。404：AUDIT_LOG_NOT_FOUND。错误响应沿用 status/code/message/path/fields/timestamp。管理员向上述路径 POST/PUT/PATCH/DELETE 返回 405。

## 验证

AuditLogApiTests 使用隔离 H2 和真实 HTTP 验证权限、分页、组合筛选、时间边界、详情、只读及错误响应。AuditLogPostgresTests 通过 OPSFLOW_POSTGRES_TESTS=true 显式启用，验证真实 PostgreSQL JSONB、可空 actor、时间筛选及排序，样例数据事务回滚。
