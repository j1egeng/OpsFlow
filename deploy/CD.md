# OpsFlow 本地持续部署 / Local continuous deployment

## 中文

### 发布链路

`PR → 审核合并 main → CI 后端/前端检查 → delivery 构建镜像 → 临时数据库验收 → GitHub Actions artifact → 本机 CD 拉取 → 更新应用容器 → 登录/审计/路由检查`

GitHub Actions 在 GitHub 的临时 Linux 主机上构建和验证；真正的部署目标是你启动 CD agent 的电脑。agent 主动通过 HTTPS 下载发布包，无需开放电脑端口、SSH 服务或配置 GitHub self-hosted runner。只有前端继续绑定 `127.0.0.1:8088`。镜像不会发布到公共 registry。

CI 的 `delivery` job 在前后端及部署安全检查通过后构建镜像并测试；PR 也会验证部署与回退，但只有 `main` push 才上传发布包。构建镜像标记完整 Git SHA，发布包包含前后端镜像、Compose 文件、校验清单和验收结果，不包含 `.env.docker`。本机校验当前 main、同一提交的成功 CI、原仓库、事件、artifact digest、包内 SHA-256、镜像 ID 和版本标签；部署已验证的镜像，不在本机重新构建。

### 启用

需要 PowerShell 7.2+ (`pwsh`)、Git、`tar`（Windows 自带 tar.exe）、可用的 Docker Linux 引擎、Docker Compose 2.24.4+。先按 [Docker 部署文档](README.md) 完成一次常规初始化，确认已有三个容器运行、管理员可登录。**保留原来的 `.env.docker`，不要重新生成。**

镜像导入时会重新导出并核对原始配置 blob 的 SHA-256。该配置包含运行设置及按顺序排列的文件系统层摘要。不同引擎的 image ID 可能分别指向 config、manifest 或 index，不能直接跨引擎比较；CI 镜像 ID 保留为来源记录，本机容器则核对本机已验证镜像 ID，回退仍使用上一版本机的确切 ID。

脚本需要 GitHub Actions artifact 的读取权限。默认在内存中使用 Git credential helper 已保存的 GitHub 登录。也可在当前进程环境中提供 `OPSFLOW_GITHUB_TOKEN`，使用仅限该仓库 `Actions: read` 和 `Contents: read` 的 fine-grained token。不要将 token 放进 `.env.docker`、命令参数、文档或截图；不要把 token 提交到 Git。

在项目根目录的新 PowerShell 7 窗口运行：

```powershell
# 只检查 GitHub 是否存在合格发布包，不部署。
pwsh -NoProfile -File ./deploy/Start-CD.ps1 -Once -CheckOnly

# 持续监听；每 60 秒检查一次，Ctrl+C 停止。
pwsh -NoProfile -File ./deploy/Start-CD.ps1
```

保持该窗口打开、电脑开机联网、Docker Linux 引擎运行。关闭窗口、退出 Docker 或休眠后不会自动部署；再次启动会检查最新 main。脚本不会自行注册开机服务。旧 CI 没有发布包时会等待；需将这些文件合并 main，并让新 CI 全部通过。发布包保留 7 天，过期后需重新运行对应 main CI。

幂等状态及执行结果保存在 Git 忽略的 `.codex_work/cd/`：

- `current.json`：最后成功部署的提交、镜像 ID、时间、健康检查和 URL。
- `github.json`：对应的 CI 链接和 artifact ID。
- 每次部署子目录的 `result.json`：成功、已回退或回退失败。

这些文件没有密码。不要在 agent 运行时手动执行另一套 Compose 更新。使用默认 project `opsflow` 保留既有 `opsflow_postgres_data`；自定义部署应使用独立 `-ProjectName`、`-EnvironmentFile` 和 `-StateDirectory`。

### 失败处理与边界

- PR、失败/取消/未完成 CI、其他仓库的运行、过期 artifact、旧 main 提交和校验失败均不会触发更新。下载后会再次确认 main 与 CI 状态。
- 发布前确认原版本可登录；更新后确认三个容器健康、前后端镜像 ID、页面版本、API、登录审计和 8 个 SPA 路由的 HTTP 回退。HTTP 路由检查不等于完整浏览器回归。
- 只更新 backend/frontend，不重建数据库容器、不删除卷、不修改 `.env.docker`。登录验收会新增正常的 `USER_LOGIN` 审计记录并更新登录时间。
- 新版不健康或验收失败时自动恢复上一版前后端的确切镜像 ID，再检查旧版可用性。首次 CD 也会记录原手工部署的镜像用于回退。
- 自动回退仅覆盖应用镜像，不撤销数据库写入或 schema。自动部署要求迁移文件列表与当前 Flyway 已应用列表相同；首次成功后也检查迁移文件哈希。新增/修改数据库迁移需单独审核、备份和人工发布。
- 回退本身失败会明确报错并保存结果，不会声称部署成功。持续模式失败后等待约 15 分钟再尝试；`-Once` 模式失败返回非零状态。
- 升级会有短暂服务中断；这是本地演示环境，不提供多副本零停机或异地灾备。

只下载并运行你信任的受审 main 代码及镜像。仓库分支保护和 PR review 仍是发布信任边界。

### Pre 演示

1. 提前启动 Docker 和 `Start-CD.ps1`，检查网络；准备上一轮成功演示录像。
2. 通过 PR 合并一个已审核的小改动，展示 CI 的 backend、frontend、delivery。
3. 等待 agent 自动输出 `Deployed <SHA>`，无需手动 `git pull` 或 `compose --build`。
4. 打开 `http://127.0.0.1:8088/release.json`，对比 GitHub 提交 SHA；登录并展示原有数据。
5. 展示 `.codex_work/cd/current.json` 的成功时间和健康检查。GitHub artifact 上传成功仅表示可交付，本机 `Deployed` 和实际检查通过才代表已部署。

### 本地验证发布包

以下步骤需要干净、已提交的工作树。脚本拒绝给未提交内容标记提交 SHA：

```powershell
pwsh -NoProfile -File ./deploy/tests/Test-CDGates.ps1
pwsh -NoProfile -File ./deploy/tests/Test-CDBundle.ps1
$revision = git rev-parse HEAD
pwsh -NoProfile -File ./deploy/Build-Release.ps1 -Revision $revision -OutputDirectory .codex_work/release-check
pwsh -NoProfile -File ./deploy/Test-Release.ps1 -BundleDirectory .codex_work/release-check
pwsh -NoProfile -File ./deploy/tests/Test-CDDeployment.ps1 -BundleDirectory .codex_work/release-check
```

每次使用新的输出目录。验收使用随机测试 project、随机 loopback 端口、随机凭据及 tmpfs 数据库；结束移除测试容器和网络，不执行删除数据卷的操作。

工作流及脚本存在不代表已在 GitHub 或本机成功部署。实际测试证据单独记录在 `deploy/results/`，如有引擎或权限阻碍必须据实说明。

## English

The pipeline is: reviewed main push → backend/frontend CI → build commit-tagged Docker images → isolated acceptance → Actions artifact → local pull agent → application update → health/auth/audit/route checks.

GitHub-hosted runners build and validate; your computer is the persistent deployment target. No inbound access, self-hosted runner, public registry, or uploaded application credentials are required. The agent downloads the exact tested images and checks main SHA, trusted successful push CI, artifact and file hashes, image IDs and revision labels. Fork PRs never produce deployable bundles.

Requirements: PowerShell 7.2+, Git, `tar` (included with Windows), Docker Linux engine, Compose 2.24.4+, and an existing healthy manual deployment with its original private `.env.docker`. Configure a local Git credential helper or a process-only `OPSFLOW_GITHUB_TOKEN` with repository `Actions: read` and `Contents: read`; never commit or display tokens. PRs run container acceptance and rollback tests but never upload deployable artifacts.

Imported images are re-exported and verified against the exact original config-blob SHA-256, including runtime configuration and ordered filesystem layer digests. Engine IDs may identify configs, manifests or indexes, so the CI ID is retained as provenance while deployed containers are compared to verified local IDs. Rollback still restores exact previous local IDs.

Run `pwsh -NoProfile -File ./deploy/Start-CD.ps1 -Once -CheckOnly` to inspect readiness, then `pwsh -NoProfile -File ./deploy/Start-CD.ps1` to watch. The process polls every 60 seconds. Keep the terminal, computer and Docker running; Ctrl+C stops the agent. It does not install a startup service. Old CI runs without release artifacts are ignored. Merge the new workflow first; artifacts expire after seven days and require a CI rerun.

Only backend/frontend are updated; the database container, named volume and private configuration remain intact. Smoke logins add normal login audit records. Failed releases restore exact previous application image IDs and verify the old application. This does not undo database writes or schema changes. Schema migration additions/changes block automatic releases and need a separate reviewed backup/manual release plan. Short downtime is expected. Failed rollback is explicitly recorded.

Ignored `.codex_work/cd/current.json` records successful deployment SHA/time/image IDs/checks; `github.json` records the source run; attempt directories contain success/rollback results. Use `http://127.0.0.1:8088/release.json` to show the served commit in a presentation. Artifact availability alone does not establish a deployed local release. Local route HTTP checks are not a complete browser regression suite.

To verify locally, commit a clean source tree, run `deploy/tests/Test-CDGates.ps1`, build with `Build-Release.ps1 -Revision <HEAD> -OutputDirectory <new-directory>`, then run `Test-Release.ps1 -BundleDirectory <directory>`. Tests use a random project/port and a disposable tmpfs database, leaving the business database untouched. Evidence and any blocked checks belong in Git-ignored `deploy/results/`.

References: [GitHub artifacts](https://docs.github.com/en/actions/tutorials/store-and-share-data), [artifact REST API](https://docs.github.com/en/rest/actions/artifacts), [Compose merge rules](https://docs.docker.com/reference/compose-file/merge/).
