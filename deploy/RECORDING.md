# CI/CD recording checklist / 录制检查表

This documentation-only change is a small release to demonstrate the existing pipeline. It does not add an application feature or database migration. Leave its pull request unmerged until recording.

这次仅修改文档，用于演示现有发布链路，不新增业务功能或数据库迁移。请在正式录制时再合并对应 PR。

| Checkpoint / 检查点 | Evidence to capture / 录制证据 |
| --- | --- |
| Before merge / 合并前 | Existing app at `http://127.0.0.1:8088/`, three healthy containers, and the current SHA at `/release.json` |
| Review / 审核 | This PR's documentation diff and its successful CI checks |
| Release / 发布 | Merge into `main`; show the new push-triggered CI, image acceptance and `opsflow-release-<SHA>` artifact |
| Deployment / 部署 | A running `deploy/Start-CD.ps1` automatically downloads and verifies the release; capture `Deployed <SHA>` |
| Version / 版本 | Compare the served `/release.json` SHA with the new `main` commit and `.codex_work/cd/current.json` |
| Persistence / 持久化 | Log in and show an existing service, incident or change that remains available after the update |
| Recovery / 回退 | Show the pipeline's isolated wrong-version/rollback test, and identify it as an isolated test |

Start the local listener in a PowerShell 7.2+ terminal at the project root:

```powershell
pwsh -NoProfile -File ./deploy/Start-CD.ps1
```

Keep Docker and this terminal running. During the recorded deployment, let the agent update the application; do not replace that step with a manual image build. If waiting is shortened in the video, label it clearly.

保持 Docker 和监听终端运行。录制中由 agent 自动更新应用；如剪辑或加速等待过程，应明确标注。

Do not record `.env.docker`, passwords or GitHub tokens. Startup, trust boundaries and recovery limitations are documented in [CD.md](CD.md).

不要录入 `.env.docker`、密码或 GitHub token。启动要求、发布信任边界及回退限制见 [CD.md](CD.md)。
