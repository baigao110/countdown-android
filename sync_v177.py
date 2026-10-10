#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v177 同步：源码全量增量同步 + APK 资产更新 + Release PATCH 到 v177。"""
import os, sys, json, base64, hashlib, subprocess, time, urllib.request, urllib.error
from urllib.parse import quote

OWNER = "baigao110"
REPO = "countdown-android"
ROOT = os.path.dirname(os.path.abspath(__file__))
GIT = r"C:/Users/BDJ/.workbuddy/binaries/PortableGit/versions/1.2.0/cmd/git.exe"
TOKEN = os.environ.get("GH_TOKEN", "").strip()
if not TOKEN:
    print("ERROR: 请先设置环境变量 GH_TOKEN"); sys.exit(1)

HDR = {"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
       "User-Agent": "countdown-sync", "Cache-Control": "no-cache"}
TEXT_EXT = {".kt", ".java", ".xml", ".gradle", ".properties", ".bat", ".md",
            ".json", ".pro", ".txt", ".yml", ".yaml", ".gitignore", ""}
RELEASE_TAG = "v1.0.0.38"
APK_NAME = "countdown-android-v1.0.0.38-release.apk"


def api(method, path, data=None, quiet=False, binary=False):
    url = f"https://api.github.com{quote(path, safe='/@:')}"
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=HDR, method=method)
    if not binary:
        req.add_header("Content-Type", "application/json")
    for attempt in range(1, 7):
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                raw = r.read()
                return (raw if binary else (json.loads(raw.decode("utf-8")) if raw else None))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "ignore")[:400]
            if e.code in (403, 429, 502, 503):
                wait = 3 * attempt
                print(f"  [限流 {e.code}] 退避 {wait}s 重试（{path}）")
                time.sleep(wait); continue
            e.detail = detail
            if e.code == 404:
                return None
            if not quiet:
                print(f"  HTTP {e.code} {method} {path}: {detail}")
            raise
    raise RuntimeError("api retry exhausted: " + path)


def tracked_files():
    out = subprocess.run([GIT, "-c", "core.quotePath=false", "ls-files"], cwd=ROOT, capture_output=True)
    return [l.strip() for l in out.stdout.decode("utf-8", errors="replace").splitlines() if l.strip()]


def content_equal(remote, local, path):
    if remote == local:
        return True
    if os.path.splitext(path)[1].lower() not in TEXT_EXT:
        return False
    for er in ("utf-8", "gbk"):
        for el in ("utf-8", "gbk"):
            try:
                if (remote.decode(er).replace("\r\n", "\n")) == (local.decode(el).replace("\r\n", "\n")):
                    return True
            except UnicodeDecodeError:
                continue
    return False


def sync_file(rel, local_path, max_attempts=4):
    with open(local_path, "rb") as f:
        local = f.read()
    for attempt in range(1, max_attempts + 1):
        meta = api("GET", f"/repos/{OWNER}/{REPO}/contents/{rel}")
        if meta is not None and content_equal(base64.b64decode(meta["content"]), local, local_path):
            return "SAME"
        payload = {"message": f"同步安卓版源码：{rel}", "content": base64.b64encode(local).decode("ascii")}
        if meta is not None:
            payload["sha"] = meta.get("sha")
        try:
            res = api("PUT", f"/repos/{OWNER}/{REPO}/contents/{rel}", payload, quiet=(attempt < max_attempts))
        except urllib.error.HTTPError as e:
            d = getattr(e, "detail", "") or ""
            if "Secret detected" in d or "secret_scanning" in d:
                print(f"  [被拦截] {rel} 含疑似密钥，跳过"); return "FAILED"
            if e.code == 409 and attempt < max_attempts:
                print(f"  [409] {rel} 重试（{attempt}）"); time.sleep(1.5 * attempt); continue
            raise
        if res and not res.get("commit", {}).get("files"):
            return "SAME"
        return "NEW" if meta is None else "UPDATED"
    print(f"  [跳过] {rel} 重试失败"); return "FAILED"


def sync_apk():
    apk_local = os.path.join(ROOT, APK_NAME)
    with open(apk_local, "rb") as f:
        local_bytes = f.read()
    local_sha = hashlib.sha256(local_bytes).hexdigest()
    rel = api("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{RELEASE_TAG}")
    if rel is None or "id" not in rel:
        print("  [ERR] 找不到 release", RELEASE_TAG); return
    rid = rel["id"]
    cur = next((a for a in rel.get("assets", []) if a["name"] == APK_NAME), None)
    if cur is not None:
        req = urllib.request.Request(cur["url"], method="GET", headers=HDR)
        req.add_header("Accept", "application/octet-stream")
        with urllib.request.urlopen(req, timeout=300) as r:
            remote_bytes = r.read()
        if hashlib.sha256(remote_bytes).hexdigest() == local_sha:
            print("  [OK] 线上 APK 与本地一致，无需上传"); return
        print("  不一致，删除旧资产后重传")
        api("DELETE", f"/repos/{OWNER}/{REPO}/releases/assets/{cur['id']}")
    upload_url = (f"https://uploads.github.com/repos/{OWNER}/{REPO}/releases/{rid}/assets"
                  f"?name={APK_NAME}")
    req = urllib.request.Request(upload_url, data=local_bytes, method="POST", headers=HDR)
    req.add_header("Content-Type", "application/vnd.android.package-archive")
    with urllib.request.urlopen(req, timeout=600) as r:
        res = json.loads(r.read().decode("utf-8"))
    print(f"  [OK] 已上传 {res['name']}（{res['size']} 字节）{res.get('browser_download_url')}")


def patch_release():
    rel = api("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{RELEASE_TAG}")
    rid = rel["id"]
    old_body = rel.get("body") or ""
    note = ("v177：重新加回「华都云境悦府购买正计时」内置正计时（与华都云境悦府 / GTA6 同款玻璃质感卡片，"
            "方向相反）：起点固定 2025-04-20 17:30，显示「自购买至今已过去多久」，全部 11 档显示模式都照常可用，"
            "名字 / 颜色 / 跳秒动画 / 备注 / 提示音都能照常改。新增「暂停 / 开始」按钮（玻璃质感，与 GTA6 展开 / 收起悬浮窗同款）："
            "点「暂停」正计时当场冻结、数字停住不动，按钮翻成「开始」；再点「开始」就从冻结那一刻接着走，"
            "暂停期间流失的墙钟时间自动补齐（暂停 3 分钟 → 开始正好多走 3 分钟），「暂停 → 开始」可一直来回循环；"
            "主界面卡片和悬浮窗里都有这颗按钮，两边状态互相同步、点哪边另一边都跟着变。悬浮窗同步照旧")
    new_block = (f"**发布日期：2026-10-10**\n"
                 f"v177：{note}\n"
                 f"**版本号**：versionCode 165（versionName 1.0.0.38）\n"
                 f"**更新日志**：{note}\n")
    body = new_block + "\n" + old_body
    api("PATCH", f"/repos/{OWNER}/{REPO}/releases/{rid}",
        {"tag_name": RELEASE_TAG, "name": "倒计时安卓版 v1.0.0.38（v177）",
         "body": body, "make_latest": True})
    print("  [OK] Release PATCH -> v177（make_latest）")


def main():
    subprocess.run([GIT, "add", "-A"], cwd=ROOT)
    subprocess.run([GIT, "add", "-f", APK_NAME], cwd=ROOT)
    files = tracked_files()
    print(f"=== 1. 源码全量比对（{len(files)} 个文件）===")
    counts = {"SAME": 0, "UPDATED": 0, "NEW": 0, "FAILED": 0}
    for rel in files:
        lp = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.isfile(lp):
            continue
        st = sync_file(rel, lp)
        counts[st] = counts.get(st, 0) + 1
        if st != "SAME":
            print(f"  [{st}] {rel}")
    print(f"  结果：一致 {counts['SAME']}，更新 {counts['UPDATED']}，新增 {counts['NEW']}，失败 {counts['FAILED']}")
    print("=== 2. APK 资产 ===")
    sync_apk()
    print("=== 3. PATCH Release -> v177 ===")
    patch_release()
    print("DONE")


if __name__ == "__main__":
    main()
