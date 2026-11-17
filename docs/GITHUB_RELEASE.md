# 发布打包服务器上的 APK

在当前机器的仓库根目录执行，即可将 Debian 打包服务器上最新成功的签名 APK 发布到 GitHub Release：

```bash
bash scripts/publish-tv-release.sh
```

脚本默认连接 `root@192.168.3.83:22`，读取 `hongguotv-android-build-data` 卷内的构建任务。无需进入 GitHub Actions，也不依赖发布 Runner 在线。

也可以使用 [GitHub Actions → 发布打包服务器最新版](https://github.com/superhuluwa/hongguoTV/actions/workflows/release.yml)，选择 `main`，点击 **Run workflow** 发布。

- `task_id` 留空：按构建任务 ID 从新到旧选择最新成功的 APK，跳过更新源码、失败、取消和未完成的任务。
- `task_id` 填写任务 ID：发布指定的成功构建。
- `notes`：可选发布说明，默认使用 APK 对应提交的说明。

工作流直接读取已完成的 APK，不重新编译或修改签名。打包服务目前只生成 Android 8.0+ 包；只有实际构建了其他最低系统版本时，才会发布相应安装包。

发布标签为 `build-v<versionName>-<versionCode>`，指向该 APK 构建记录中的完整源码提交，而非运行工作流时的最新源码。对应提交须已同步到 GitHub。Release 附件包括 APK、`build-info.json` 和 `SHA256SUMS`。APK 的大小和 SHA-256 必须与构建记录一致，所有附件上传成功后才会将草稿发布为 Latest。

重复运行已发布的构建会返回原 Release；上传失败时保留草稿，重新运行可继续上传。遇到连接重置、超时或 GitHub 临时服务错误时，每个请求最多尝试 4 次，重试间隔依次为 2、4、8 秒。创建标签、草稿或上传附件的响应中断后，会先查询 GitHub 上的状态，复用已完成的结果，并清理未完成的附件后补传。

## Shell 一键发布

本机需要 Bash、Python 3 和 OpenSSH，打包机需要 Python 3。脚本将当前仓库的 Python 发布程序通过 SSH 标准输入交给打包机执行，直接读取已有 APK；不重新构建，也不在服务器安装脚本。

GitHub 认证复用**执行命令的机器**上的 `GH_TOKEN` / `GITHUB_TOKEN`；未设置时，读取 Git 凭据管理器中保存的 GitHub 凭据，因此此时还需要 Git。凭据须具有目标仓库的 Release 写入权限（fine-grained Token 的 `Contents: Read and write`）。Token 通过加密 SSH 标准输入传输，不写入命令参数、仓库或远程文件。

当前维护环境自动复用已保存的 `debain-server-auth` 认证。其他机器使用 OpenSSH 已配置的密钥或交互式密码认证；首次连接须先通过 SSH 确认并保存服务器主机密钥。

可选参数示例：

```bash
# 指定构建任务与发布说明
bash scripts/publish-tv-release.sh --task-id 165 --notes '本次更新说明'

# 如果已在 Debian 打包机上，直接读取本机构建产物并使用该机的 GitHub 凭据
bash scripts/publish-tv-release.sh --local

# 查看参数
bash scripts/publish-tv-release.sh --help
```

`--repo owner/repository` 可指定目标仓库，默认 `superhuluwa/hongguoTV`；`--tasks-dir /path/to/tasks` 可覆盖打包机上的任务目录。

可通过 `HONGGUOTV_BUILD_HOST`、`HONGGUOTV_BUILD_SSH_PORT`、`HONGGUOTV_BUILD_TASKS_DIR` 覆盖默认服务器地址、端口和任务目录。只有默认地址和端口会自动启用已保存的 Debian 认证；如需指定自有 SSH 认证脚本，设置 `HONGGUOTV_SSH_HELPER`，该 Python 脚本应接收远程命令作为唯一参数，并原样转发标准输入和输出。

## GitHub 连接中断

脚本会显示当前处于检查提交、准备草稿、上传附件还是正式发布阶段；失败信息包含具体的 GitHub 主机和请求路径。`Connection reset by peer` 表示连接被对端或中间网络设备重置，程序会自动重试。

如果打包机需要代理才能访问 GitHub，在运行脚本前设置 `HTTPS_PROXY`，脚本会通过 SSH 将代理设置提供给打包机。代理地址须能从 Debian 打包机访问，例如下面的局域网 HTTP 代理（替换为实际地址）：

```bash
HTTPS_PROXY=http://192.168.3.10:7890 bash scripts/publish-tv-release.sh
```

`HTTP_PROXY`、`NO_PROXY` 及其小写形式同样会同步。设置为 `127.0.0.1` 时指的是 Debian 打包机本身。失败后可以重新执行相同命令，已有正式 Release 不会重复发布。

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
