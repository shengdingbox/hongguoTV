#!/usr/bin/env bash
# Publish the latest completed APK on Debian; never rebuild the application.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
publisher="$script_dir/publish-tv-release.py"
build_host="${HONGGUOTV_BUILD_HOST:-root@192.168.3.83}"
build_port="${HONGGUOTV_BUILD_SSH_PORT:-22}"
tasks_dir="${HONGGUOTV_BUILD_TASKS_DIR:-/var/lib/docker/volumes/hongguotv-android-build-data/_data/tasks}"

usage() {
  cat <<'HELP'
用法：bash scripts/publish-tv-release.sh [--local] [发布参数]

默认连接 Debian 打包服务器，创建包含最新成功构建 APK 的正式 GitHub Release，并设为 Latest。
发布流程为创建 Release 草稿、上传附件、正式发布；所需 tag 由 GitHub 随发布自动创建。
GitHub 认证复用本机 GH_TOKEN / GITHUB_TOKEN 或已保存的 GitHub Git 凭据。

  --local           已在 Debian 打包机上时使用，直接读取本机构建产物
  --dry-run         模拟执行：读取并校验真实 APK 和 GitHub 连接，不写入 GitHub
  --task-id ID      指定成功的构建任务；不填则选择最新成功的 APK
  --repo OWNER/REPO 指定 GitHub 仓库；默认 superhuluwa/hongguoTV
  --notes TEXT      自定义发布说明
  --tasks-dir PATH  指定打包机上的构建任务目录
  -h, --help        显示此帮助

可选环境变量：
  HONGGUOTV_BUILD_HOST       SSH 地址，默认 root@192.168.3.83
  HONGGUOTV_BUILD_SSH_PORT   SSH 端口，默认 22
  HONGGUOTV_BUILD_TASKS_DIR  打包机上的构建任务目录
  HONGGUOTV_SSH_HELPER       可选的 SSH 认证 Python 脚本，接收远程命令作为唯一参数
  HTTPS_PROXY / HTTP_PROXY  可选的 HTTP 代理，同步提供给打包机访问 GitHub

当前机器若已安装 debain-server-auth 技能，默认服务器自动复用该技能的认证。
其他环境使用 OpenSSH 已配置的密钥或交互式密码认证，并核对已知主机密钥。
HELP
}

if [[ "${1:-}" == '-h' || "${1:-}" == '--help' ]]; then
  usage
  exit 0
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo '发布需要 Python 3，请先安装。' >&2
  exit 1
fi
if [[ ! -f "$publisher" ]]; then
  echo "缺少发布程序：$publisher，请保留同目录下的 publish-tv-release.py。" >&2
  exit 1
fi

if [[ "${1:-}" == '--local' ]]; then
  shift
  exec python3 "$publisher" --tasks-dir "$tasks_dir" "$@"
fi

ssh_helper="${HONGGUOTV_SSH_HELPER:-}"
if [[ -z "$ssh_helper" && "$build_host" == 'root@192.168.3.83' && "$build_port" == '22' ]]; then
  saved_helper="${CODEX_HOME:-$HOME/.codex}/skills/debain-server-auth/scripts/ssh.py"
  if [[ -f "$saved_helper" ]]; then
    ssh_helper="$saved_helper"
  fi
fi

if [[ -n "$ssh_helper" ]]; then
  if [[ ! -f "$ssh_helper" ]]; then
    echo "SSH 认证脚本不存在：$ssh_helper" >&2
    exit 1
  fi
  ssh_command=(python3 "$ssh_helper")
else
  ssh_command=(ssh -T -p "$build_port"
    -o StrictHostKeyChecking=yes
    -o ConnectTimeout=10
    -o ServerAliveInterval=15
    -o ServerAliveCountMax=2
    -- "$build_host")
fi

# The remote command is constant. Arguments, source and GitHub credentials travel
# only over encrypted SSH stdin, never in process arguments or remote files.
remote_bootstrap='
import json
import os
import sys

request = sys.stdin.read()
if not request:
    sys.exit(1)
payload = json.loads(request)
os.environ.update(payload["environment"])
os.environ["GH_TOKEN"] = payload["token"]
sys.argv = ["publish-tv-release.py", *payload["arguments"]]
exec(compile(payload["source"], "publish-tv-release.py", "exec"),
     {"__name__": "__main__", "__file__": "publish-tv-release.py"})
'
remote_command="python3 -u -c '$remote_bootstrap'"

printf '连接打包服务器：%s，读取：%s\n' "$build_host" "$tasks_dir"
python3 - "$publisher" "$tasks_dir" "$@" <<'PY' | "${ssh_command[@]}" "$remote_command"
import json
import os
from pathlib import Path
import runpy
import sys

publisher = runpy.run_path(sys.argv[1])
try:
    token = publisher["token_from_environment"]()
except (publisher["ReleaseError"], OSError) as error:
    print(f"发布失败：{error}", file=sys.stderr)
    sys.exit(1)

json.dump({
    "token": token,
    "source": Path(sys.argv[1]).read_text(encoding="utf-8"),
    "arguments": ["--tasks-dir", sys.argv[2], *sys.argv[3:]],
    "environment": {
        key: os.environ[key]
        for key in (
            "GITHUB_REPOSITORY", "RELEASE_TASK_ID", "RELEASE_NOTES",
            "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "https_proxy", "http_proxy", "no_proxy",
        )
        if key in os.environ
    },
}, sys.stdout, ensure_ascii=False)
PY
