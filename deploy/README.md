# OpsFlow Docker 本地部署 / Local Docker Deployment

## 简体中文

### 已完成的本机部署

2026-10-18 已实际构建前后端镜像并启动三个容器，三个容器均通过健康检查。前端 8088、API 反向代理、管理员网页登录、审计日志列表与详情、中英文切换及 7 个主要页面直接访问和刷新均通过。私有 `.env.docker` 和原 Windows PostgreSQL 开发数据库保留。

本机证据保存在不提交 Git 的 `deploy/results/`。这是该次验收结果，当前状态以 `compose ps` 和健康检查为准；当前电脑日常使用直接执行下文“再次启动”，无需重新初始化凭据或重新构建。GitHub Actions 尚未配置。

### 运行前提与隔离范围

需要 Docker Desktop 的 Linux 引擎运行正常，并提供 Docker Compose。在 Windows 上先完成 WSL 2 安装；仅有 `docker` 客户端版本输出不足以证明引擎可用，`docker version` 必须同时显示 Client 和 Server。

此方案创建独立的 PostgreSQL 18.6 容器和 `opsflow_postgres_data` 数据卷，不连接、清空或迁移现有 Windows PostgreSQL。数据库和后端不发布宿主端口，只有前端发布到 `127.0.0.1:8088`，因此不会占用开发环境的 5173 / 8080 / 5432。这是本地演示部署，不会发布到互联网。

链路为：浏览器 → Nginx → Spring Boot → 容器 PostgreSQL。Nginx 提供 React 构建产物、SPA 路由回退和 API 反向代理；不使用 Vite 开发服务器。

### 首次启动

在项目根目录运行以下 PowerShell 命令：

```powershell
.\deploy\Initialize-Docker.ps1
docker compose --env-file .env.docker -f compose.yaml config --quiet
docker compose --env-file .env.docker -f compose.yaml up -d --build --wait --wait-timeout 240
docker compose --env-file .env.docker -f compose.yaml ps
Invoke-RestMethod 'http://127.0.0.1:8088/actuator/health'
```

初始化脚本生成本地私有 `.env.docker`，包含随机数据库密码、随机管理员密码和固定 JWT 密钥；再次执行不会覆盖已有凭据。可用 `-AdminUsername`、`-AdminEmail`、`-HttpPort` 参数自定义初始化资料。也可参考根目录 `.env.docker.example` 手工配置，但不要使用空密码或把真实凭据提交到 Git。

打开 `http://127.0.0.1:8088/`。从本机 `.env.docker` 读取 `BOOTSTRAP_ADMIN_USERNAME` 和 `BOOTSTRAP_ADMIN_PASSWORD` 登录；不要把该文件、密码或完整 Compose 展开配置发到公共聊天或截图。这里是全新数据库，原本开发环境的用户和业务记录不会自动出现。

Flyway 自动建表和初始化角色。首次管理员创建成功后，可将 `.env.docker` 中的 `BOOTSTRAP_ADMIN_ENABLED` 改为 `false`，再执行：

```powershell
docker compose --env-file .env.docker -f compose.yaml up -d backend
```

保留其他凭据，特别是数据库密码和 JWT 密钥。重新生成密码不会自动修改已有数据库账号。已有启用管理员时，bootstrap 不会覆盖密码。

### 再次启动、检查、日志、停止和更新

先启动 Docker Desktop，等待 Linux 引擎就绪，再在项目根目录运行。日常启动无需 `--build`；修改代码后才需要重新构建。

```powershell
docker compose --env-file .env.docker -f compose.yaml up -d --wait --wait-timeout 240
docker compose --env-file .env.docker -f compose.yaml ps
docker compose --env-file .env.docker -f compose.yaml logs --tail 100 backend frontend
docker compose --env-file .env.docker -f compose.yaml stop
docker compose --env-file .env.docker -f compose.yaml up -d --build --wait --wait-timeout 240
```

上面的 `stop` 用于正常停止，最后一条带 `--build` 的命令用于代码更新。可以先执行 `stop`，再正常退出 Docker Desktop 或关机。

### 关闭 Docker 与数据保留

**正常退出 Docker Desktop、停止容器或重启电脑，不会自动清空已提交的数据库内容。** 用户、服务、事件、变更和审计日志保存在命名卷 `opsflow_postgres_data` 中。Docker 关闭期间服务不可访问，再次启动同一套 Compose 后继续使用原数据。仅关闭 Docker Desktop 窗口可能仍让引擎在后台运行。

| 操作 | 数据结果 |
| --- | --- |
| 正常退出 Docker Desktop、`stop`、重启电脑 | 保留 |
| 不带 `-v` 的 `down` | 移除容器和网络，保留此命名卷，再次 `up` 时复用 |
| 使用同一卷重新构建镜像或重建容器 | 保留 |
| `down -v`、删除该卷、会删除该卷的清理操作 | 删除容器数据库数据 |
| 恢复出厂设置或清除 Docker 数据 | 可能删除数据，操作前必须备份 |

不要随意删除 `opsflow_postgres_data` 或清理数据卷。改变 Compose 项目名、卷名或切换 Docker 引擎可能连接到另一套空数据库，不代表原卷已被清空。持久化不是备份，对重要记录仍需单独备份。保留 `.env.docker`：它保存凭据和 JWT 密钥，但不包含数据库业务数据。PostgreSQL 18 的卷挂载在 `/var/lib/postgresql`，不是旧版的 `/var/lib/postgresql/data`。

参考 Docker 官方[数据卷说明](https://docs.docker.com/engine/storage/volumes/)和 [Compose down 说明](https://docs.docker.com/reference/cli/docker/compose/down/)。

容器健康检查覆盖前端 `/healthz`、后端 `/actuator/health` 和数据库 `pg_isready`；`depends_on` 等待依赖健康后再启动。前端 `/healthz` 只说明 Nginx 正常，要检查数据库链路请使用 `/actuator/health`，再登录验收业务。

### 构建与验证边界

- 后端镜像使用仓库的 Maven Wrapper 和 Java 21 构建，再用非 root 用户运行 JRE 21；Java 健康检查无需 curl 或密码。
- 前端镜像使用 Node.js 22 构建，使用 Nginx 提供静态文件。
- `.dockerignore` 排除后端私有 `application-local.*`、前端私有环境文件、缓存和测试结果，避免将本机数据库凭据打入镜像。
- 镜像构建跳过业务测试，部署前需单独执行后端 `verify`、前端单测和浏览器验收；构建成功不等于业务验收通过。
- Compose `config --quiet` 只检查配置。只有镜像实际构建成功、容器健康且页面/API 可访问后，才能称为部署成功。
- 构建阶段需要下载 JDK/JRE、Node、Nginx、PostgreSQL 镜像及 Maven/npm 依赖。镜像标签不是不可变 digest，正式交付可进一步锁定已验证的 digest。

### Windows Docker 启动故障记录

该故障曾在本机出现，之后已观察到 Linux 引擎可用并完成上述部署验收。普通及管理员目录改名尝试均曾被拒绝，恢复原因未确认，尚未验证 Windows 或 Docker Desktop 再次重启后的启动可靠性。

如果 Docker Desktop 提示 `initializing Secrets Engine ... engine.sock ... The file cannot be accessed by the system`，这是 Docker 自身的 Windows 运行时问题，不是 OpsFlow 启动错误。不要因此删除项目数据库或直接恢复出厂设置。

先确认 WSL 2 已安装，完成系统提示的重启，再检查 Docker 是否能启动。若旧 socket 仍导致错误，需要在完全退出 Docker 后处理对应运行时目录，并按实际权限或 Windows 文件锁情况排查。不能把“配置校验通过”或“客户端已安装”当成引擎已经可用。

## English

### Completed local deployment

On 2026-10-18, frontend/backend images were built and all three containers became healthy. Port 8088, API proxying, administrator browser login, audit list/detail, language switching, and direct navigation/refresh on seven main pages passed. Private `.env.docker` and the original Windows development database were preserved.

Local evidence is saved under the Git-ignored `deploy/results/` directory. These are results of that run; use `compose ps` and health checks for current status. On this computer, use the startup commands below without regenerating credentials or rebuilding for everyday operation. GitHub Actions is not configured.

### Prerequisites and isolation

Docker Desktop must have a running Linux engine and Docker Compose. On Windows, complete WSL 2 setup first. `docker version` must show both Client and Server; the presence of the client alone does not establish readiness.

This setup creates a separate PostgreSQL 18.6 container and the `opsflow_postgres_data` named volume. It does not use or alter the existing Windows PostgreSQL database. Only Nginx is published, at `127.0.0.1:8088`; backend and database ports remain internal. Existing development ports 5173, 8080, and 5432 are untouched. This is a loopback-only demonstration, not an internet deployment.

Request path: browser → Nginx → Spring Boot → container PostgreSQL. Nginx serves the built React files, handles SPA route fallback, and proxies API requests. Vite is not the deployed web server.

### Initial startup

From the project root in PowerShell:

```powershell
.\deploy\Initialize-Docker.ps1
docker compose --env-file .env.docker -f compose.yaml config --quiet
docker compose --env-file .env.docker -f compose.yaml up -d --build --wait --wait-timeout 240
docker compose --env-file .env.docker -f compose.yaml ps
Invoke-RestMethod 'http://127.0.0.1:8088/actuator/health'
```

Initialization creates a private `.env.docker` with random database/admin passwords and a stable JWT signing key. It never overwrites an existing file. Optional parameters are `-AdminUsername`, `-AdminEmail`, and `-HttpPort`. Alternatively, fill in the root `.env.docker.example` manually and save it as `.env.docker`. Do not use blank secrets or commit the private file.

Open `http://127.0.0.1:8088/`. Read `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` locally from `.env.docker` to sign in; do not publish this file, its passwords, or expanded Compose configuration. This is a fresh database, so development accounts and records are not copied automatically.

Flyway creates the schema and roles. After initial administrator creation, set `BOOTSTRAP_ADMIN_ENABLED=false` in `.env.docker` and run:

```powershell
docker compose --env-file .env.docker -f compose.yaml up -d backend
```

Preserve the remaining credentials, especially the database password and JWT key. Changing generated configuration does not rotate an existing database account's password. Bootstrap does not overwrite an existing enabled administrator.

### Everyday startup, inspection, logs, shutdown, and updates

Start Docker Desktop and wait for the Linux engine, then run from the project root. Everyday startup needs no `--build`; rebuild after code changes.

```powershell
docker compose --env-file .env.docker -f compose.yaml up -d --wait --wait-timeout 240
docker compose --env-file .env.docker -f compose.yaml ps
docker compose --env-file .env.docker -f compose.yaml logs --tail 100 backend frontend
docker compose --env-file .env.docker -f compose.yaml stop
docker compose --env-file .env.docker -f compose.yaml up -d --build --wait --wait-timeout 240
```

The `stop` command shuts down services normally; the final command with `--build` updates the deployment after code changes. Stop services before normally quitting Docker Desktop or shutting down.

### Quitting Docker and data retention

**Normally quitting Docker Desktop, stopping containers, or rebooting does not automatically erase committed database content.** Users, services, incidents, changes, and audit records live in `opsflow_postgres_data`. Services are unavailable while Docker is stopped; starting the same Compose deployment reuses the data. Closing only the Desktop window may leave the engine running.

| Action | Data result |
| --- | --- |
| Normally quit Docker Desktop, `stop`, or reboot | Preserved |
| `down` without `-v` | Containers/networks removed; this named volume preserved and reused by the next `up` |
| Rebuild images or recreate containers using the same volume | Preserved |
| `down -v`, delete this volume, or run cleanup that removes it | Container database data deleted |
| Factory-reset or clear Docker data | May delete data; back up first |

Do not casually delete `opsflow_postgres_data` or prune volumes. Changing the Compose project/volume name or switching Docker engines can connect to another empty database without erasing the original volume. Persistence is not a backup; back up important records separately. Preserve `.env.docker`: it contains credentials and the JWT key, not the business database. PostgreSQL 18 mounts its volume at `/var/lib/postgresql`, not the older `/var/lib/postgresql/data` location.

See Docker's [volume documentation](https://docs.docker.com/engine/storage/volumes/) and [Compose down documentation](https://docs.docker.com/reference/cli/docker/compose/down/).

Health checks cover Nginx `/healthz`, backend `/actuator/health`, and database `pg_isready`. Dependencies wait for healthy services. `/healthz` only checks Nginx; use `/actuator/health` to check the backend/database path and then verify authenticated business operations.

### Build and verification boundaries

- Backend: repository Maven Wrapper and Java 21 build stage, then a non-root JRE 21 runtime. The Java health check requires neither curl nor credentials.
- Frontend: Node.js 22 build stage and Nginx runtime.
- Docker build contexts exclude private backend `application-local.*`, frontend environment files, caches, and test artifacts.
- Image builds skip business tests. Run backend `verify`, frontend unit tests, and browser acceptance separately before deployment.
- `compose config --quiet` validates configuration only. Deployment is successful only after actual image builds, healthy containers, and working page/API checks.
- Initial builds need image and Maven/npm downloads. Tags are not immutable digests; pin verified digests for stricter release reproducibility.

### Windows Docker startup error

This previously occurred on this computer; the Linux engine later became available and the deployment verification above passed. Both ordinary and administrator directory-renaming attempts had been denied. The recovery cause is unknown, and reliability across another Windows or Docker Desktop restart has not been verified.

`initializing Secrets Engine ... engine.sock ... The file cannot be accessed by the system` is a Docker Desktop runtime failure, not an OpsFlow application error. Do not erase project databases or factory-reset Docker as the first response.

Complete WSL 2 setup and any required Windows reboot, then recheck the engine. If a stale socket still blocks startup, inspect its runtime directory only after Docker has fully exited and handle Windows permissions/locks accordingly. A valid Compose file or an installed client does not mean the engine is running.
