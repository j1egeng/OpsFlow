# 变更管理与 Dashboard API

## 变更流程及权限

使用现有 change_requests、change_reviews、change_status_history 表，无需修改既有迁移。所有接口要求启用账号及 JWT。所有角色可以查询。管理员/开发者可以创建草稿；仅申请人（仍具有管理员/开发者角色）或管理员可以修改、提交及实施。只有 APPROVER 可以审批，且不能审批自己申请的变更；管理员也不能绕过这条规则。演示完整流程时使用两个不同账号。

状态路径：DRAFT → PENDING_APPROVAL → APPROVED / REJECTED；REJECTED 经修改回到 DRAFT，再提交会增加 submissionNumber，保留旧审批；APPROVED → IMPLEMENTING → COMPLETED / FAILED。禁止跳步、回退、重复执行、删除和提交后修改。

服务在创建、修改、提交、开始实施时必须启用；实施途中停用服务不阻止记录结果或失败事件生成。关联事件可为空，提供时必须存在且属于同一服务。一个失败变更只生成一个同服务事件，失败结果、关联事件、历史和审计在同一事务完成，失败全部回滚。

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| POST | `/api/v1/changes` | 创建草稿，201 |
| GET | `/api/v1/changes` | 分页查询，支持 status、riskLevel、serviceId、requesterId |
| GET | `/api/v1/changes/{id}` | 详情、当前操作权限及失败事件 ID |
| PUT | `/api/v1/changes/{id}` | 完整替换草稿资料；修改被拒绝申请时转回 DRAFT |
| POST | `/api/v1/changes/{id}/submit` | 提交审批 |
| POST | `/api/v1/changes/{id}/reviews` | 批准/拒绝 |
| GET | `/api/v1/changes/{id}/reviews` | 分页审批记录（包括所有提交轮次） |
| POST | `/api/v1/changes/{id}/implementation/start` | 开始实施 |
| POST | `/api/v1/changes/{id}/implementation/result` | 记录完成/失败，失败自动建事件 |
| GET | `/api/v1/changes/{id}/history` | 分页状态历史 |
| GET | `/api/v1/dashboard/summary` | 全局运营统计，仅查询 |

分页 page 默认 0、最小 0；size 默认 20、范围 1—100；按 ID 倒序，返回 items/page/size/totalElements/totalPages。所有筛选和总数由数据库计算。不存在实体的历史和审批列表返回 404。

## 草稿请求

```json
{
  "serviceId": 1,
  "relatedIncidentId": null,
  "title": "调整数据库连接池",
  "businessReason": "修复高峰期连接耗尽",
  "riskLevel": "MEDIUM",
  "plannedImplementationAt": "2026-11-01T12:00:00Z",
  "implementationPlan": "更新连接配置并进行健康检查",
  "rollbackPlan": "恢复原配置并滚动重启",
  "version": 0
}
```

创建时 serviceId/title 必填，version 忽略；PUT 时 version 必填，其余字段为完整替换，省略可空字段会清空。标题非空白、最多 200 字符；三项说明字段最多 10,000 字符。riskLevel 为 LOW/MEDIUM/HIGH。时间需带 UTC 或偏移量；无时区格式无效。草稿允许资料不完整、计划时间已过；提交时业务原因、风险、计划时间、实施计划和回滚计划均必填，计划时间必须在未来。开始实施不强制等待计划时间（计划时间为排期，不是定时执行），但仍需先获批准。

提交及开始实施请求：`{"version":0}`。审批请求：`{"version":1,"decision":"APPROVED","comment":"计划可行"}`；拒绝 decision 为 REJECTED 且 comment 必填，最长 2,000 字符。实施结果：`{"version":3,"outcome":"FAILED","result":"实施异常，已按回滚计划恢复配置"}`；outcome 只允许 COMPLETED/FAILED，result 必填且最多 10,000 字符。

响应包含服务摘要、申请人摘要、relatedIncidentId、所有计划字段、status、submissionNumber、submittedAt、implementationStartedAt/FinishedAt/Result、failureIncidentId、createdAt/updatedAt/version，以及 permissions（canEdit/canSubmit/canReview/canStart/canFinish）。用户摘要不含邮箱/密码。失败事件初始 OPEN，严重程度映射 LOW→LOW、MEDIUM→MEDIUM、HIGH→HIGH，sourceChangeId 指向当前变更；前端可双向跳转。

所有写入版本必须匹配当前详情，旧版本 409 CONCURRENT_UPDATE；@Version 同时防止重叠事务覆盖。失败结果重复提交不能产生第二个事件。审批轮次采用数据库唯一约束及乐观锁，不能对同一轮作出两次决策。

400：VALIDATION_ERROR、INVALID_REQUEST、VERSION_REQUIRED、CHANGE_SERVICE_DISABLED、RELATED_INCIDENT_SERVICE_MISMATCH、CHANGE_NOT_READY、INVALID_PLANNED_TIME、REJECTION_COMMENT_REQUIRED。403：FORBIDDEN、SELF_APPROVAL_FORBIDDEN。404：CHANGE_NOT_FOUND、SERVICE_NOT_FOUND、INCIDENT_NOT_FOUND。409：INVALID_CHANGE_STATE、CONCURRENT_UPDATE、DATA_CONFLICT。错误格式沿用现有 API。审计动作 CHANGE_CREATED/UPDATED/SUBMITTED/REVIEWED/STARTED/FINISHED，entity_type=CHANGE_REQUEST；不保存密码/令牌。

## Dashboard

`GET /api/v1/dashboard/summary` 对所有登录角色开放。返回 generatedAt、zoneId=UTC、totalServices/enabledServices、totalIncidents/activeIncidents、totalChanges/pendingApprovals/implementingChanges、completedChanges/failedChanges、changeSuccessRate（完成数÷已结束实施数×100，无结束记录为 null）、incidentsByStatus、incidentsBySeverity、changesByStatus、changesByRisk、最近七个 UTC 自然日（含今日）的新建事件/变更数量 trend、最近五条事件和变更摘要。

activeIncidents 仅 OPEN/ASSIGNED/INVESTIGATING；风险分组包括尚未评估的 UNASSESSED。状态分布包含数量为 0 的所有状态。趋势缺失日期补 0，按日期升序。统计是全库口径，不是列表当前页，也不含账号私密信息。采用只读 REPEATABLE_READ 事务保证一次响应内的一致快照；SQL 聚合在数据库执行，不加载全部业务对象。Dashboard 不会创建演示数据。

## 验证

后端 `.\mvnw.cmd verify`；显式设置 OPSFLOW_POSTGRES_TESTS=true 后增加真实 PostgreSQL 回滚测试。前端构建、单测和 Chromium 用隔离 H2 验证真实 HTTP 接口、审批往返、失败事件、统计变化、角色约束和双语页面，不写正式业务库。
