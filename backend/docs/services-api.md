# 软件服务管理接口

基础地址 `http://127.0.0.1:8080`，请求和响应为 JSON。使用现有 `software_services` 表，无需新建表或修改已执行的迁移。

所有接口需要 `Authorization: Bearer <accessToken>`，账号必须启用。

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/v1/services` | ADMINISTRATOR | 201 |
| GET | `/api/v1/services` | 所有登录用户 | 200 |
| GET | `/api/v1/services/{id}` | 所有登录用户 | 200 |
| PUT | `/api/v1/services/{id}` | ADMINISTRATOR | 200 |
| PATCH | `/api/v1/services/{id}/status` | ADMINISTRATOR | 200 |

## 创建与修改

POST 创建及 PUT 完整替换基础资料使用相同请求体：

```json
{
  "code": "payment-api",
  "name": "支付服务",
  "description": "管理支付请求及支付状态",
  "ownerId": 2
}
```

- `code` 必填，1—50 个 ASCII 字符，首字符为字母或数字，之后允许字母、数字、点、下划线和短横线；统一存为小写，忽略大小写唯一。停用服务仍占用编码。
- `name` 必填，不能全为空白，最多 100 个字符；保存时去除首尾空白。
- `description` 可省略或为 null，最多 10,000 个字符；去除首尾空白，全空白保存为 null。
- `ownerId` 可省略或为 null，表示未分配负责人；传入时必须是正整数，对应存在且启用的用户，角色不限。管理员可通过现有用户分页接口选择负责人。
- PUT 是完整替换上述四个字段，省略 `ownerId` 或 `description` 将清空对应字段；不会改变启停状态、创建人或创建时间。
- 创建人从当前 JWT 获取，不接受客户端指定；创建时默认启用。

创建、查询详情、修改和启停均返回相同的服务对象，例如：

```json
{
  "id": 1,
  "code": "payment-api",
  "name": "支付服务",
  "description": "管理支付请求及支付状态",
  "ownerId": 2,
  "owner": {
    "id": 2,
    "username": "developer01",
    "displayName": "Developer One",
    "enabled": true
  },
  "enabled": true,
  "createdBy": 1,
  "createdAt": "2026-10-17T08:00:00Z",
  "updatedAt": "2026-10-17T08:00:00Z",
  "version": 0
}
```

ID、时间仅为格式示例。未分配负责人时 `ownerId` 和 `owner` 都为 null。负责人摘要不包含邮箱、密码或角色。负责人之后被停用不会删除服务上的历史关联，摘要中的 `enabled` 会反映当前状态；再次分配负责人时仍需选择启用用户。

## 列表与详情

```http
GET /api/v1/services?page=0&size=20&enabled=true
GET /api/v1/services/1
```

`page` 从 0 开始，默认 0；`size` 默认 20，允许 1—100；按 ID 升序排序。`enabled` 可省略（返回所有状态）、为 true（仅启用）或 false（仅停用）。筛选在数据库完成，计数及分页均针对筛选结果。停用服务仍可查看详情。

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

## 启停

```http
PATCH /api/v1/services/1/status
Content-Type: application/json

{"enabled": false}
```

`enabled` 必填且不能为 null。重复设置同一状态仍成功。重新启用时，如已有关联负责人，该负责人必须仍启用，否则需先通过 PUT 更换或清空负责人。停用操作不要求负责人启用。

不提供物理删除，不删除未来关联的事件、变更及审计历史。本模块不实现事件/变更接口，后续在这些模块中校验新建记录所选服务是否启用。

## 错误与审计

沿用 [通用错误格式](auth-users-api.md#错误响应)。

| 状态码 | code | 情况 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR / INVALID_REQUEST | 字段、路径 ID、分页或布尔参数无效 |
| 400 | SERVICE_OWNER_DISABLED | 所选负责人已停用，或启用服务时现有负责人已停用 |
| 401 | UNAUTHENTICATED | 未登录、令牌失效或账号已停用 |
| 403 | FORBIDDEN | 非管理员尝试写入 |
| 404 | SERVICE_NOT_FOUND | 服务不存在 |
| 404 | USER_NOT_FOUND | 指定负责人不存在 |
| 409 | SERVICE_CODE_EXISTS | 编码与其他服务重复（忽略大小写） |
| 409 | DATA_CONFLICT | 数据库检测到重复编码或其他约束冲突，包括并发创建冲突 |
| 409 | CONCURRENT_UPDATE | Hibernate 检测到重叠事务更新冲突，刷新后重试 |

实体使用数据库 `version` 乐观锁，阻止重叠事务丢失更新；当前接口没有客户端版本前置条件，不承诺阻止用户拿旧页面在另一个事务完成后覆盖资料。

写操作分别记录 `SERVICE_CREATED`、`SERVICE_UPDATED`、`SERVICE_STATUS_UPDATED`，`entity_type=SERVICE`，记录操作人及变更明细，与业务写入处于同一事务。读取不写审计日志。

## 验证

```powershell
# H2 隔离环境下通过真实 HTTP 验证五个接口及认证、权限、校验和审计
.\mvnw.cmd test

# 本地 PostgreSQL：验证实体映射、更新触发器及 JSONB 审计，测试数据自动回滚
$env:OPSFLOW_POSTGRES_TESTS = 'true'
.\mvnw.cmd test '-Dtest=SoftwareServicePostgresTests'
Remove-Item Env:OPSFLOW_POSTGRES_TESTS
```
