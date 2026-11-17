# 发布打包服务器上的 APK

发布入口：[GitHub Actions → 发布打包服务器最新版](https://github.com/superhuluwa/hongguoTV/actions/workflows/release.yml)。选择 `main`，点击 **Run workflow** 即可发布打包服务器上最新成功的签名 APK。

- `task_id` 留空：按构建任务 ID 从新到旧选择最新成功的 APK，跳过更新源码、失败、取消和未完成的任务。
- `task_id` 填写任务 ID：发布指定的成功构建。
- `notes`：可选发布说明，默认使用 APK 对应提交的说明。

工作流直接读取已完成的 APK，不重新编译或修改签名。打包服务目前只生成 Android 8.0+ 包；只有实际构建了其他最低系统版本时，才会发布相应安装包。

发布标签为 `build-v<versionName>-<versionCode>`，指向该 APK 构建记录中的完整源码提交，而非运行工作流时的最新源码。对应提交须已同步到 GitHub。Release 附件包括 APK、`build-info.json` 和 `SHA256SUMS`。APK 的大小和 SHA-256 必须与构建记录一致，所有附件上传成功后才会将草稿发布为 Latest。

重复运行已发布的构建会返回原 Release；上传失败时保留草稿，重新运行可继续上传。

## 内网发布节点

GitHub 托管节点无法直接访问内网 `192.168.3.83`。工作流使用打包机上的专用 self-hosted runner：

| 项目 | 配置 |
| --- | --- |
| Runner 名称 | `hongguotv-release-debian` |
| Runner 标签 | `hongguotv-release` |
| 部署目录 | `/opt/hongguotv-github-release-runner` |
| 容器 | `hongguotv-github-release-runner` |
| 构建产物卷 | `hongguotv-android-build-data`，只读挂载至 `/build-data` |
| Runner 状态卷 | `hongguotv-github-release-runner-state` |

节点主动连接 GitHub，无需开放入站端口。容器不挂载 Docker socket、打包工作目录、发布签名或服务器 SSH 凭据。工作流只允许从 `main` 手动运行，使用 GitHub 自动提供的 `GITHUB_TOKEN`，权限为 `contents: write`，无需把个人 Token 配置进仓库。

首次部署：将 `deploy/github-release-runner/` 中的文件复制到部署目录，执行 `docker compose build` 和 `docker compose create`。在仓库 **Settings → Actions → Runners → New self-hosted runner** 获取注册 Token，并通过标准输入写入状态卷：

```bash
read -r -s -p 'Runner registration token: ' registration_token
printf '\n'
printf '%s' "$registration_token" | docker run --rm -i --user root \
  --entrypoint /bin/bash \
  -v hongguotv-github-release-runner-state:/home/runner \
  hongguotv-github-release-runner:latest -c \
  'umask 077; cat > /home/runner/.registration-token; chown runner /home/runner/.registration-token'
unset registration_token
docker compose up -d
```

基础镜像已固定 SHA-256 摘要；如 GHCR 下载较慢，可使用相同摘要的镜像源构建：

```bash
docker compose build --build-arg \
  RUNNER_IMAGE=ghcr.nju.edu.cn/actions/actions-runner@sha256:6d0b2d80d80f143f77dcb676e50920100bbb547e39e883454d57f45ab8c31300
```

首次注册完成后临时 Token 文件会删除，后续重启复用卷中的 Runner 注册状态。不要删除状态卷；如需重新注册，先在 GitHub 删除旧 Runner，再重新创建状态卷。

运维命令（在部署目录执行）：

```bash
docker compose logs --tail=100 runner
docker compose restart runner
```

## 直接执行发布脚本

也可以在能读取构建产物的环境中执行 Python 3 脚本。认证优先读取 `GH_TOKEN` / `GITHUB_TOKEN`，未设置时读取 Git 凭据管理器中保存的 GitHub 凭据。

```bash
python3 scripts/publish-tv-release.py \
  --tasks-dir /var/lib/docker/volumes/hongguotv-android-build-data/_data/tasks \
  --repo superhuluwa/hongguoTV
```

添加 `--task-id 165` 可指定构建任务，`--notes '本次更新说明'` 可自定义发布说明。脚本只需要 Python 标准库和 Git，不依赖 GitHub CLI 或 Android SDK。
