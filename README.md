# OpsFlow — 事件与变更管理平台

简体中文 | [English](README.en.md)

OpsFlow 是一个面向软件运维协作的 Web 项目，覆盖软件服务目录、事件处理、变更审批与实施记录、运营统计和审计查询。前端采用 React，后端采用 Spring Boot 模块化单体架构，业务数据保存在 PostgreSQL。

## 1. 项目功能

| 模块 | 已实现功能 |
| --- | --- |
| 登录与账户 | JWT 登录、当前账户、会话恢复、登录过期处理；BCrypt 密码散列和基于角色的权限校验 |
| 用户管理 | 管理员创建用户、分页查询、分配角色、启用/停用；保护最后一个启用的管理员 |
| 软件服务 | 服务目录、详情、创建、编辑、负责人选择、启用/停用和分页状态筛选 |
| 事件管理 | 报告事件、分派/重新分派、开始调查、追加备注、解决、关闭、状态历史和条件筛选 |
| 变更管理 | 草稿、关联同服务事件、提交审批、批准/拒绝、拒绝后修改重提、多轮审批历史、实施结果记录 |
| 失败联动 | 变更实施失败时，自动创建同服务事件；结果、事件、历史和审计在同一事务中保存 |
| 运营总览 | 服务/事件/变更统计、实施成功率、UTC 七日新增趋势、状态分布和最近记录 |
| 审计日志 | 管理员前端入口、分页筛选和只读详情，支持操作人、动作、对象、时间及中英文切换 |
| 界面与交互 | 中英文即时切换、桌面/手机布局、加载/空/错误状态、分页及并发版本冲突提示 |

事件流程：`OPEN → ASSIGNED → INVESTIGATING → RESOLVED → CLOSED`。

变更流程：`DRAFT → PENDING_APPROVAL → APPROVED → IMPLEMENTING → COMPLETED / FAILED`；拒绝时进入 `REJECTED`，修改后回到 `DRAFT`，重提保留之前的审批记录。

平台记录实施计划和结果，不自动执行发布、回滚或其他运维命令。目前没有邮件通知、公开注册、密码修改、刷新令牌或服务器端注销接口；退出登录由前端清理会话。事件和审计日志不提供修改历史或物理删除功能。

### 角色与权限

| 角色 | 主要权限 |
| --- | --- |
| `ADMINISTRATOR` | 用户和服务管理、事件分派与关闭、业务查询、变更维护与实施、审计查询 |
| `DEVELOPER` | 业务查询、报告事件、处理分配给自己的事件、维护和实施自己申请的变更 |
| `APPROVER` | 业务查询、批准或拒绝其他人的变更申请 |

审批必须具有 `APPROVER` 角色，管理员身份本身不授予审批权限。任何人都不能审批自己的变更，即使同时拥有多个角色。后端始终执行最终权限和状态检查。

## 2. 技术栈与结构

| 层次 | 技术 |
| --- | --- |
| 前端 | React 19.3.1、TypeScript 5.9.3、React Router 7.18.4、Vite 6.4.4、Lucide React、CSS |
| 后端 | Java 21、Spring Boot 3.5.16、Spring Web、Spring Data JPA / Hibernate、Bean Validation |
| 安全 | Spring Security、OAuth2 Resource Server 的 JWT 验证、HS256、BCrypt；不依赖外部 OAuth 服务 |
| 数据 | PostgreSQL 18（本地已验证 18.6）、Flyway 11.20.3；11 张业务表及 Flyway 迁移记录表 |
| 构建 | Maven Wrapper 3.9.11、npm 及 package-lock.json |
| 测试 | JUnit / Spring Boot Test、H2（仅测试）、Vitest 4.1.12、Playwright 1.64.1 / Chromium |
| 运行检查 | Spring Boot Actuator 健康检查 |

部署请求链路：`浏览器（127.0.0.1:8088）→ Nginx → Spring Boot（容器内部 8080）→ PostgreSQL（容器内部 5432）`。

```text
Architechitecting_Software_Solutions/
  README.md                 中文项目说明
  README.en.md              English project guide
  compose.yaml              前端、后端、数据库容器编排
  .env.docker.example       Docker 私有配置示例
  deploy/                   初始化脚本、部署文档及本机验收结果
  backend/                  Spring Boot 后端及 Maven Wrapper
    src/main/java/com/opsflow/  auth、users、services、incidents、changes、dashboard、audit
    src/main/resources/db/migration/  PostgreSQL 版本化迁移
    docs/                   数据库和 API 契约说明
    src/test/               HTTP、业务规则及 PostgreSQL 测试
  frontend/                 React + TypeScript 前端
    src/                    页面、组件、API 客户端及中英文词库
    e2e/                    浏览器测试及隔离测试后端
    package-lock.json       前端依赖锁定文件
```

## 3. Docker 部署与访问

当前使用 Docker Compose 在本机运行三个容器：Nginx 提供 React 构建产物，Spring Boot 提供 API，PostgreSQL 保存业务数据。访问 [http://127.0.0.1:8088/](http://127.0.0.1:8088/)。

### 3.1 已完成的部署验收

2026-10-18 已实际构建前后端镜像并启动容器：Docker Client 和 Linux Server 均正常，三个容器均健康；前端访问、API 反向代理、管理员网页登录、审计日志列表与详情、中英文切换及 7 个主要页面直接访问和刷新均通过，未检测到浏览器 JavaScript 运行时错误。

这是该次验收结果，不代表之后任何时刻的实时状态。本机证据保存在不提交 Git 的 `deploy/results/`，包括 `DEPLOYMENT-2026-10-18.md`、`docker-runtime.json`、`docker-verification.json` 和中英文截图。Docker 曾发生 Windows Secrets Engine socket 启动故障，后续恢复可用；恢复原因及再次重启后的可靠性尚未确认。GitHub Actions 尚未配置。

### 3.2 环境与隔离

- Windows 需要 Docker Desktop、WSL 2 和可用的 Linux 引擎；`docker version` 必须同时显示 Client 和 Server。
- Java、Maven、Node.js 和 Nginx 由镜像构建阶段提供；仅运行此部署无需在宿主机另装 JDK、Node.js 或 PostgreSQL。
- 只有前端绑定 `127.0.0.1:8088`；后端和数据库不发布宿主端口。
- 容器 PostgreSQL 18.6 使用命名卷 `opsflow_postgres_data`，挂载到 `/var/lib/postgresql`。
- 原 Windows PostgreSQL 开发数据库保留，与容器数据库独立；原开发账号和记录不会自动复制过来。

### 3.3 首次构建或在新电脑部署

在项目根目录打开 PowerShell。当前电脑已完成部署，日常使用直接看第 4 节。

```powershell
Set-Location 'E:\study flies\teamProject\Architechitecting_Software_Solutions'
docker version
.\deploy\Initialize-Docker.ps1
docker compose --env-file .env.docker -f compose.yaml config --quiet
docker compose --env-file .env.docker -f compose.yaml up -d --build --wait --wait-timeout 240
docker compose --env-file .env.docker -f compose.yaml ps
Invoke-RestMethod 'http://127.0.0.1:8088/actuator/health'
```

其他电脑请调整项目路径。若 PATH 找不到 Docker，当前电脑可用绝对路径 `C:\Users\26514\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe` 调用。

初始化脚本只在 `.env.docker` 不存在时生成随机数据库密码、管理员密码和固定 JWT 密钥，已有文件不会被覆盖。请保留当前私有配置，不要重新生成或提交真实凭据。首次构建需联网下载镜像和 Maven/npm 依赖。

Flyway 自动创建表和角色，bootstrap 仅在尚无启用管理员时初始化管理员。无需手工创建数据库或配置宿主机 `application-local.yml`。`config --quiet` 只校验配置，仍需确认容器健康、页面和 API 可用。

### 3.4 查看本机登录凭据

打开 [http://127.0.0.1:8088/](http://127.0.0.1:8088/)，在本机用记事本打开私有配置：

```powershell
notepad .env.docker
```

查看 `BOOTSTRAP_ADMIN_USERNAME` 和 `BOOTSTRAP_ADMIN_PASSWORD` 后登录。不要把文件内容、密码、JWT 密钥或完整展开的 Compose 配置发到聊天、截图或版本库。重新启动不会覆盖已有启用管理员的密码。

## 4. 日常启停、更新与数据库持久化

### 4.1 再次启动和检查

先启动 Docker Desktop，等待 Linux 引擎就绪，再在项目根目录执行：

```powershell
docker compose --env-file .env.docker -f compose.yaml up -d --wait --wait-timeout 240
docker compose --env-file .env.docker -f compose.yaml ps
Invoke-RestMethod 'http://127.0.0.1:8088/actuator/health'
```

三个服务应为 `healthy`，健康接口应返回 `{"status":"UP"}`，然后访问 8088。日常使用无需重新构建或分别运行 Maven、Vite。

查看应用日志：

```powershell
docker compose --env-file .env.docker -f compose.yaml logs --tail 100 backend frontend
```

### 4.2 正常停止与代码更新

退出 Docker Desktop 或关机前，可先正常停止服务：

```powershell
docker compose --env-file .env.docker -f compose.yaml stop
```

修改代码后重新构建并启动：

```powershell
docker compose --env-file .env.docker -f compose.yaml up -d --build --wait --wait-timeout 240
```

使用同一项目配置和命名卷时，重新构建镜像或重建容器不会清空已有数据库。Flyway 执行待应用的迁移，不要修改已经执行的迁移。

### 4.3 关闭 Docker 后数据会消失吗？

**正常退出 Docker Desktop、停止容器或重启电脑不会自动清空数据库。** 已提交的用户、服务、事件、变更和审计日志保存在命名卷 `opsflow_postgres_data` 中，独立于容器生命周期。Docker 关闭期间服务暂时不可访问，启动同一套 Compose 后继续使用原数据。

| 操作 | 数据是否保留 |
| --- | --- |
| 关闭 Docker Desktop 窗口 | 保留；引擎可能仍在后台运行 |
| 正常退出 Docker Desktop、停止容器、重启电脑 | 保留，服务重新启动后可访问 |
| `docker compose ... stop` | 保留容器和命名卷 |
| 不带 `-v` 的 `docker compose ... down` | 删除容器和网络，保留此命名卷；再次 `up` 时复用 |
| 使用同一卷重新构建镜像或重建容器 | 保留 |
| `down -v`、删除该卷，或执行会删除该卷的清理操作 | 会删除容器数据库数据 |
| 恢复出厂设置或清除 Docker 数据 | 可能删除数据库数据，操作前必须备份 |

不要随意执行 `down -v`、删除 `opsflow_postgres_data` 或清理数据卷。改变 Compose 项目名、卷名或切换 Docker 引擎可能连接到另一套空数据库，不代表原卷已被清空。

持久化不等于备份，重要记录仍需单独备份。应正常停止服务，避免强制结束进程或断电。保留 `.env.docker`：它包含凭据和 JWT 密钥，但不包含数据库业务数据。

参考 Docker 官方[数据卷说明](https://docs.docker.com/engine/storage/volumes/)和 [Compose down 说明](https://docs.docker.com/reference/cli/docker/compose/down/)。详细操作见 [Docker 部署文档](deploy/README.md)。

## 5. 测试与演示

### 自动化检查

以下命令仅用于开发测试，需要宿主机 JDK 21、Node.js 22+ 及已安装的 npm 依赖；日常运行 Docker 部署无需执行这些命令。镜像构建跳过业务测试。

后端（在 `backend`，使用 Java 21）：

```powershell
$env:BOOTSTRAP_ADMIN_ENABLED = 'false'
$env:OPSFLOW_POSTGRES_TESTS = 'false'
.\mvnw.cmd verify
```

常规测试使用内存 H2；真实 PostgreSQL 测试默认跳过。只有明确需要时，设置 `OPSFLOW_POSTGRES_TESTS=true` 再执行 `verify`：它会读取 `local` 配置、验证/应用迁移，测试样例数据通过事务回滚。迁移以及数据库序列的推进不会回滚，不要把“样例回滚”理解为整个数据库完全没有变化。

前端（在 `frontend`）：

```powershell
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
npx.cmd playwright install chromium
npm.cmd run test:e2e
```

端到端测试需要 JDK 21，会自动启动 18081 上的隔离 H2 测试后端、4173 上的测试前端和 Chromium，不使用本地 PostgreSQL。不要占用这两个测试端口。

### 一个人手工演示

1. 管理员创建一个启用的软件服务、一个开发者账号和一个独立审批人账号。
2. 开发者报告事件；管理员分派；开发者开始调查、写备注并解决；管理员关闭。
3. 开发者创建变更，补齐原因、风险、未来计划时间、实施和回滚计划，再提交。
4. 退出并换审批人批准，切回开发者开始实施并记录结果。
5. 失败时检查自动生成的关联事件；成功时确认不会生成失败事件；刷新运营总览核对统计。
6. 检查中英文切换、筛选、分页、必填校验和越权限制；普通试用记录会保留，建议使用 `TEST-` 前缀。

## 6. 常见问题与安全说明

| 现象 | 检查方法 |
| --- | --- |
| 后端无法启动 | 检查 Docker Linux 引擎、容器健康状态、backend/db 日志及保留的 .env.docker 凭据 |
| 前端打不开 | 确认 Docker Desktop 和容器已启动，访问 http://127.0.0.1:8088/ |
| 前端提示连接失败 | 检查 8088 的 /actuator/health、backend/db 健康状态及 Nginx 日志 |
| 无法登录 | 在本机查看 .env.docker 的管理员凭据；容器数据库不使用原开发数据库账号 |
| 重启后需要重新登录 | Docker 使用固定 JWT 密钥；令牌仍会过期，关闭标签页也可能清除会话，不代表数据库数据丢失 |
| 403 无权限 | 检查角色、当前处理人/申请人，以及是否尝试审批自己的变更 |
| 409 版本冲突 | 其他页面已修改该记录；刷新详情后重新操作，不要重复提交旧版本 |
| 变更无法提交 | 补齐全部计划字段、选择未来时间、确认服务启用及关联事件属于同一服务 |

JWT 使用当前标签页的 `sessionStorage`；只有语言偏好保存在 `localStorage`。不要将密码、访问令牌或签名密钥放入前端配置、URL 或版本库。

Docker 部署已通过私有配置提供稳定的 `JWT_SECRET_BASE64`，并设置 `JWT_REQUIRE_PERSISTENT_KEY=true`。不要每次启动生成新密钥；详细配置见登录接口文档。CORS 来源通过 `CORS_ALLOWED_ORIGINS` 配置，当前 Docker 部署允许本机 8088 来源。

Docker 构建已通过 `.dockerignore` 排除私有 `application-local.*`。如另行在宿主机开发构建，`.gitignore` 不控制 Maven 打包，私有配置仍可能进入 JAR；不要分享含凭据的构建产物。

## 7. 更多文档

- [Docker 部署、日常启停与数据持久化](deploy/README.md)
- [后端运行与接口索引](backend/README.md)
- [前端运行与测试说明](frontend/README.md)
- [数据库结构](backend/docs/database.md)
- [登录、用户管理和管理员初始化](backend/docs/auth-users-api.md)
- [软件服务 API](backend/docs/services-api.md)
- [事件 API](backend/docs/incidents-api.md)
- [变更与 Dashboard API](backend/docs/changes-dashboard-api.md)
- [审计日志 API](backend/docs/audit-logs-api.md)
