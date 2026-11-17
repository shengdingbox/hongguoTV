#!/usr/bin/env python3
"""Publish a completed APK from the Debian build server without rebuilding it."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request


API_URL = "https://api.github.com"
DEFAULT_TASKS = "/build-data/tasks"
DEFAULT_REPOSITORY = "superhuluwa/hongguoTV"


class ReleaseError(Exception):
    pass


class GitHub:
    def __init__(self, repository, token):
        self.repository = repository
        self.token = token
        self.root = f"/repos/{repository}"

    def request(self, method, path, data=None, content_type="application/json", missing_ok=False):
        url = path if path.startswith("https://") else API_URL + self.root + path
        if urllib.parse.urlsplit(url).hostname not in ("api.github.com", "uploads.github.com"):
            raise ReleaseError("GitHub API 返回了无效的上传地址。")
        if data is not None and content_type == "application/json":
            data = json.dumps(data).encode("utf-8")
        request = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "hongguotv-release",
            "Content-Type": content_type,
        })
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            if missing_ok and error.code == 404:
                return None
            try:
                message = json.load(error).get("message", "请求失败")
            except (ValueError, AttributeError):
                message = "请求失败"
            raise ReleaseError(f"GitHub API {error.code}: {message}") from None
        except urllib.error.URLError as error:
            raise ReleaseError(f"连接 GitHub 失败：{error.reason}") from None


def token_from_environment():
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        return token
    result = subprocess.run(
        ["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
        text=True, capture_output=True,
        env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
    )
    fields = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    if result.returncode == 0 and fields.get("password"):
        return fields["password"]
    raise ReleaseError("请设置 GH_TOKEN / GITHUB_TOKEN，或先保存 GitHub 的 Git 凭据。")


def read_build(tasks_dir, task_id):
    if not tasks_dir.is_dir():
        raise ReleaseError(f"构建任务目录不存在：{tasks_dir}")
    if task_id:
        if not re.fullmatch(r"[1-9][0-9]*", task_id):
            raise ReleaseError("构建任务 ID 必须为正整数。")
        candidates = [tasks_dir / task_id]
    else:
        candidates = sorted(
            (path for path in tasks_dir.iterdir() if path.is_dir() and path.name.isdecimal()),
            key=lambda path: int(path.name), reverse=True,
        )
    for directory in candidates:
        result_file = directory / "result.json"
        if not result_file.is_file():
            if task_id:
                raise ReleaseError(f"构建任务 {task_id} 尚未完成。")
            continue
        try:
            result = json.loads(result_file.read_text(encoding="utf-8"))
        except (ValueError, OSError) as error:
            raise ReleaseError(f"无法读取构建任务 {directory.name}：{error}") from None
        if result.get("status") == "SUCCESS" and result.get("artifactName"):
            return directory, result
        if task_id:
            raise ReleaseError(f"构建任务 {task_id} 没有成功生成 APK。")
    raise ReleaseError("没有找到成功完成的 APK 构建任务。")


def digest(path):
    sha256 = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha256.update(chunk)
    return sha256.hexdigest()


def prepare_assets(directory, result, output):
    name = result.get("artifactName", "")
    version = result.get("versionName", "")
    code = result.get("versionCode")
    commit = result.get("commit", "")
    if not isinstance(name, str) or Path(name).name != name or not name.endswith(".apk"):
        raise ReleaseError("构建记录中的 APK 文件名无效。")
    if not isinstance(version, str) or not re.fullmatch(r"[0-9][0-9A-Za-z.+_-]*", version):
        raise ReleaseError("构建记录中的版本名称无效。")
    if type(code) is not int or code <= 0:
        raise ReleaseError("构建记录中的版本代码无效。")
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ReleaseError("构建记录中缺少完整的源码提交哈希。")
    if result.get("packageName") != "com.huluwa.nativeapp":
        raise ReleaseError("构建产物的包名不属于红果 TV。")
    minimum_sdk = result.get("minSdk")
    if type(minimum_sdk) is not int or minimum_sdk < 21:
        raise ReleaseError("构建记录中的最低 Android SDK 无效。")
    suffix = {21: "android5", 26: "android8"}.get(minimum_sdk, f"android-api{minimum_sdk}")
    apk_name = f"hongguotv-kotlin-{version}-{suffix}.apk"
    source = directory / name
    if source.is_symlink() or not source.is_file():
        raise ReleaseError(f"构建任务 {directory.name} 的 APK 已不存在。")
    apk = output / apk_name
    shutil.copyfile(source, apk)
    if apk.stat().st_size != result.get("apkSize") or digest(apk) != result.get("sha256"):
        raise ReleaseError("APK 大小或 SHA-256 与成功构建记录不符，已停止发布。")
    metadata = {
        key: result[key] for key in (
            "versionName", "versionCode", "commit", "commitSubject", "packageName", "minSdk",
            "apkSize", "sha256", "signatureSha256", "startedAt", "completedAt",
        ) if key in result
    }
    metadata.update(taskId=int(directory.name), artifactName=apk_name, sourceArtifactName=name)
    info = output / "build-info.json"
    info.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    sums = output / "SHA256SUMS"
    sums.write_text("".join(f"{digest(path)}  {path.name}\n" for path in (apk, info)), encoding="utf-8")
    return [apk, info, sums], metadata


def ensure_tag(github, tag, commit):
    reference = "/git/ref/tags/" + urllib.parse.quote(tag, safe="")
    existing = github.request("GET", reference, missing_ok=True)
    if existing is None:
        github.request("POST", "/git/refs", {"ref": f"refs/tags/{tag}", "sha": commit})
        return
    target = existing["object"]
    for _ in range(10):
        if target["type"] != "tag":
            break
        target = github.request("GET", "/git/tags/" + target["sha"])["object"]
    if target["type"] != "commit" or target["sha"] != commit:
        raise ReleaseError(f"标签 {tag} 已指向其他提交，已停止发布。")


def release_notes(metadata, notes):
    details = [
        f"红果 TV {metadata['versionName']}",
        "",
        notes.strip() or metadata.get("commitSubject", "打包服务器签名构建。"),
        "",
        f"- 构建任务：{metadata['taskId']}",
        f"- 版本代码：{metadata['versionCode']}",
        f"- 源码提交：{metadata['commit']}",
        f"- 构建完成时间：{metadata.get('completedAt', '未记录')}",
        f"- 最低系统：Android API {metadata['minSdk']}",
        f"- APK SHA-256：`{metadata['sha256']}`",
        "",
        "APK 来自打包服务器已成功完成的签名构建。附件包含构建信息和 SHA256SUMS 校验文件。",
    ]
    return "\n".join(details)


def publish(github, assets, metadata, notes):
    tag = f"build-v{metadata['versionName']}-{metadata['versionCode']}"
    # Fail before creating anything when the build commit has not reached GitHub.
    if github.request("GET", "/commits/" + metadata["commit"], missing_ok=True) is None:
        raise ReleaseError("APK 对应的源码提交尚未同步到 GitHub，请先推送该提交。")
    ensure_tag(github, tag, metadata["commit"])
    release = github.request("GET", "/releases/tags/" + urllib.parse.quote(tag, safe=""), missing_ok=True)
    if release and not release["draft"]:
        print(f"该构建已发布：{release['html_url']}")
        return tag, release["html_url"]
    fields = {
        "tag_name": tag,
        "target_commitish": metadata["commit"],
        "name": f"红果 TV {metadata['versionName']}",
        "body": release_notes(metadata, notes),
        "draft": True,
        "prerelease": False,
    }
    if release:
        release = github.request("PATCH", f"/releases/{release['id']}", fields)
    else:
        release = github.request("POST", "/releases", fields)
    old_assets = {
        item["name"]: item for item in github.request("GET", f"/releases/{release['id']}/assets?per_page=100")
    }
    upload_url = release["upload_url"].split("{", 1)[0]
    for path in assets:
        if path.name in old_assets:
            github.request("DELETE", f"/releases/assets/{old_assets[path.name]['id']}")
        url = upload_url + "?" + urllib.parse.urlencode({"name": path.name})
        uploaded = github.request("POST", url, path.read_bytes(), "application/octet-stream")
        if uploaded.get("state") != "uploaded" or uploaded.get("size") != path.stat().st_size:
            raise ReleaseError(f"附件 {path.name} 上传未完成，Release 已保留为草稿。")
        server_digest = uploaded.get("digest")
        if server_digest and server_digest != "sha256:" + digest(path):
            raise ReleaseError(f"附件 {path.name} 上传校验失败，Release 已保留为草稿。")
        print(f"已上传：{path.name}", flush=True)
    published = github.request("PATCH", f"/releases/{release['id']}", {"draft": False, "make_latest": "true"})
    return tag, published["html_url"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tasks-dir", type=Path, default=Path(os.environ.get("HONGGUOTV_BUILD_TASKS_DIR", DEFAULT_TASKS)))
    parser.add_argument("--task-id", default=os.environ.get("RELEASE_TASK_ID", ""))
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", DEFAULT_REPOSITORY))
    parser.add_argument("--notes", default=os.environ.get("RELEASE_NOTES", ""))
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        raise ReleaseError("GitHub 仓库名称格式应为 owner/repository。")
    directory, result = read_build(args.tasks_dir, args.task_id.strip())
    with tempfile.TemporaryDirectory(prefix="hongguotv-release-") as temporary:
        assets, metadata = prepare_assets(directory, result, Path(temporary))
        print(f"发布构建任务 {metadata['taskId']}：{metadata['versionName']} ({metadata['versionCode']})，源码 {metadata['commit']}", flush=True)
        github = GitHub(args.repo, token_from_environment())
        tag, url = publish(github, assets, metadata, args.notes)
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
                output.write(f"tag={tag}\nurl={url}\n")
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
                summary.write(f"[红果 TV {metadata['versionName']}]({url})\n\n")
                summary.write(f"构建任务：{metadata['taskId']} · 版本代码：{metadata['versionCode']}\n\n")
                summary.write(f"源码提交：`{metadata['commit']}`\n")
        print(f"发布完成：{url}")


if __name__ == "__main__":
    try:
        main()
    except (ReleaseError, OSError, ValueError, KeyError) as error:
        print(f"发布失败：{error}", file=sys.stderr)
        sys.exit(1)
