#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
安卓版 → GitHub 幂等全量同步脚本
================================

用途：把 CountdownAndroid 工程同步到 GitHub 仓库 baigao110/countdown-android。

做了三件事：
1. 遍历本地 git 已跟踪文件，与 GitHub 仓库上的内容逐一比对，**只提交真正有差异的文件**；
2. 校验 v1.0.0 Release 的 APK 资产与本地签名 APK 的 sha256，不一致才覆盖上传；
3. 全程幂等 —— 重复运行不会产生空提交，输出「全部一致」即代表完全同步。

用法（令牌只走环境变量，绝不写进文件）：
    set GH_TOKEN=<你的 GitHub 个人访问令牌>       # Windows
    python sync_to_github.py

或双击同目录下的「同步到GitHub.bat」，令牌当场输入、不落盘。

注意：
- **永远不要把令牌填进本文件或任何源码**（GitHub 会拦截含令牌的提交；一旦进入公开仓库即视为泄露）。
- 本机代理会拦截 github.com 的 git 协议（push 返回 502），因此**不能用 git push**，
  必须走 REST API（api.github.com 走代理正常）。
- 文本文件存在编码差异（线上 UTF-8 / 本地 GBK，如 build_apk.bat），
  故比较时按「解码后的文本内容 + 换行归一」判断，避免误报差异。
"""
import os
import sys
import json
import base64
import hashlib
import subprocess
import time
import urllib.request
import urllib.error
from urllib.parse import quote

OWNER = "baigao110"
REPO = "countdown-android"
ROOT = os.path.dirname(os.path.abspath(__file__))
GIT = r"C:/Users/BDJ/.workbuddy/binaries/PortableGit/versions/1.2.0/cmd/git.exe"

# 安全约定：令牌只从环境变量读取，绝不能写死在文件里。
# （GitHub 的密钥扫描会拦截含令牌字样的提交，且令牌一旦进入公开仓库即视为泄露。）
TOKEN = os.environ.get("GH_TOKEN", "").strip()
if not TOKEN:
    print("ERROR: 请先设置环境变量 GH_TOKEN（classic PAT，勾选 repo 权限）")
    sys.exit(1)

HDR = {
    "Authorization": f"Bearer {TOKEN}",
    "Accept": "application/vnd.github+json",
    "User-Agent": "countdown-sync",
    "Cache-Control": "no-cache",
}

TEXT_EXT = {".kt", ".java", ".xml", ".gradle", ".properties", ".bat", ".md",
            ".json", ".pro", ".txt", ".yml", ".yaml", ".gitignore", ""}


def current_version() -> str:
    """从 app/build.gradle 读取 versionName，避免版本号写死在多处不同步。"""
    gradle = os.path.join(ROOT, "app", "build.gradle")
    with open(gradle, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("versionName"):
                return line.split("'")[1].split('"')[0]
    return "1.0.0"


def api(method, path, data=None, quiet=False):
    # 路径可能含中文文件名（如「同步到GitHub.bat」），必须先做 URL 编码，
    # 否则 http.client 会用 ascii 编码请求行而抛 UnicodeEncodeError。
    url = f"https://api.github.com{quote(path, safe='/@:')}"
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=HDR, method=method)
    req.add_header("Content-Type", "application/json")
    # 本机代理会偶发中断 SSL 握手（UNEXPECTED_EOF / reset），统一退避重试，
    # 否则一次抖动就会打断整轮同步（创建 Release、上传 APK 尤其容易中招）。
    for attempt in range(1, 5):
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                raw = r.read()
                return json.loads(raw.decode("utf-8")) if raw else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "ignore")[:500]
            e.detail = detail  # 供上层判断错误类型
            if e.code == 404:
                return None
            if not quiet:
                print(f"  HTTP {e.code} {method} {path}: {detail}")
            raise
        except urllib.error.URLError as e:
            # 纯网络抖动：退避重试（HTTPError 是 URLError 的子类，已在上面分支处理）
            if attempt == 4:
                raise
            print(f"  [网络] {method} {path} {e.reason}，第 {attempt} 次重试")
            time.sleep(2.0 * attempt)


def tracked_files():
    # core.quotePath=false：否则中文文件名（如「同步到GitHub.bat」）会被转义成
    # "\345\220\214..." 字面串，导致拼出的本地路径不存在而被静默跳过。
    out = subprocess.run([GIT, "-c", "core.quotePath=false", "ls-files"],
                         cwd=ROOT, capture_output=True)
    text = out.stdout.decode("utf-8", errors="replace")
    return [l.strip() for l in text.splitlines() if l.strip()]


def content_equal(remote: bytes, local: bytes, path: str) -> bool:
    """内容是否等价：先比字节，再按文本解码（兼容 UTF-8/GBK 与 CRLF/LF）比较。"""
    if remote == local:
        return True
    if os.path.splitext(path)[1].lower() not in TEXT_EXT:
        return False
    for enc_r in ("utf-8", "gbk"):
        for enc_l in ("utf-8", "gbk"):
            try:
                a = remote.decode(enc_r)
                b = local.decode(enc_l)
            except UnicodeDecodeError:
                continue
            if a.replace("\r\n", "\n") == b.replace("\r\n", "\n"):
                return True
    return False


def sync_file(rel_api_path: str, local_path: str, max_attempts: int = 4):
    """
    同步单个文件（带 409 自愈重试）。

    409 Conflict 的常见成因：本机有代理，PUT 请求偶发被重放——第一次其实已成功更新
    （文件 blob sha 随之改变），重放的第二次仍携带旧 sha，GitHub 便返回 409。
    因此遇到 409 不直接失败：重新 GET 取最新 sha，若内容已等价则判定同步完成，
    否则用新 sha 再试一次。
    """
    with open(local_path, "rb") as f:
        local = f.read()

    for attempt in range(1, max_attempts + 1):
        try:
            meta = api("GET", f"/repos/{OWNER}/{REPO}/contents/{rel_api_path}")
        except urllib.error.URLError as e:
            if attempt < max_attempts:
                print(f"  [网络] {rel_api_path} 读取失败（{e.reason}），第 {attempt} 次重试")
                time.sleep(2.0 * attempt)
                continue
            raise
        if meta is not None and content_equal(base64.b64decode(meta["content"]), local, local_path):
            return "SAME"

        payload = {
            "message": f"同步安卓版源码：{rel_api_path}",
            "content": base64.b64encode(local).decode("ascii"),
        }
        if meta is not None:
            payload["sha"] = meta.get("sha")

        try:
            res = api("PUT", f"/repos/{OWNER}/{REPO}/contents/{rel_api_path}", payload,
                      quiet=(attempt < max_attempts))
        except urllib.error.HTTPError as e:
            detail = getattr(e, "detail", "") or ""
            if "Secret detected" in detail or "secret_scanning" in detail:
                print(f"  [被拦截] {rel_api_path} 含疑似密钥内容，GitHub 拒绝提交")
                print("           → 请删除文件里的令牌字样（以 GitHub PAT 前缀开头的字符串）后重跑；"
                      "已泄露的令牌请到 GitHub 立即吊销")
                return "FAILED"
            if e.code == 409 and attempt < max_attempts:
                print(f"  [409] {rel_api_path} sha 已过期，重新获取后重试（第 {attempt} 次）")
                time.sleep(1.5 * attempt)
                continue
            raise
        except urllib.error.URLError as e:
            # 本机代理偶发 SSL 握手中断（UNEXPECTED_EOF / connection reset），
            # 这不是内容问题，直接退避重试即可；否则整轮同步会被一次抖动打断。
            if attempt < max_attempts:
                print(f"  [网络] {rel_api_path} {e.reason}，第 {attempt} 次重试")
                time.sleep(2.0 * attempt)
                continue
            raise

        # GitHub 在内容等价于现状时不会创建提交（files 为空），此时视为已同步
        if res and not res.get("commit", {}).get("files"):
            return "SAME"
        return "NEW" if meta is None else "UPDATED"

    print(f"  [跳过] {rel_api_path} 重试 {max_attempts} 次仍未成功，请稍后重跑脚本")
    return "FAILED"


def check_release_asset():
    version = current_version()
    tag = f"v{version}"
    apk_name = f"countdown-android-{tag}-release.apk"
    apk_local = os.path.join(ROOT, apk_name)
    if not os.path.isfile(apk_local):
        print(f"  [跳过] 本地没有 {apk_name}，请先运行 build_apk.bat 打包")
        return
    with open(apk_local, "rb") as f:
        local_bytes = f.read()
    local_sha = hashlib.sha256(local_bytes).hexdigest()

    rel = api("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{tag}")
    if rel is None:
        print(f"  线上没有 {tag} Release，自动创建")
        # 更新日志统一取自 update.json 的 note，App 内弹窗与 Release 页面显示同一份内容
        try:
            with open(os.path.join(ROOT, "update.json"), "r", encoding="utf-8") as f:
                note = json.load(f).get("note", "").strip()
        except Exception:
            note = ""
        body = f"倒计时安卓版 {tag}\n\n更新日志：\n{note}\n\n安装：下载 {apk_name} 后覆盖安装即可。"
        rel = api("POST", f"/repos/{OWNER}/{REPO}/releases", {
            "tag_name": tag,
            "name": f"倒计时安卓版 {tag}",
            "body": body,
            "draft": False,
            "prerelease": False,
        })
    cur = next((a for a in rel.get("assets", []) if a["name"] == apk_name), None)
    if cur is not None:
        req = urllib.request.Request(cur["url"], method="GET")
        req.add_header("Authorization", f"Bearer {TOKEN}")
        req.add_header("Accept", "application/octet-stream")
        req.add_header("User-Agent", "countdown-sync")
        with urllib.request.urlopen(req, timeout=300) as r:
            remote_bytes = r.read()
        remote_sha = hashlib.sha256(remote_bytes).hexdigest()
        print(f"  本地 APK: {local_sha[:16]}... ({len(local_bytes)} 字节)")
        print(f"  线上 APK: {remote_sha[:16]}... ({len(remote_bytes)} 字节)")
        if remote_sha == local_sha:
            print("  [OK] 线上 APK 与本地一致，无需上传")
            return
        print("  不一致，删除旧资产后重新上传")
        api("DELETE", f"/repos/{OWNER}/{REPO}/releases/assets/{cur['id']}")
    else:
        print("  线上无该资产，直接上传")

    upload_url = (f"https://uploads.github.com/repos/{OWNER}/{REPO}/releases/"
                  f"{rel['id']}/assets?name={apk_name}")
    req = urllib.request.Request(upload_url, data=local_bytes, method="POST")
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Type", "application/vnd.android.package-archive")
    req.add_header("User-Agent", "countdown-sync")
    with urllib.request.urlopen(req, timeout=600) as r:
        res = json.loads(r.read().decode("utf-8"))
    print(f"  [OK] 已上传 {res['name']}（{res['size']} 字节）")
    print(f"       {res.get('browser_download_url')}")


def main():
    files = tracked_files()
    print(f"=== 1. 源码全量比对（本地跟踪 {len(files)} 个文件）===")
    counts = {"SAME": 0, "UPDATED": 0, "NEW": 0, "FAILED": 0}
    for rel in files:
        local_path = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.isfile(local_path):
            continue
        state = sync_file(rel, local_path)
        counts[state] = counts.get(state, 0) + 1
        if state != "SAME":
            print(f"  [{state}] {rel}")
    print(f"  结果：一致 {counts['SAME']}，更新 {counts['UPDATED']}，"
          f"新增 {counts['NEW']}，失败 {counts['FAILED']}")
    if counts["UPDATED"] == 0 and counts["NEW"] == 0 and counts["FAILED"] == 0:
        print("  => 源码已与 GitHub 完全同步")

    print(f"=== 2. 校验 v{current_version()} Release 的 APK 资产 ===")
    check_release_asset()
    print("DONE")


if __name__ == "__main__":
    main()
