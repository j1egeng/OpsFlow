# 登录与用户管理接口

基础地址为 `http://127.0.0.1:8080`，JSON 使用 UTF-8。下面六个接口已实现，业务数据使用已有的 PostgreSQL 表。

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/login` | 公开 | 200 |
| GET | `/api/v1/auth/me` | 已登录且账号启用 | 200 |
| POST | `/api/v1/users` | ADMINISTRATOR | 201 |
| GET | `/api/v1/users?page=0&size=20` | ADMINISTRATOR | 200 |
| PUT | `/api/v1/users/{id}/roles` | ADMINISTRATOR | 200 |
| PATCH | `/api/v1/users/{id}/status` | ADMINISTRATOR | 200 |

## 首次创建管理员

没有默认账号、默认密码或公开注册接口。首次管理员通过显式的本地启动配置初始化。已有启用的管理员时，初始化会跳过，不覆盖已有账号或密码。

在 `backend` 目录打开 PowerShell，为当前终端设置 Java 21，然后填写自己的管理员邮箱和密码：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$env:BOOTSTRAP_ADMIN_ENABLED = 'true'
$env:BOOTSTRAP_ADMIN_EMAIL = '填写你的邮箱'
$taskAdminCredential = Get-Credential -UserName 'admin' -Message '设置初始管理员密码：至少 12 个字符，UTF-8 最多 72 字节'
$env:BOOTSTRAP_ADMIN_USERNAME = $taskAdminCredential.UserName
$env:BOOTSTRAP_ADMIN_PASSWORD = $taskAdminCredential.GetNetworkCredential().Password

.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

数据库配置继续读取已有的 `application-local.yml`。初始化会创建一个管理员用户、一条角色关联及审计记录。初始化失败会回滚，不会留下半个账号。

首次启动结束后，清理当前终端中的初始化配置，今后按正常方式启动：

```powershell
Remove-Item Env:BOOTSTRAP_ADMIN_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
Remove-Variable taskAdminCredential -ErrorAction SilentlyContinue
```

如从 IDEA 启动，也可在 Run Configuration 的 Environment variables 中设置相同变量，并启用 `local` profile；首次成功后移除管理员密码和初始化开关。

## JWT 配置

登录成功返回 Bearer JWT，默认有效期为一小时。后续请求携带：

```http
Authorization: Bearer <accessToken>
```

签名使用 HS256，验证签名、有效期、签发者 `opsflow-backend`、受众 `opsflow-api` 和用户标识。每次请求重新读取账号状态和角色；账号停用后不能使用原令牌访问，角色修改会在下一次请求生效。重新启用账号后，尚未过期的旧令牌也可再次使用。

本地未设置签名密钥时，会生成仅保存在内存中的随机开发密钥，重启后原令牌失效。如需重启后继续使用令牌，在启动前配置稳定的随机密钥：

```powershell
$taskSigningKey = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($taskSigningKey)
$env:JWT_SECRET_BASE64 = [Convert]::ToBase64String($taskSigningKey)
$env:JWT_REQUIRE_PERSISTENT_KEY = 'true'
```

以上变量只保留在当前终端。长期使用时通过本地私有配置或部署环境保存同一密钥，部署时必须设置 `JWT_REQUIRE_PERSISTENT_KEY=true`；不要将密钥写入共享配置或 Git。`JWT_TTL` 可设置有效期，例如 `1h`，允许 1 秒至 24 小时。

当前不提供刷新令牌、服务器端注销或密码修改接口。客户端退出时删除持有的令牌。

## 登录

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "首次初始化时设置的密码"
}
```

成功响应：

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": 1,
    "username": "admin",
    "email": "your-email@example.com",
    "displayName": "Administrator",
    "enabled": true,
    "roles": ["ADMINISTRATOR"],
    "lastLoginAt": "2026-10-17T08:00:00Z",
    "createdAt": "2026-10-17T07:00:00Z",
    "updatedAt": "2026-10-17T08:00:00Z"
  }
}
```

示例中的 ID 和时间仅说明格式。实际值由数据库产生。用户名登录忽略大小写；密码错误、账号不存在和账号停用统一返回 401 `INVALID_CREDENTIALS`。

## 获取当前用户

```http
GET /api/v1/auth/me
Authorization: Bearer <accessToken>
```

返回当前用户对象，字段与登录响应中的 `user` 相同。所有用户响应均不包含密码或密码哈希。

## 管理员创建用户

```http
POST /api/v1/users
Authorization: Bearer <administratorToken>
Content-Type: application/json

{
  "username": "developer01",
  "email": "developer01@example.com",
  "displayName": "Developer One",
  "password": "replace-with-your-password",
  "roles": ["DEVELOPER"]
}
```

创建成功返回用户对象，账号默认启用。密码使用 BCrypt 编码，角色关联记录操作人，用户创建和审计写入同一事务。

字段规则：

- `username` 为 3—50 个 ASCII 字符，首字符为字母或数字，其余可含字母、数字、点、下划线和短横线。
- `email` 必填，格式有效且最长 254 个字符；`displayName` 必填且最长 100 个字符。
- 密码至少 12 个字符，UTF-8 编码后不超过 BCrypt 的 72 字节限制。
- `roles` 必须非空，可选 `ADMINISTRATOR`、`DEVELOPER`、`APPROVER`，可同时具有多个角色。
- 用户名和邮箱统一存为小写并保证唯一。

## 分页查询用户

```http
GET /api/v1/users?page=0&size=20
Authorization: Bearer <administratorToken>
```

`page` 从 0 开始，`size` 为 1—100，默认 20，按 ID 升序返回。

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

## 替换用户角色

```http
PUT /api/v1/users/2/roles
Authorization: Bearer <administratorToken>
Content-Type: application/json

{
  "roles": ["DEVELOPER", "APPROVER"]
}
```

这是完整替换角色集合，不能传空集合。返回更新后的用户对象；移除角色立即影响该用户现有令牌的下一次访问权限。系统不允许移除最后一个启用管理员的管理员角色。

## 启用或停用用户

```http
PATCH /api/v1/users/2/status
Authorization: Bearer <administratorToken>
Content-Type: application/json

{
  "enabled": false
}
```

返回更新后的用户对象。`true` 表示启用，`false` 表示停用，字段不能省略或为 null。系统不允许停用最后一个启用的管理员。

## 错误响应

```json
{
  "status": 403,
  "code": "FORBIDDEN",
  "message": "当前账号没有权限执行此操作",
  "path": "/api/v1/users",
  "fields": {},
  "timestamp": "2026-10-17T08:00:00Z"
}
```

| 状态码 | 常见情况 |
| --- | --- |
| 400 | 请求体错误、字段不合法、无效角色、分页超出范围 |
| 401 | 未登录、无效或过期令牌、错误密码、账号停用 |
| 403 | 已登录但缺少管理员角色 |
| 404 | 目标用户不存在 |
| 409 | 用户名/邮箱重复、并发冲突、操作会移除最后一个启用管理员 |

字段验证失败时，`fields` 返回字段名称和规则说明，不返回原始密码值。CORS 只允许已配置的前端来源。

## 审计和测试

成功登录、创建用户、修改角色、修改启用状态分别写入 `USER_LOGIN`、`USER_CREATED`、`USER_ROLES_UPDATED`、`USER_STATUS_UPDATED`。写入与业务数据处于同一事务，审计明细不包含密码或令牌。

```powershell
# H2 HTTP 接口测试与基础启动测试
.\mvnw.cmd test

# 显式运行本地 PostgreSQL 约束、实体映射和用户服务测试；样例记录自动回滚
$env:OPSFLOW_POSTGRES_TESTS = 'true'
.\mvnw.cmd test '-Dtest=AuthUserPostgresTests,DatabaseSchemaPostgresTests'
Remove-Item Env:OPSFLOW_POSTGRES_TESTS
```
