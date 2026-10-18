# OpsFlow Backend

项目总览及 Docker 部署步骤：[简体中文](../README.md) | [English](../README.en.md)

当前部署在容器中运行后端与独立 PostgreSQL，统一通过 [http://127.0.0.1:8088/](http://127.0.0.1:8088/) 访问。日常启停、凭据与数据库持久化见 [Docker 部署文档](../deploy/README.md)。下面的 Maven 和本机数据库步骤仅用于宿主机开发调试。

OpsFlow 事件与变更管理系统后端。当前包含本地 PostgreSQL 配置、11 张业务表的 Flyway 迁移、JWT 登录、当前用户、管理员用户管理、软件服务管理、事件生命周期、变更审批与实施、Dashboard 汇总、管理员审计日志查询、健康检查和自动化测试。

## 技术栈

- Java 21、Spring Boot 3.5.16、Maven 3.9.11。
- Spring Web、Spring Data JPA、Bean Validation、Actuator。
- Spring Security、JWT Bearer 认证、BCrypt 密码编码。
- PostgreSQL 用于本地运行；H2 仅用于自动化测试。
- Flyway 11.20.3 管理 PostgreSQL 结构迁移和基础角色初始化。
- JUnit 和 Spring Boot Test 用于测试。

## 宿主机开发调试（可选）

在当前电脑上打开 PowerShell，进入 `backend`，为当前终端指定已有的 JDK 21：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

这只影响当前终端，不会修改系统默认 Java 8。IDEA 中也需要把 Project SDK、Maven Runner JRE 设置为 Java 21。

在 pgAdmin 或 psql 中创建专用数据库（如果已经存在则跳过），数据库名也可通过后续配置改为已有的项目专用库：

```sql
CREATE DATABASE opsflow;
```

配置数据库有两种方式，任选一种。

### 方式一 使用当前终端环境变量

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/opsflow'
$env:DB_USERNAME = '你的数据库用户名'
$taskDbCredential = Get-Credential -UserName $env:DB_USERNAME -Message '请输入本地 PostgreSQL 密码'
$env:DB_PASSWORD = $taskDbCredential.GetNetworkCredential().Password
.\mvnw.cmd spring-boot:run
```

### 方式二 使用本地配置文件

```powershell
if (-not (Test-Path -LiteralPath 'src/main/resources/application-local.yml')) {
    Copy-Item -LiteralPath 'src/main/resources/application-local.yml.example' -Destination 'src/main/resources/application-local.yml'
}
```

填写复制后文件里的用户名、密码和数据库名，然后运行：

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

`application-local.yml` 已在 `.gitignore` 中排除。`.example` 文件只是模板，不会自动生效。

默认监听 `127.0.0.1:8080`。数据库必须已存在、服务正在运行且账号有访问权限。启动时由 Flyway 执行尚未应用的版本化迁移，创建业务表并初始化三种角色；Hibernate 保持 `ddl-auto: validate`，不会自行创建或更新表。

首次使用需显式初始化管理员，没有预置用户或默认密码。请按 [登录与用户管理接口说明](docs/auth-users-api.md) 设置初始化邮箱和密码，并查看 JWT 密钥配置、请求示例和权限规则。

数据库字段、关系、约束以及真实 PostgreSQL 测试方式见 [数据库设计说明](docs/database.md)。

## 接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/v1/system/info` | 返回服务名称和版本 |
| GET | `/actuator/health` | 检查应用及数据库连接，正常时返回 `{"status":"UP"}` |
| POST | `/api/v1/auth/login` | 登录并签发 JWT |
| GET | `/api/v1/auth/me` | 已登录用户查询本人资料和角色 |
| POST | `/api/v1/users` | 管理员创建用户 |
| GET | `/api/v1/users` | 管理员分页查询用户 |
| PUT | `/api/v1/users/{id}/roles` | 管理员替换用户角色 |
| PATCH | `/api/v1/users/{id}/status` | 管理员启用/停用用户 |
| POST | `/api/v1/services` | 管理员创建软件服务 |
| GET | `/api/v1/services` | 登录用户分页查询服务，可按 enabled 筛选 |
| GET | `/api/v1/services/{id}` | 登录用户查看服务详情 |
| PUT | `/api/v1/services/{id}` | 管理员完整替换服务资料 |
| PATCH | `/api/v1/services/{id}/status` | 管理员启用/停用服务 |
| POST | `/api/v1/incidents` | 管理员/开发者报告事件 |
| GET | `/api/v1/incidents` | 登录用户按状态、严重程度、服务、处理人筛选事件 |
| GET | `/api/v1/incidents/{id}` | 事件详情及当前用户可执行操作 |
| GET | `/api/v1/incidents/assignees` | 管理员分页选择候选处理人 |
| POST | `/api/v1/incidents/{id}/assignment` | 管理员分派/重新分派 |
| POST | `/api/v1/incidents/{id}/transitions` | 当前处理人或管理员开始调查/解决；管理员关闭 |
| POST / GET | `/api/v1/incidents/{id}/notes` | 追加 / 分页查询调查备注 |
| GET | `/api/v1/incidents/{id}/history` | 分页查看状态历史 |
| POST / GET | `/api/v1/changes` | 创建草稿 / 分页筛选变更 |
| GET / PUT | `/api/v1/changes/{id}` | 详情 / 修改草稿 |
| POST | `/api/v1/changes/{id}/submit` | 提交审批 |
| POST / GET | `/api/v1/changes/{id}/reviews` | 批准或拒绝 / 查询各轮审批记录 |
| POST | `/api/v1/changes/{id}/implementation/start` | 开始实施 |
| POST | `/api/v1/changes/{id}/implementation/result` | 记录成功或失败，失败自动创建同服务事件 |
| GET | `/api/v1/changes/{id}/history` | 变更状态历史 |
| GET | `/api/v1/dashboard/summary` | 运营汇总、成功率、UTC 七日趋势及最近记录 |
| GET | `/api/v1/audit-logs` | 管理员分页筛选审计日志 |
| GET | `/api/v1/audit-logs/{id}` | 管理员查看审计详情 |

前端开发允许来自 `http://localhost:5173` 和 `http://127.0.0.1:5173` 的 `/api/**` 请求。可通过 `CORS_ALLOWED_ORIGINS` 设置逗号分隔的来源列表。健康检查、服务信息和登录公开访问，其余接口需要 `Authorization: Bearer <token>`；用户管理和服务写操作额外要求 ADMINISTRATOR 角色。服务字段、负责人校验、分页、启停规则及错误示例见 [软件服务管理接口说明](docs/services-api.md)。

事件流程为 OPEN → ASSIGNED → INVESTIGATING → RESOLVED → CLOSED。`IncidentStatus` 集中定义允许的状态边，`IncidentService` 校验角色、当前处理人、版本和解决说明，再原子写入业务、状态历史和审计。备注同样增加聚合版本，避免与分派/关闭竞争。完整说明见 [事件管理接口](docs/incidents-api.md)。不提供事件删除或重新打开接口。

变更流程为 DRAFT → PENDING_APPROVAL → APPROVED / REJECTED；拒绝后修改回草稿并可再次提交，保留每轮审批。批准后可 IMPLEMENTING → COMPLETED / FAILED。只有 APPROVER 可审批且不能审批自己的申请，ADMINISTRATOR 也不能绕过该约束；申请人（具有管理员/开发者角色）或管理员可维护和实施。所有变更写操作校验 version。

失败结果、同服务关联事件、双方状态历史和审计在同一事务中保存，事件创建出错时全部回滚。Dashboard 是数据库聚合查询及一致快照，统计全库而非分页数据。字段、权限、计算口径见 [变更及 Dashboard 契约](docs/changes-dashboard-api.md)。

## 测试与打包

审计日志仅管理员可查询，支持操作人、动作、对象类型/ID、起止时间组合筛选，固定按发生时间和 ID 倒序。无日志写入、修改或删除接口。参数、详情字段和错误响应见 [审计日志查询接口](docs/audit-logs-api.md)。前端管理员菜单提供对应列表、筛选、分页及中英文只读详情。

```powershell
.\mvnw.cmd clean verify
```

常规接口测试使用独立的 `test` profile 和内存 H2，通过真实 HTTP 验证登录、权限、用户管理、软件服务管理、事件流程、健康检查以及跨域请求规则，不需要你的本地 PostgreSQL 密码。PostgreSQL 表结构、用户、软件服务和事件测试默认跳过，显式设置 `OPSFLOW_POSTGRES_TESTS=true` 后才会读取 local 配置、应用迁移并验证真实约束和实体映射；测试样例数据会自动回滚。

```powershell
java -jar target/opsflow-backend-0.0.1-SNAPSHOT.jar
```

运行 jar 同样需要正确的 PostgreSQL 配置。初次执行 Maven Wrapper 需要联网下载固定版本的 Maven 和项目依赖。

## 目录与后续开发

```text
backend/
  .mvn/wrapper/              Maven Wrapper 配置
  src/main/java/com/opsflow/
    OpsFlowApplication.java  启动入口
    config/                  通用 Web 配置
    auth/                    JWT、登录和管理员初始化
    users/                   用户、角色及用户管理
    services/                软件服务、负责人及启停管理
    incidents/               事件状态机、分派、备注和历史
    changes/                 草稿、提交、审批、实施及失败联动
    dashboard/               全局状态聚合、成功率和 UTC 趋势
    common/                  错误响应和通用校验
    audit/                   业务审计记录及管理员只读查询
    system/                  服务信息接口
  src/main/resources/        应用配置和本地配置示例
    db/migration/            PostgreSQL 版本化迁移
  src/test/                  自动化测试及测试专用配置
  docs/database.md           数据库设计说明
  docs/auth-users-api.md     登录、用户管理接口及初始化步骤
  docs/services-api.md       软件服务管理接口与验证方法
  docs/incidents-api.md      事件流程、权限及接口契约
  docs/changes-dashboard-api.md  变更与运营统计接口
  docs/audit-logs-api.md      管理员审计查询接口契约
  pom.xml
  mvnw
  mvnw.cmd
```

核心业务模块已实现。后续可进行项目部署、CI 集成、整体验收及报告证据整理；当前不包含自动执行实施脚本、邮件通知或外部运维系统集成。
