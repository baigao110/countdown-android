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

用法（令牌走环境变量，绝不落盘）：
    set GH_TOKEN=ghp_xxxxxxxxxxxx        # Windows
    python sync_to_github.py

注意：
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
import urllib.request
import urllib.error

OWNER = "baigao110"
REPO = "countdown-android"
ROOT = os.path.dirname(os.path.abspath(__file__))
GIT = r"C:/Users/BDJ/.workbuddy/binaries/PortableGit/versions/1.2.0/cmd/git.exe"

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


def api(method, path, data=None):
    url = f"https://api.github.com{path}"
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=HDR, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            raw = r.read()
            return json.loads(raw.decode("utf-8")) if raw else None
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        detail = e.read().decode("utf-8", "ignore")[:300]
        print(f"  HTTP {e.code} {method} {path}: {detail}")
        raise


def tracked_files():
    out = subprocess.run([GIT, "ls-files"], cwd=ROOT, capture_output=True, text=True)
    return [l.strip() for l in out.stdout.splitlines() if l.strip()]


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


def sync_file(rel_api_path: str, local_path: str):
    meta = api("GET", f"/repos/{OWNER}/{REPO}/contents/{rel_api_path}")
    with open(local_path, "rb") as f:
        local = f.read()
    if meta is not None and content_equal(base64.b64decode(meta["content"]), local, local_path):
        return "SAME"
    payload = {
        "message": f"同步安卓版源码：{rel_api_path}",
        "content": base64.b64encode(local).decode("ascii"),
    }
    if meta is not None:
        payload["sha"] = meta.get("sha")
    res = api("PUT", f"/repos/{OWNER}/{REPO}/contents/{rel_api_path}", payload)
    # GitHub 在内容等价于现状时不会创建提交（files 为空），此时视为已同步
    if res and not res.get("commit", {}).get("files"):
        return "SAME"
    return "NEW" if meta is None else "UPDATED"


def check_release_asset():
    apk_name = "countdown-android-v1.0.0-release.apk"
    apk_local = os.path.join(ROOT, apk_name)
    with open(apk_local, "rb") as f:
        local_bytes = f.read()
    local_sha = hashlib.sha256(local_bytes).hexdigest()

    rel = api("GET", f"/repos/{OWNER}/{REPO}/releases/tags/v1.0.0")
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
    counts = {"SAME": 0, "UPDATED": 0, "NEW": 0}
    for rel in files:
        local_path = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.isfile(local_path):
            continue
        state = sync_file(rel, local_path)
        counts[state] += 1
        if state != "SAME":
            print(f"  [{state}] {rel}")
    print(f"  结果：一致 {counts['SAME']}，更新 {counts['UPDATED']}，新增 {counts['NEW']}")
    if counts["UPDATED"] == 0 and counts["NEW"] == 0:
        print("  => 源码已与 GitHub 完全同步")

    print("=== 2. 校验 v1.0.0 Release 的 APK 资产 ===")
    check_release_asset()
    print("DONE")


if __name__ == "__main__":
    main()
