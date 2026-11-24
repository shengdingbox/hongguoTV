#!/usr/bin/env python3
"""发布打包服务器已完成的标准版与 Android 5 兼容版 APK，不重新编译应用。"""

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


API_URL = "https://api.github.com"
DEFAULT_TASKS = "/build-data/tasks"
DEFAULT_REPOSITORY = "superhuluwa/hongguoTV"
# 发布说明里最多列出的提交条数，避免一次跨越多个版本时正文过长。
MAX_LISTED_COMMITS = 50


class ReleaseError(Exception):
    pass


class GitHub:
    def __init__(self, repository, token):
        self.repository = repository
        self.token = token
        self.root = f"/repos/{repository}"

    def request(self, method, path, data=None, content_type="application/json", missing_ok=False,
                recover=None):
        url = path if path.startswith("https://") else API_URL + self.root + path
        endpoint = urllib.parse.urlsplit(url)
        if endpoint.hostname not in ("api.github.com", "uploads.github.com"):
            raise ReleaseError("GitHub API 返回了无效的上传地址。")
        if data is not None and content_type == "application/json":
            data = json.dumps(data).encode("utf-8")
        operation = f"{method} {endpoint.hostname}{endpoint.path}"
        attempts = 4
        for attempt in range(1, attempts + 1):
            request = urllib.request.Request(url, data=data, method=method, headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "*/*" if method == "HEAD" else "application/vnd.github+json",
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
                except (ValueError, AttributeError, OSError, http.client.HTTPException):
                    message = "请求失败"
                reason = f"HTTP {error.code}: {message}"
                # A lost POST response may appear as an already-existing resource.
                if error.code not in (408, 429, 500, 502, 503, 504) and not (
                        error.code == 422 and recover is not None):
                    raise ReleaseError(f"GitHub {operation} 失败：{reason}") from None
            except urllib.error.URLError as error:
                reason = str(error.reason)
            except (OSError, http.client.HTTPException, json.JSONDecodeError) as error:
                reason = str(error)

            # Never blindly repeat a POST: GitHub may have accepted it before the
            # connection broke. Its caller must reconcile the server-side state.
            if recover is not None:
                recovered = recover()
                if recovered is not None:
                    print(f"连接中断后已确认请求完成：{operation}", flush=True)
                    return recovered
            if attempt == attempts or (method == "POST" and recover is None):
                raise ReleaseError(
                    f"GitHub {operation} 失败（尝试 {attempt} 次）：{reason}。"
                    "请检查网络或配置 HTTPS_PROXY 后重新执行。"
                ) from None
            delay = 2 ** attempt
            print(f"GitHub {operation} 连接失败：{reason}；"
                  f"{delay} 秒后重试（{attempt + 1}/{attempts}）。",
                  file=sys.stderr, flush=True)
            time.sleep(delay)


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


def prepare_assets(directory, result, output, single_apk=False):
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
    metadata = {
        key: result[key] for key in (
            "versionName", "versionCode", "commit", "commitSubject", "packageName", "minSdk",
            "apkSize", "sha256", "signatureSha256", "startedAt", "completedAt",
        ) if key in result
    }
    metadata.update(taskId=int(directory.name))
    records = [result]
    if not single_apk:
        if minimum_sdk <= 22:
            raise ReleaseError("双包发布需要选择标准版构建任务；仅发布当前包请使用 --single-apk。")
        manifest_file = directory / "dual-apks.json"
        if manifest_file.is_symlink() or not manifest_file.is_file():
            raise ReleaseError(
                f"构建任务 {directory.name} 缺少 dual-apks.json，无法确认配套的 Android 5 兼容包。"
                "请先完成同版本双包归档；仅发布当前 APK 可使用 --single-apk。"
            )
        try:
            manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        except (ValueError, OSError) as error:
            raise ReleaseError(f"无法读取双包清单：{error}") from None
        if not isinstance(manifest, dict) or manifest.get("status") != "SUCCESS":
            raise ReleaseError("双包清单没有记录成功构建，已停止发布。")
        # 清单根节点记录两包共用的构建身份，禁止拼接其他版本或其他提交的兼容包。
        for key in ("artifactName", "versionName", "versionCode", "commit", "minSdk", "apkSize", "sha256", "signatureSha256"):
            if manifest.get(key) != result.get(key):
                raise ReleaseError(f"双包清单的 {key} 与所选成功构建不一致，已停止发布。")
        signature = result.get("signatureSha256")
        if not isinstance(signature, str) or not re.fullmatch(r"[0-9a-f]{64}", signature):
            raise ReleaseError("双包构建缺少有效的签名证书校验值，已停止发布。")
        if manifest.get("packageName", result["packageName"]) != result["packageName"]:
            raise ReleaseError("双包清单的包名与成功构建不一致，已停止发布。")
        android5 = manifest.get("android5")
        if not isinstance(android5, dict) or type(android5.get("minSdk")) is not int or android5["minSdk"] not in (21, 22):
            raise ReleaseError("双包清单缺少有效的 Android 5 兼容包记录，已停止发布。")
        for key in ("versionName", "versionCode", "commit", "packageName", "signatureSha256"):
            if android5.get(key, result.get(key)) != result.get(key):
                raise ReleaseError(f"Android 5 兼容包的 {key} 与标准包不一致，已停止发布。")
        if android5.get("status", "SUCCESS") != "SUCCESS":
            raise ReleaseError("Android 5 兼容包尚未成功完成，已停止发布。")
        if android5.get("artifactName") == name or android5.get("sha256") == result.get("sha256"):
            raise ReleaseError("Android 5 兼容包不能使用标准 APK 的副本，已停止发布。")
        records.append({**metadata, **android5})

    assets = []
    artifacts = []
    for record in records:
        source_name = record.get("artifactName", "")
        size = record.get("apkSize")
        checksum = record.get("sha256")
        if not isinstance(source_name, str) or Path(source_name).name != source_name or not source_name.endswith(".apk"):
            raise ReleaseError("构建记录中的 APK 文件名无效。")
        if type(size) is not int or size <= 0 or not isinstance(checksum, str) or not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise ReleaseError("构建记录中的 APK 大小或 SHA-256 无效。")
        is_legacy = record["minSdk"] <= 22
        label = "Android 5 兼容版" if is_legacy else "标准版"
        apk_name = f"hongguotv-kotlin-{version}{'-android5' if is_legacy else ''}.apk"
        source = directory / source_name
        if source.is_symlink() or not source.is_file():
            raise ReleaseError(f"构建任务 {directory.name} 的{label} APK 已不存在：{source_name}")
        apk = output / apk_name
        shutil.copyfile(source, apk)
        if apk.stat().st_size != size or digest(apk) != checksum:
            raise ReleaseError(f"{label} APK 大小或 SHA-256 与构建记录不符，已停止发布。")
        assets.append(apk)
        artifacts.append({
            "label": label, "artifactName": apk_name, "sourceArtifactName": source_name,
            "minSdk": record["minSdk"], "apkSize": size, "sha256": checksum,
            "signatureSha256": record.get("signatureSha256"),
        })
    metadata.update(
        artifactName=artifacts[0]["artifactName"], sourceArtifactName=name, artifacts=artifacts,
    )
    # GitHub Release Assets 按较早创建的附件显示在前。先上传标准版、最后上传兼容版，
    # 让通用标准包显示在 Assets 顶部；发布说明也保持标准版在前的顺序。
    return sorted(assets, key=lambda path: path.name.endswith("-android5.apk")), metadata


def tag_commit(github, tag):
    """返回 (标签是否存在, 标签指向的提交哈希或 None)，附注标签会逐层剥开到提交。"""
    reference = "/git/ref/tags/" + urllib.parse.quote(tag, safe="")
    existing = github.request("GET", reference, missing_ok=True)
    if existing is None:
        return False, None
    target = existing["object"]
    for _ in range(10):
        if target["type"] != "tag":
            break
        target = github.request("GET", "/git/tags/" + target["sha"])["object"]
    return True, target["sha"] if target["type"] == "commit" else None


def check_tag(github, tag, commit):
    exists, existing_commit = tag_commit(github, tag)
    if not exists:
        # GitHub creates a missing tag from target_commitish when publishing the
        # Release. Do not leave a standalone tag if creating the draft fails.
        return
    if existing_commit is None:
        raise ReleaseError(f"标签 {tag} 不是提交标签，已停止发布。")
    if existing_commit != commit:
        raise ReleaseError(f"标签 {tag} 已指向其他提交，已停止发布。")


def find_release(github, tag):
    release = github.request(
        "GET", "/releases/tags/" + urllib.parse.quote(tag, safe=""), missing_ok=True,
    )
    if release is not None:
        return release
    # The tag endpoint only returns published Releases. Authenticated listing
    # also includes drafts, which must be reused after an interrupted publish.
    page = 1
    while True:
        releases = github.request("GET", f"/releases?per_page=100&page={page}")
        for release in releases:
            if release["tag_name"] == tag:
                return release
        if len(releases) < 100:
            return None
        page += 1


def list_releases(github):
    releases = []
    page = 1
    while True:
        batch = github.request("GET", f"/releases?per_page=100&page={page}")
        releases.extend(batch)
        if len(batch) < 100:
            return releases
        page += 1


def is_merge_commit(commit):
    """合并提交一律不计入更新列表。"""
    if len((commit.get("parents") or [])) > 1:
        return True
    message = (commit.get("commit") or {}).get("message", "")
    return message.startswith("Merge ")


def commit_changes(github, tag, commit):
    """列出上一个正式版本到本次构建提交之间的新增提交。

    找不到可对比的版本时，退化为列出该提交之前的提交历史。
    """
    base_commit = None
    commits = []
    comparison = None
    # /releases 按创建时间倒序返回，取第一个仍是本次提交祖先的正式 Release，
    # 这样补发旧构建时不会拿比它更新的版本当作对比基准。
    for release in list_releases(github):
        if release.get("draft") or release.get("prerelease"):
            continue
        if release.get("tag_name") == tag:
            continue
        exists, candidate = tag_commit(github, release["tag_name"])
        if not exists or not candidate or candidate == commit:
            continue
        span = urllib.parse.quote(f"{candidate}...{commit}", safe="")
        compared = github.request("GET", f"/compare/{span}?per_page=100", missing_ok=True)
        # status 为 ahead 表示上一版本的提交是本次提交的祖先，可直接对比。
        if not compared or compared.get("status") != "ahead":
            continue
        base_commit, comparison = candidate, compared
        commits = compared.get("commits") or []
        break

    if base_commit is None:
        # 没有可对比的版本，退化为列出该提交之前的提交历史。
        page = 1
        while page <= 10:
            batch = github.request(
                "GET", f"/commits?sha={urllib.parse.quote(commit)}&per_page=100&page={page}")
            commits.extend(batch)
            if len(batch) < 100 or sum(
                    1 for item in commits if not is_merge_commit(item)) >= MAX_LISTED_COMMITS:
                break
            page += 1
        return commits

    # compare 单页最多 100 条，按页补齐直到够用。
    span = urllib.parse.quote(f"{base_commit}...{commit}", safe="")
    page = 2
    while page <= 10:
        if len(commits) >= (comparison.get("total_commits") or 0):
            break
        if sum(1 for item in commits if not is_merge_commit(item)) >= MAX_LISTED_COMMITS:
            break
        batch = github.request("GET", f"/compare/{span}?per_page=100&page={page}") \
            .get("commits") or []
        if not batch:
            break
        commits.extend(batch)
        page += 1
    return commits


def format_changes(commits):
    """把提交列表渲染为 Release 正文片段，合并记录不列出。"""
    # 去掉合并记录并按提交时间倒序，最新的改动排在最前。
    seen = set()
    changes = []
    for commit in reversed(commits):
        sha = commit.get("sha") or ""
        if sha in seen or is_merge_commit(commit):
            continue
        seen.add(sha)
        changes.append(commit)
    lines = ["## 本次更新", ""]
    if not changes:
        lines.append("- 没有新增的功能改动。")
        return lines
    for commit in changes[:MAX_LISTED_COMMITS]:
        sha = (commit.get("sha") or "")[:7]
        subject = (commit.get("commit") or {}).get("message", "").splitlines()
        title = subject[0].strip() if subject else ""
        lines.append(f"- `{sha}` {title}")
    hidden = len(changes) - MAX_LISTED_COMMITS
    if hidden > 0:
        lines.append("")
        lines.append(f"另有 {hidden} 条提交未列出。")
    return lines


def release_notes(metadata, notes, commits):
    details = [f"红果 TV {metadata['versionName']}", ""]
    custom = notes.strip()
    if custom:
        details.extend([custom, ""])
    if commits is not None:
        details.extend(format_changes(commits))
        details.append("")
    details.extend([
        "## 构建信息",
        "",
        f"- 构建任务：{metadata['taskId']}",
        f"- 版本代码：{metadata['versionCode']}",
        f"- 源码提交：{metadata['commit']}（{metadata.get('commitSubject', '未记录提交说明')}）",
        f"- 构建完成时间：{metadata.get('completedAt', '未记录')}",
    ])
    for artifact in metadata.get("artifacts") or [metadata]:
        label = artifact.get("label", "APK")
        details.extend([
            f"- {label}：`{artifact['artifactName']}`，最低 Android API {artifact['minSdk']}",
            f"- {label} SHA-256：`{artifact['sha256']}`",
        ])
    details.extend([
        "",
        "请在本 Release 的 Assets 中下载适合设备系统的 APK。",
        "APK 来自打包服务器已成功完成的签名构建。",
    ])
    return "\n".join(details)


def list_release_assets(github, release_id):
    assets = []
    page = 1
    while True:
        batch = github.request("GET", f"/releases/{release_id}/assets?per_page=100&page={page}")
        assets.extend(batch)
        if len(batch) < 100:
            return assets
        page += 1


def asset_matches(asset, path):
    if not asset or asset.get("state") != "uploaded" or asset.get("size") != path.stat().st_size:
        return False
    server_digest = asset.get("digest")
    return not server_digest or server_digest == "sha256:" + digest(path)


def recover_upload(github, release_id, path):
    """Reuse a completed upload or remove its incomplete server-side placeholder."""
    for asset in list_release_assets(github, release_id):
        if asset["name"] != path.name:
            continue
        if asset_matches(asset, path):
            return asset
        github.request("DELETE", f"/releases/assets/{asset['id']}", missing_ok=True)
    return None


def publish(github, assets, metadata, notes, dry_run=False):
    tag = f"build-v{metadata['versionName']}-{metadata['versionCode']}"
    required = {item["artifactName"] for item in metadata.get("artifacts") or [metadata]}
    if not required.issubset({path.name for path in assets}):
        raise ReleaseError("发布附件中缺少本次构建的配套 APK，已停止发布。")
    print("检查 GitHub 源码提交与发布标签……", flush=True)
    # Fail before creating anything when the build commit has not reached GitHub.
    if github.request("GET", "/commits/" + metadata["commit"], missing_ok=True) is None:
        raise ReleaseError("APK 对应的源码提交尚未同步到 GitHub，请先推送该提交。")
    check_tag(github, tag, metadata["commit"])
    release = find_release(github, tag)
    # 说明正文列出上一个正式版本以来的新增提交（不含合并记录）。
    if release is None or release["draft"]:
        print("读取上一版本以来的提交记录……", flush=True)
        commits = commit_changes(github, tag, metadata["commit"])
    else:
        commits = None
    fields = {
        "tag_name": tag,
        "target_commitish": metadata["commit"],
        "name": f"红果 TV {metadata['versionName']}",
        "body": release_notes(metadata, notes, commits),
        "draft": True,
        "prerelease": False,
    }
    if dry_run:
        old_assets = {
            item["name"]: item for item in list_release_assets(github, release["id"])
        } if release else {}
        if commits is not None:
            print("--- 发布说明预览 ---", flush=True)
            print(fields["body"], flush=True)
            print("--- 发布说明预览结束 ---", flush=True)
        print("检查 GitHub 附件上传主机连接……", flush=True)
        github.request("HEAD", "https://uploads.github.com/", missing_ok=True)
        if release and not release["draft"]:
            print(f"模拟补齐已发布 Release 的 Assets：{release['html_url']}", flush=True)
        else:
            print(f"模拟{'更新' if release else '创建'} Release 草稿：{tag}", flush=True)
        for path in assets:
            if asset_matches(old_assets.get(path.name), path):
                print(f"Assets 已有完整附件，保留：{path.name}", flush=True)
            else:
                print(f"模拟上传到 Assets：{path.name}（{path.stat().st_size} 字节，"
                      f"SHA-256：{digest(path)}）", flush=True)
        if not release or release["draft"]:
            print(f"模拟正式发布 Release 并设为 Latest（缺少标签 {tag} 时由 GitHub 自动创建）。", flush=True)
        return tag, None
    if release:
        if release["draft"]:
            release = github.request("PATCH", f"/releases/{release['id']}", fields)
    else:
        print(f"创建 Release 草稿：{tag}", flush=True)
        release = github.request(
            "POST", "/releases", fields,
            recover=lambda: find_release(github, tag),
        )
    release_url = release["html_url"]
    is_draft = release["draft"]
    print(f"{'Release 草稿' if is_draft else '补齐已发布 Release 的 Assets'}：{release_url}", flush=True)
    try:
        old_assets = {
            item["name"]: item for item in list_release_assets(github, release["id"])
        }
        upload_url = release["upload_url"].split("{", 1)[0]
        for path in assets:
            existing = old_assets.get(path.name)
            if asset_matches(existing, path):
                print(f"Assets 已有完整附件，保留：{path.name}", flush=True)
                continue
            print(f"上传到 Assets：{path.name}（{path.stat().st_size} 字节）", flush=True)
            if existing:
                github.request("DELETE", f"/releases/assets/{existing['id']}", missing_ok=True)
            url = upload_url + "?" + urllib.parse.urlencode({"name": path.name})
            uploaded = github.request(
                "POST", url, path.read_bytes(), "application/vnd.android.package-archive",
                recover=lambda: recover_upload(github, release["id"], path),
            )
            if not asset_matches(uploaded, path):
                raise ReleaseError(f"附件 {path.name} 上传未完成或校验失败。")
            print(f"已上传到 Assets：{path.name}", flush=True)
        # Also check existing published Releases: an empty Release must never be
        # treated as complete simply because its tag already has a Release.
        uploaded_assets = {
            item["name"]: item for item in list_release_assets(github, release["id"])
        }
        missing = [path.name for path in assets if not asset_matches(uploaded_assets.get(path.name), path)]
        if missing:
            raise ReleaseError("Release 的 Assets 中缺少完整附件：" + "、".join(missing))
        if is_draft:
            print("APK 已上传到 Assets，正在正式发布 Release 并设为 Latest……", flush=True)
            published = github.request("PATCH", f"/releases/{release['id']}", {"draft": False, "make_latest": "true"})
            if published.get("draft", True):
                raise ReleaseError("GitHub 返回的 Release 仍为草稿。")
            release_url = published["html_url"]
    except (ReleaseError, OSError) as error:
        raise ReleaseError(
            f"{error}\nRelease 尚未确认附件齐全并发布完成：{release_url}。重新执行脚本可继续补传。"
        ) from None
    for path in assets:
        apk = uploaded_assets[path.name]
        print(f"APK 下载地址（{path.name}）：{apk['browser_download_url']}", flush=True)
    return tag, release_url


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tasks-dir", type=Path, default=Path(os.environ.get("HONGGUOTV_BUILD_TASKS_DIR", DEFAULT_TASKS)))
    parser.add_argument("--task-id", default=os.environ.get("RELEASE_TASK_ID", ""))
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", DEFAULT_REPOSITORY))
    parser.add_argument("--notes", default=os.environ.get("RELEASE_NOTES", ""),
                        help="补充说明，放在发布说明开头；更新列表由脚本自动生成")
    parser.add_argument("--single-apk", action="store_true",
                        help="仅发布所选构建的当前 APK；默认要求标准包与 Android 5 兼容包一起发布")
    parser.add_argument("--dry-run", action="store_true",
                        help="读取并校验真实 APK，查询 GitHub；不创建标签、上传附件或发布 Release")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        raise ReleaseError("GitHub 仓库名称格式应为 owner/repository。")
    directory, result = read_build(args.tasks_dir, args.task_id.strip())
    with tempfile.TemporaryDirectory(prefix="hongguotv-release-") as temporary:
        assets, metadata = prepare_assets(directory, result, Path(temporary), single_apk=args.single_apk)
        print(f"发布构建任务 {metadata['taskId']}：{metadata['versionName']} ({metadata['versionCode']})，源码 {metadata['commit']}", flush=True)
        github = GitHub(args.repo, token_from_environment())
        tag, url = publish(github, assets, metadata, args.notes, dry_run=args.dry_run)
        if args.dry_run:
            print(f"模拟执行完成：{tag}。未向 GitHub 写入任何变更。", flush=True)
            return
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
