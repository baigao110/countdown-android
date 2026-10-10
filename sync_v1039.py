#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.0.39 同步：源码全量增量同步 + 新建 Release v1.0.0.39 + 上传新 APK 资产。
从 update.json 读取版本与文案（单一事实来源）。旧 v1.0.0.38 Release 保留为历史。"""
import os, sys, json, base64, hashlib, subprocess, time, socket, urllib.request, urllib.error
import http.client
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
RELEASE_TAG = "v1.0.0.39"
OLD_TAG = "v1.0.0.38"
APK_NAME = "countdown-android-v1.0.0.39-release.apk"
RELEASE_DATE = "2026-10-10"

with open(os.path.join(ROOT, "update.json"), encoding="utf-8") as f:
    UJ = json.load(f)
VERSION_CODE = int(UJ["versionCode"])
VERSION_NAME = UJ["versionName"]
NOTE = UJ["note"]
REL_NAME = f"倒计时安卓版 {VERSION_NAME}"


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
        except (urllib.error.URLError, http.client.IncompleteRead,
                ConnectionError, TimeoutError, socket.timeout) as e:
            if attempt < 6:
                wait = 3 * attempt
                print(f"  [传输错误 {type(e).__name__}] 退避 {wait}s 重试（{path}）")
                time.sleep(wait); continue
            print(f"  [传输错误] {method} {path}: {e}")
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


def ensure_release():
    rel = api("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{RELEASE_TAG}")
    if rel is not None:
        print(f"  release {RELEASE_TAG} 已存在，id={rel['id']}")
        return rel["id"]
    old = api("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{OLD_TAG}")
    old_body = (old.get("body") or "") if old else ""
    new_block = (f"**发布日期：{RELEASE_DATE}**\n"
                 f"{NOTE}\n"
                 f"**版本号**：versionCode {VERSION_CODE}（versionName {VERSION_NAME}）\n"
                 f"**更新日志**：{NOTE}\n")
    body = new_block + "\n" + old_body
    created = api("POST", f"/repos/{OWNER}/{REPO}/releases",
                  {"tag_name": RELEASE_TAG, "name": REL_NAME, "body": body,
                   "make_latest": "true", "target_commitish": "main"})
    print(f"  [OK] 已新建 release {RELEASE_TAG}（id={created['id']}）")
    return created["id"]


def sync_apk(rid):
    apk_local = os.path.join(ROOT, APK_NAME)
    with open(apk_local, "rb") as f:
        local_bytes = f.read()
    local_sha = hashlib.sha256(local_bytes).hexdigest()
    rel = api("GET", f"/repos/{OWNER}/{REPO}/releases/{rid}")
    cur = next((a for a in rel.get("assets", []) if a["name"] == APK_NAME), None)
    if cur is not None:
        req = urllib.request.Request(cur["url"], method="GET", headers=HDR)
        req.add_header("Accept", "application/octet-stream")
        with urllib.request.urlopen(req, timeout=300) as r:
            remote_bytes = r.read()
        if hashlib.sha256(remote_bytes).hexdigest() == local_sha:
            print("  [OK] 线上 APK 与本地一致，无需上传"); return
        print("  不一致，删旧重传")
        api("DELETE", f"/repos/{OWNER}/{REPO}/releases/assets/{cur['id']}")
    upload_url = (f"https://uploads.github.com/repos/{OWNER}/{REPO}/releases/{rid}/assets"
                  f"?name={APK_NAME}")
    req = urllib.request.Request(upload_url, data=local_bytes, method="POST", headers=HDR)
    req.add_header("Content-Type", "application/vnd.android.package-archive")
    with urllib.request.urlopen(req, timeout=600) as r:
        res = json.loads(r.read().decode("utf-8"))
    print(f"  [OK] 已上传 {res['name']}（{res['size']} 字节）{res.get('browser_download_url')}")


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
        try:
            st = sync_file(rel, lp)
        except Exception as e:
            print(f"  [ERROR] {rel}: {e}")
            st = "FAILED"
        counts[st] = counts.get(st, 0) + 1
        if st != "SAME":
            print(f"  [{st}] {rel}")
    print(f"  结果：一致 {counts['SAME']}，更新 {counts['UPDATED']}，新增 {counts['NEW']}，失败 {counts['FAILED']}")
    print("=== 2. 新建 Release v1.0.0.39 + 上传 APK ===")
    rid = ensure_release()
    sync_apk(rid)
    print("DONE")


if __name__ == "__main__":
    main()
