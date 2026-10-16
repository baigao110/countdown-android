#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
安卓版 → GitHub 幂等全量同步脚本（修正版）
========================================
按 update.json 里的 versionName 动态定位 Release 与 APK 文件名，避免写死 v1.0.0。

做了三件事：
1. 遍历本地 git 已跟踪文件，与 GitHub 仓库上的内容逐一比对，只提交真正有差异的文件；
2. 删除旧 APK 资产后重新上传本地签名 APK 到对应版本 Release（不下载旧包比对，绕开代理对二进制直链的 502 拦截）；
3. 把新增功能说明补进 Release 发布说明并设为 latest。

用法（令牌只走环境变量，绝不写进文件）：
    set GH_TOKEN=<你的 GitHub 个人访问令牌>       # Windows
    python sync_to_github.py
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
import ssl
import http.client
from urllib.parse import quote

OWNER = "baigao110"
REPO = "daojishi"
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


def api(method, path, data=None, quiet=False):
    url = f"https://api.github.com{quote(path, safe='/@:')}"
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=HDR, method=method)
    req.add_header("Content-Type", "application/json")
    last_err = None
    for attempt in range(1, 13):
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
        except (urllib.error.URLError, ssl.SSLError, http.client.IncompleteRead,
                http.client.HTTPException, ConnectionError, OSError) as e:
            # 代理/TLS 握手偶发被重置（SSL: UNEXPECTED_EOF_WHILE_READING）或读响应中断
            # （IncompleteRead）时退避重试
            last_err = e
            if not quiet:
                print(f"  [retry {attempt}/12] TLS/网络抖动: {e}")
            time.sleep(3 * attempt)
    if last_err:
        raise last_err


def tracked_files():
    out = subprocess.run([GIT, "-c", "core.quotePath=false", "ls-files"],
                         cwd=ROOT, capture_output=True)
    text = out.stdout.decode("utf-8", errors="replace")
    return [l.strip() for l in text.splitlines() if l.strip()]


def content_equal(remote: bytes, local: bytes, path: str) -> bool:
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
    with open(local_path, "rb") as f:
        local = f.read()

    for attempt in range(1, max_attempts + 1):
        meta = api("GET", f"/repos/{OWNER}/{REPO}/contents/{rel_api_path}")
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

        if res and not res.get("commit", {}).get("files"):
            return "SAME"
        return "NEW" if meta is None else "UPDATED"

    print(f"  [跳过] {rel_api_path} 重试 {max_attempts} 次仍未成功，请稍后重跑脚本")
    return "FAILED"


def get_release(tag: str):
    return api("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{tag}")


def upload_apk(tag: str, apk_name: str):
    apk_local = os.path.join(ROOT, apk_name)
    if not os.path.isfile(apk_local):
        print(f"  [跳过] 本地找不到 {apk_name}")
        return
    with open(apk_local, "rb") as f:
        local_bytes = f.read()
    local_sha = hashlib.sha256(local_bytes).hexdigest()

    rel = get_release(tag)
    if rel is None:
        print(f"  [WARN] Release {tag} 不存在，跳过 APK 上传")
        return
    cur = next((a for a in rel.get("assets", []) if a["name"] == apk_name), None)
    if cur is not None:
        print(f"  删除旧资产 {apk_name}（{cur['size']} 字节）")
        api("DELETE", f"/repos/{OWNER}/{REPO}/releases/assets/{cur['id']}")
        time.sleep(1.5)

    upload_url = (f"https://uploads.github.com/repos/{OWNER}/{REPO}/releases/"
                  f"{rel['id']}/assets?name={apk_name}")
    req = urllib.request.Request(upload_url, data=local_bytes, method="POST")
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Type", "application/vnd.android.package-archive")
    req.add_header("User-Agent", "countdown-sync")
    with urllib.request.urlopen(req, timeout=600) as r:
        res = json.loads(r.read().decode("utf-8"))
    print(f"  本地 APK sha256: {local_sha[:16]}...")
    print(f"  [OK] 已上传 {res['name']}（{res['size']} 字节）")
    print(f"       {res.get('browser_download_url')}")


def patch_release_body(tag: str, note_line: str):
    rel = get_release(tag)
    if rel is None:
        return
    body = rel.get("body") or ""
    if note_line and note_line not in body:
        new_body = (note_line + "\n\n" + body) if body.strip() else note_line
    else:
        new_body = body
    api("PATCH", f"/repos/{OWNER}/{REPO}/releases/{rel['id']}",
        {"body": new_body, "make_latest": "true"})


def create_release_if_missing(tag: str, note_line: str):
    existing = get_release(tag)
    if existing is not None:
        print(f"  [INFO] Release {tag} 已存在，跳过创建")
        return existing
    print(f"=== 0. 新建 Release {tag} ===")
    body = note_line if note_line else f"版本 {tag}"
    payload = {
        "tag_name": tag,
        "name": tag,
        "body": body,
        "draft": False,
        "prerelease": False,
        "make_latest": "true",
    }
    try:
        rel = api("POST", f"/repos/{OWNER}/{REPO}/releases", payload)
        print(f"  [OK] 已创建 Release {tag}（id={rel.get('id')}）")
        return rel
    except Exception as e:  # noqa
        print(f"  [ERROR] 创建 Release 失败: {e}")
        return None


def main():
    with open(os.path.join(ROOT, "update.json"), encoding="utf-8") as f:
        cfg = json.load(f)
    tag = f"v{cfg['versionName']}"
    apk_name = f"countdown-android-v{cfg['versionName']}-release.apk"

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

    note_line = """🔧 「药名库」批量删除卡死（ANR）已修复：点「批量删除 → 全选 → 删除选中」又会卡死——根因是删除逻辑在主线程对选中项（可能 2 万条）逐条调用 removeMedName，每调一次都要解析并写回整个药名库 JSON（约 2 万条），约 2 万次 ×（解析 2 万 + 写 2 万）= O(n²) 直接拖死界面线程。现已新增批量删除接口 removeMedNames（一次读 / 一次过滤 / 一次写，O(n)），并把「删除选中」与单条删除都挪到后台线程（删除时弹「正在删除 N 个药名…」进度框），一次性清空全部约 2.1 万条也能秒删不卡。版本 v1.0.0.62。"""
    print(f"=== 2. 上传 APK 到 Release {tag} ===")
    create_release_if_missing(tag, note_line)
    upload_apk(tag, apk_name)

    print("=== 3. 发布说明 & 设为 latest ===")
    patch_release_body(tag, note_line)
    print("DONE")


if __name__ == "__main__":
    main()
