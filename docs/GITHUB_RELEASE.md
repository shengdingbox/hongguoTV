# 发布打包服务器上的 APK

在当前机器的仓库根目录执行，即可将 Debian 打包服务器上最新成功的签名 APK 发布到 GitHub Release：

```bash
bash scripts/publish-tv-release.sh
```

脚本默认连接 `root@192.168.3.83:22`，读取 `hongguotv-android-build-data` 卷内的构建任务。无需进入 GitHub Actions，也不依赖发布 Runner 在线。

也可以使用 [GitHub Actions → 发布打包服务器最新版](https://github.com/superhuluwa/hongguoTV/actions/workflows/release.yml)，选择 `main`，点击 **Run workflow** 发布。

- `task_id` 留空：按构建任务 ID 从新到旧选择最新成功的构建，跳过更新源码、失败、取消和未完成的任务；所选任务还须具有配套的双包清单。
- `task_id` 填写任务 ID：发布指定的成功构建。
- `notes`：可选补充说明，会放在发布说明开头；不填时只有「本次更新」和「构建信息」两节。

工作流直接读取已完成的 APK，不重新编译或修改签名。当前 `main` 分支默认只启用 `standard`
变体，生成 Android 7.0（API 24）及以上安装包；标准包仍导出到原有路径，现有打包任务无需调整。
在已经准备好 NDK 27.1.12297006、CMake 3.22.1、Python 3.12+、curl 和 make 的环境中，为 Gradle 增加
`-PHONGGUOTV_BUILD_ANDROID5=true` 可同时生成标准版与 Android 5 兼容版；也可指定
`:app:assembleAndroid5Release` 只生成兼容版。这个开关不会改变标准版的最低系统和依赖。
发布任务默认读取标准构建的 `result.json` 和同目录的 `dual-apks.json`，把标准包与 Android 5
兼容包一起上传到同一个 Release。清单根节点的版本、版本代码、提交、签名、标准 APK 及校验值
必须与成功构建一致；`android5` 字段记录兼容包的 `artifactName`、`minSdk`（21 或 22）、
`apkSize` 和 `sha256`，其他共用信息继承根节点。若兼容包另外记录版本、提交、包名或签名，
也必须与标准包一致。归档阶段应确保两包来自同一提交、使用同一版本和证书。

脚本校验两个实际文件的大小与 SHA-256，拒绝缺包、混用不同构建记录或把标准包复制成兼容包。
例如任务 327 已包含 `dual-apks.json` 和两个签名 APK，可直接使用 `--task-id 327` 发布。
当前构建缺少兼容包时不会自动拼接旧版本，也不会重新编译；需要沿用单包发布时显式指定
`--single-apk`。

脚本创建的是正式 GitHub Release：先创建草稿，将所选构建的两个 APK 上传到该 Release 的 **Assets**，确认附件齐全后再正式发布并设为 Latest。GitHub 按附件创建时间倒序展示，因此脚本先上传 Android 5 兼容版、最后上传标准版，让作为通用包的 标准版显示在 Assets 顶部。标准版使用 `hongguotv-kotlin-<版本号>.apk`，例如 `hongguotv-kotlin-1.1.70.apk`；最低系统为 API 21/22 的兼容包使用 `hongguotv-kotlin-<版本号>-android5.apk`，与标准版附件分开命名；不生成或上传 `build-info.json`、`SHA256SUMS`。发布成功会输出 APK 的直接下载地址。APK 的大小和 SHA-256 必须与构建记录一致。Release 使用 `build-v<versionName>-<versionCode>` 标签，指向该 APK 构建记录中的完整源码提交，而非运行脚本或工作流时的最新源码。对应提交须已同步到 GitHub；已有标签必须指向同一提交，缺少标签时由 GitHub 在发布 Release 时自动生成，脚本不再提前单独创建 tag。

发布说明由脚本自动生成，不再是只取本次构建那一条提交信息。「本次更新」一节会以**上一个正式 Release** 的标签为基准，通过 GitHub 的 compare 接口取出两个版本之间新增的提交，直接逐条列出（`<短哈希> 提交标题`），不再额外写一行「对比上一版本 …」的说明；合并提交（多父提交或以 `Merge ` 开头的）不计入，最多列出 50 条，超出时注明剩余数量。没有提交可列时显示「没有新增的功能改动」。找不到可对比的上一版本时（例如首个版本，或两个版本没有共同祖先），退化为列出本次提交之前的历史。补发旧构建时会挑选仍是本次提交祖先的版本作为基准，不会误用更新的版本。`--dry-run` 会打印完整的发布说明预览。

重复运行会逐个检查两个 APK 的 Assets，补传缺失或不完整的附件，保留已上传完整的 APK；已有正式 Release 会保持发布状态，确认 APK 完整后才报告完成。之前只有 tag 时会为其创建 Release。脚本会输出草稿链接，上传失败时保留草稿，重新运行会通过 Release 列表查找并复用同名草稿，继续发布。遇到连接重置、超时或 GitHub 临时服务错误时，每个请求最多尝试 4 次，重试间隔依次为 2、4、8 秒。创建草稿或上传附件的响应中断后，会先查询 GitHub 上的状态，复用已完成的结果，并清理未完成的附件后补传。

## Shell 一键发布

本机需要 Bash、Python 3 和 OpenSSH，打包机需要 Python 3。脚本将当前仓库的 Python 发布程序通过 SSH 标准输入交给打包机执行，直接读取已有 APK；不重新构建，也不在服务器安装脚本。

GitHub 认证复用**执行命令的机器**上的 `GH_TOKEN` / `GITHUB_TOKEN`；未设置时，读取 Git 凭据管理器中保存的 GitHub 凭据，因此此时还需要 Git。凭据须具有目标仓库的 Release 写入权限（fine-grained Token 的 `Contents: Read and write`）。Token 通过加密 SSH 标准输入传输，不写入命令参数、仓库或远程文件。

当前维护环境自动复用已保存的 `debain-server-auth` 认证。其他机器使用 OpenSSH 已配置的密钥或交互式密码认证；首次连接须先通过 SSH 确认并保存服务器主机密钥。

可选参数示例：

```bash
# 模拟执行：实际连接 Debian、校验 APK 并查询 GitHub，不创建标签或发布
bash scripts/publish-tv-release.sh --dry-run

# 指定已有双包归档的构建任务与发布说明
bash scripts/publish-tv-release.sh --task-id 327 --notes '本次更新说明'

# 仅发布当前 APK，适用于没有配套兼容包的旧构建
bash scripts/publish-tv-release.sh --task-id 165 --single-apk

# 如果已在 Debian 打包机上，直接读取本机构建产物并使用该机的 GitHub 凭据
bash scripts/publish-tv-release.sh --local

# 查看参数
bash scripts/publish-tv-release.sh --help
```

`--dry-run` 会读取真实 APK，校验大小与 SHA-256，查询 GitHub 上的源码提交、标签和 Release；需要上传时还会检查 `uploads.github.com` 的连接。它只发送 GET / HEAD 请求，不创建标签、草稿或附件，也不会正式发布。此模式不实际上传，因此不验证上传权限或上传过程中是否断线。

`--repo owner/repository` 可指定目标仓库，默认 `superhuluwa/hongguoTV`；`--tasks-dir /path/to/tasks` 可覆盖打包机上的任务目录。

可通过 `HONGGUOTV_BUILD_HOST`、`HONGGUOTV_BUILD_SSH_PORT`、`HONGGUOTV_BUILD_TASKS_DIR` 覆盖默认服务器地址、端口和任务目录。只有默认地址和端口会自动启用已保存的 Debian 认证；如需指定自有 SSH 认证脚本，设置 `HONGGUOTV_SSH_HELPER`，该 Python 脚本应接收远程命令作为唯一参数，并原样转发标准输入和输出。

## GitHub 连接中断

脚本会显示当前处于检查提交、准备草稿、上传附件还是正式发布阶段；失败信息包含具体的 GitHub 主机和请求路径。`Connection reset by peer` 表示连接被对端或中间网络设备重置，程序会自动重试。

如果打包机需要代理才能访问 GitHub，在运行脚本前设置 `HTTPS_PROXY`，脚本会通过 SSH 将代理设置提供给打包机。代理地址须能从 Debian 打包机访问，例如下面的局域网 HTTP 代理（替换为实际地址）：

```bash
HTTPS_PROXY=http://192.168.3.10:7890 bash scripts/publish-tv-release.sh
```

`HTTP_PROXY`、`NO_PROXY` 及其小写形式同样会同步。设置为 `127.0.0.1` 时指的是 Debian 打包机本身。失败后可以重新执行相同命令，已有正式 Release 会复用，并补齐缺失的 APK。

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
