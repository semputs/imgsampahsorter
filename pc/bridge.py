"""
ImgSampah Bridge - runs on the PC.

* Starts ComfyUI hidden in the background (no window) listening only on 127.0.0.1:8188.
* Listens on port 8190 and lets ONLY Tailscale devices (100.64.0.0/10) that know the
  pairing code in.  Everything not under /isb/ is passed straight through to ComfyUI,
  so the phone can use the real ComfyUI editor and API.
* /isb/... endpoints move finished images from chosen output subfolders to the phone:
  the phone downloads a file, confirms it, and only then is the PC copy deleted.

Run with ComfyUI's own Python (it already has aiohttp).
"""

import asyncio
import hmac
import ipaddress
import json
import logging
import logging.handlers
import os
import re
import secrets
import subprocess
import sys
import time
from pathlib import Path

import aiohttp
from aiohttp import web

VERSION = "1.1"
APP_DIR = Path(os.environ.get("ISB_APP_DIR") or (Path(os.environ.get("LOCALAPPDATA", ".")) / "ImgSampahBridge"))
PORT = int(os.environ.get("ISB_PORT", "8190"))
COMFY_PORT = int(os.environ.get("ISB_COMFY_PORT", "8188"))
COMFY = f"http://127.0.0.1:{COMFY_PORT}"
COMFY_WS = f"ws://127.0.0.1:{COMFY_PORT}"
IS_WIN = sys.platform == "win32"
NO_WINDOW = 0x08000000 if IS_WIN else 0

MEDIA_EXT = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".mp4", ".webm", ".mov", ".avif"}
HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers",
    "transfer-encoding", "upgrade", "host", "content-length",
}
ALLOWED_NETS = [
    ipaddress.ip_network("100.64.0.0/10"),        # Tailscale IPv4
    ipaddress.ip_network("fd7a:115c:a1e0::/48"),  # Tailscale IPv6
]

APP_DIR.mkdir(parents=True, exist_ok=True)
log = logging.getLogger("isb")
log.setLevel(logging.INFO)
_h = logging.handlers.RotatingFileHandler(APP_DIR / "bridge.log", maxBytes=1_000_000, backupCount=2, encoding="utf-8")
_h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
log.addHandler(_h)
if os.environ.get("ISB_CONSOLE"):
    log.addHandler(logging.StreamHandler())


# ------------------------------------------------------------------ config

def load_json(path: Path, default):
    try:
        # utf-8-sig: Windows PowerShell 5 writes a BOM at the start of UTF-8 files.
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception:
        return default


PATHS = load_json(APP_DIR / "paths.json", {})
CFG_PATH = APP_DIR / "config.json"
CFG = load_json(CFG_PATH, {})


def norm_code(s: str) -> str:
    return "".join(c for c in (s or "").upper() if c.isalnum())


if not CFG.get("token"):
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    raw = "".join(secrets.choice(alphabet) for _ in range(16))
    CFG["token"] = "-".join(raw[i:i + 4] for i in range(0, 16, 4))
CFG.setdefault("sync_folders", [])


def save_cfg():
    tmp = CFG_PATH.with_suffix(".tmp")
    tmp.write_text(json.dumps(CFG, indent=2), encoding="utf-8")
    os.replace(tmp, CFG_PATH)


save_cfg()
TOKEN = norm_code(CFG["token"])

SHARED = Path(PATHS["shared_dir"]) if PATHS.get("shared_dir") else None
OUTPUT = Path(PATHS.get("output_dir") or (SHARED / "output" if SHARED else APP_DIR / "output"))


# ------------------------------------------------------------------ ComfyUI process

MODEL_KEYS = [
    "checkpoints", "classifiers", "clip_vision", "configs", "controlnet", "diffusers", "diffusion_models",
    "embeddings", "gligen", "hypernetworks", "latent_upscale_models", "loras", "model_patches",
    "audio_encoders", "photomaker", "style_models", "text_encoders", "upscale_models", "background_removal",
    "frame_interpolation", "geometry_estimation", "optical_flow", "detection", "vae", "vae_approx", "etc",
    "ipadapter", "clip", "unet",
]


def write_models_yaml() -> Path | None:
    if not SHARED:
        return None
    lines = ["comfy_desktop_shared:", f"  base_path: {SHARED.as_posix()}"]
    for k in MODEL_KEYS:
        if k == "controlnet":
            lines += ["  controlnet: |", "    models/controlnet", "    models/t2i_adapter"]
        else:
            lines.append(f"  {k}: models/{k}")
    p = APP_DIR / "models.yaml"
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return p


def supported_flags(comfy_dir: Path) -> set[str]:
    try:
        text = (comfy_dir / "comfy" / "cli_args.py").read_text(encoding="utf-8", errors="ignore")
        return set(re.findall(r"""add_argument\(\s*["'](--[a-z0-9-]+)""", text))
    except Exception:
        return {"--listen", "--port", "--output-directory", "--input-directory", "--extra-model-paths-config"}


class ComfyProcess:
    def __init__(self):
        self.proc: subprocess.Popen | None = None
        self.adopted_pid: int | None = None
        self.started_at = 0.0
        self.fails = 0
        self.last_error = ""
        self.enabled = bool(PATHS.get("python") and PATHS.get("comfy_dir"))
        self.restart_requested = False

    @property
    def pid_file(self):
        return APP_DIR / "comfy.pid"

    def build_cmd(self) -> list[str]:
        comfy_dir = Path(PATHS["comfy_dir"])
        flags = supported_flags(comfy_dir)
        cmd = [PATHS["python"], "-s", str(comfy_dir / "main.py")]

        def add(flag, *vals):
            if flag in flags:
                cmd.extend([flag, *vals])

        add("--listen", "127.0.0.1")
        add("--port", str(COMFY_PORT))
        if SHARED:
            add("--output-directory", str(SHARED / "output"))
            add("--input-directory", str(SHARED / "input"))
        y = write_models_yaml()
        if y:
            add("--extra-model-paths-config", str(y))
        if PATHS.get("cuda_device") not in (None, ""):
            add("--cuda-device", str(PATHS["cuda_device"]))
        add("--enable-manager")
        add("--enable-assets")
        add("--disable-auto-launch")
        for extra in PATHS.get("extra_args", []):
            cmd.append(extra)
        return cmd

    def _pid_alive(self, pid: int) -> bool:
        if IS_WIN:
            out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True,
                                 creationflags=NO_WINDOW).stdout
            return str(pid) in out
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False

    def _kill_pid(self, pid: int):
        try:
            if IS_WIN:
                subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True, creationflags=NO_WINDOW)
            else:
                os.kill(pid, 9)
        except Exception as e:
            log.warning("kill %s failed: %s", pid, e)

    def start(self):
        cmd = self.build_cmd()
        logf = APP_DIR / "comfyui.log"
        if logf.exists():
            try:
                os.replace(logf, APP_DIR / "comfyui.prev.log")
            except OSError:
                pass
        log.info("starting ComfyUI: %s", " ".join(cmd))
        fh = open(logf, "ab")
        self.proc = subprocess.Popen(
            cmd, cwd=PATHS["comfy_dir"], stdout=fh, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
            creationflags=NO_WINDOW,
        )
        self.started_at = time.time()
        self.pid_file.write_text(str(self.proc.pid))

    def managed_pid(self) -> int | None:
        if self.proc and self.proc.poll() is None:
            return self.proc.pid
        return self.adopted_pid

    def stop(self):
        pid = self.managed_pid()
        if pid:
            log.info("stopping ComfyUI pid %s", pid)
            self._kill_pid(pid)
        self.proc = None
        self.adopted_pid = None

    async def alive(self, session) -> bool:
        try:
            async with session.get(COMFY + "/system_stats", timeout=aiohttp.ClientTimeout(total=4)) as r:
                return r.status == 200
        except Exception:
            return False

    async def supervise(self, session):
        # Adopt a ComfyUI we started before the bridge was restarted.
        try:
            old = int(self.pid_file.read_text().strip())
            if self._pid_alive(old):
                self.adopted_pid = old
                log.info("adopted running ComfyUI pid %s", old)
        except Exception:
            pass

        while True:
            try:
                if self.restart_requested:
                    self.restart_requested = False
                    self.stop()
                    await asyncio.sleep(3)

                if await self.alive(session):
                    self.fails = 0
                    await asyncio.sleep(5)
                    continue

                if not self.enabled:
                    self.last_error = "No ComfyUI install configured"
                    await asyncio.sleep(10)
                    continue

                if self.proc and self.proc.poll() is None:
                    # Still loading.
                    if time.time() - self.started_at > 600:
                        self.last_error = "ComfyUI took over 10 minutes to start; restarting it"
                        log.warning(self.last_error)
                        self.stop()
                    await asyncio.sleep(2)
                    continue

                if self.adopted_pid and self._pid_alive(self.adopted_pid):
                    await asyncio.sleep(3)
                    continue
                self.adopted_pid = None

                if self.proc and self.proc.poll() is not None:
                    self.fails += 1
                    self.last_error = f"ComfyUI stopped (exit code {self.proc.returncode}); see comfyui.log"
                    log.warning(self.last_error)
                    self.proc = None
                    await asyncio.sleep(min(60, 5 * self.fails))

                self.start()
            except Exception as e:
                self.last_error = f"supervisor: {e}"
                log.exception("supervisor error")
                await asyncio.sleep(10)


COMFY_PROC = ComfyProcess()


# ------------------------------------------------------------------ security

def remote_allowed(remote: str | None) -> tuple[bool, bool]:
    """(allowed, is_local)"""
    if not remote:
        return False, False
    try:
        ip = ipaddress.ip_address(remote.split("%")[0])
    except ValueError:
        return False, False
    if getattr(ip, "ipv4_mapped", None):
        ip = ip.ipv4_mapped
    if ip.is_loopback:
        return True, True
    return any(ip in n for n in ALLOWED_NETS if ip.version == n.version), False


def has_token(request: web.Request) -> bool:
    given = request.headers.get("X-ISB-Token") or request.cookies.get("isb_token") or ""
    return hmac.compare_digest(norm_code(given), TOKEN)


@web.middleware
async def guard(request: web.Request, handler):
    ok, local = remote_allowed(request.remote)
    if not ok:
        log.warning("blocked %s %s", request.remote, request.path)
        return web.Response(status=403, text="Forbidden")
    if request.path.startswith("/isb/local/"):
        if not local:
            return web.Response(status=403, text="Forbidden")
        return await handler(request)
    if not local and not has_token(request):
        return web.json_response({"error": "bad or missing pairing code"}, status=401)
    return await handler(request)


# ------------------------------------------------------------------ image hand-off

def sync_folders() -> list[str]:
    return [f for f in CFG.get("sync_folders", []) if f and "/" not in f and "\\" not in f and f not in (".", "..")]


def resolve_synced(rel: str) -> Path | None:
    rel = rel.replace("\\", "/").lstrip("/")
    parts = rel.split("/")
    if len(parts) < 2 or parts[0] not in sync_folders() or any(p in ("", ".", "..") for p in parts):
        return None
    p = (OUTPUT / rel).resolve()
    root = (OUTPUT / parts[0]).resolve()
    if root not in p.parents:
        return None
    return p


async def h_status(request):
    alive = await COMFY_PROC.alive(request.app["session"])
    return web.json_response({
        "bridge": VERSION,
        "comfy_running": alive,
        "comfy_managed": COMFY_PROC.enabled,
        "comfy_starting": bool(COMFY_PROC.proc and COMFY_PROC.proc.poll() is None and not alive),
        "last_error": COMFY_PROC.last_error,
        "output_dir": str(OUTPUT),
        "sync_folders": sync_folders(),
    })


async def h_restart(request):
    COMFY_PROC.restart_requested = True
    return web.json_response({"ok": True})


def _list_folders():
    out = []
    if OUTPUT.is_dir():
        for d in sorted(OUTPUT.iterdir(), key=lambda p: p.name.lower()):
            if d.is_dir():
                n = sum(1 for f in d.rglob("*") if f.is_file() and f.suffix.lower() in MEDIA_EXT)
                out.append({"name": d.name, "count": n, "sync": d.name in sync_folders()})
    return out


async def h_folders(request):
    return web.json_response(await asyncio.to_thread(_list_folders))


async def h_get_config(request):
    return web.json_response({"sync_folders": sync_folders()})


async def h_set_config(request):
    data = await request.json()
    folders = data.get("sync_folders", [])
    if not isinstance(folders, list):
        return web.json_response({"error": "sync_folders must be a list"}, status=400)
    clean = []
    for f in folders:
        f = str(f)
        if f and "/" not in f and "\\" not in f and f not in (".", ".."):
            clean.append(f)
    CFG["sync_folders"] = clean
    save_cfg()
    log.info("sync folders now %s", clean)
    return web.json_response({"sync_folders": clean})


def _pending(limit=1000):
    now = time.time()
    files = []
    for name in sync_folders():
        root = OUTPUT / name
        if not root.is_dir():
            continue
        for f in root.rglob("*"):
            try:
                if not f.is_file() or f.suffix.lower() not in MEDIA_EXT:
                    continue
                st = f.stat()
                if now - st.st_mtime < 3:  # may still be being written
                    continue
                files.append({"path": f.relative_to(OUTPUT).as_posix(), "size": st.st_size, "mtime": st.st_mtime})
            except OSError:
                continue
    files.sort(key=lambda x: x["mtime"])
    return files[:limit], len(files)


async def h_pending(request):
    files, total = await asyncio.to_thread(_pending)
    return web.json_response({"files": files, "total": total})


async def h_file(request):
    p = resolve_synced(request.query.get("path", ""))
    if not p or not p.is_file():
        return web.json_response({"error": "not found"}, status=404)
    return web.FileResponse(p)


async def h_ack(request):
    data = await request.json()
    p = resolve_synced(str(data.get("path", "")))
    if not p:
        return web.json_response({"error": "bad path"}, status=400)
    if not p.exists():
        return web.json_response({"ok": True, "already": True})
    size = p.stat().st_size
    if int(data.get("size", -1)) != size:
        return web.json_response({"error": "size mismatch", "size": size}, status=409)
    try:
        p.unlink()
    except OSError as e:
        return web.json_response({"error": str(e)}, status=500)
    log.info("moved to phone: %s", p.relative_to(OUTPUT).as_posix())
    # Tidy empty sub-subfolders, but never the synced folder itself.
    top = (OUTPUT / p.relative_to(OUTPUT).parts[0]).resolve()
    d = p.parent
    while d != top and top in d.parents:
        try:
            d.rmdir()
        except OSError:
            break
        d = d.parent
    return web.json_response({"ok": True})


def tailscale_ip() -> str:
    exe = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Tailscale" / "tailscale.exe"
    for cand in ([str(exe)] if exe.exists() else []) + ["tailscale"]:
        try:
            out = subprocess.run([cand, "ip", "-4"], capture_output=True, text=True, timeout=10,
                                 creationflags=NO_WINDOW).stdout.strip().splitlines()
            if out:
                return out[0].strip()
        except Exception:
            continue
    return ""


async def h_local_info(request):
    ip = await asyncio.to_thread(tailscale_ip)
    alive = await COMFY_PROC.alive(request.app["session"])
    return web.json_response({"address": ip, "port": PORT, "code": CFG["token"], "comfy_running": alive,
                              "last_error": COMFY_PROC.last_error})


async def h_local_pair(request):
    ip = await asyncio.to_thread(tailscale_ip)
    html = f"""<!doctype html><meta name=viewport content="width=device-width">
<title>ImgSampah Bridge</title>
<body style="font-family:sans-serif;background:#121212;color:#eee;padding:24px">
<h2>Pair your phone</h2>
<p>In the app: <b>ComfyUI</b> &rarr; enter these.</p>
<p>Address: <b style="font-size:1.5em">{ip or "(Tailscale not running)"}</b></p>
<p>Pairing code: <b style="font-size:1.5em;letter-spacing:2px">{CFG['token']}</b></p>
<p style="color:#999">Keep the code private. ComfyUI on this PC: <a style="color:#8ab4f8" href="{COMFY}">{COMFY}</a></p>
</body>"""
    return web.Response(text=html, content_type="text/html")


# ------------------------------------------------------------------ proxy to ComfyUI

STARTING_HTML = """<!doctype html><meta name=viewport content="width=device-width">
<meta http-equiv="refresh" content="5"><body style="font-family:sans-serif;background:#121212;color:#eee;padding:24px">
<h2>ComfyUI is starting&hellip;</h2><p>This page refreshes by itself.</p></body>"""


def upstream_headers(request: web.Request) -> dict:
    headers = {}
    for k, v in request.headers.items():
        lk = k.lower()
        if lk in HOP or lk in ("cookie", "x-isb-token", "referer", "accept-encoding"):
            continue
        headers[k] = v
    if "Origin" in headers or "origin" in {k.lower() for k in headers}:
        for k in list(headers):
            if k.lower() == "origin":
                del headers[k]
        headers["Origin"] = COMFY
    headers["Host"] = f"127.0.0.1:{COMFY_PORT}"
    return headers


async def proxy(request: web.Request):
    if request.headers.get("Upgrade", "").lower() == "websocket":
        return await proxy_ws(request)
    session = request.app["session"]
    url = COMFY + request.raw_path
    try:
        async with session.request(
            request.method, url, headers=upstream_headers(request),
            data=request.content if request.body_exists else None, allow_redirects=False,
        ) as r:
            resp = web.StreamResponse(status=r.status, reason=r.reason)
            for k, v in r.headers.items():
                if k.lower() in HOP:
                    continue
                resp.headers.add(k, v)
            if r.headers.get("Content-Length"):
                resp.content_length = int(r.headers["Content-Length"])
            await resp.prepare(request)
            async for chunk in r.content.iter_chunked(65536):
                await resp.write(chunk)
            await resp.write_eof()
            return resp
    except aiohttp.ClientConnectorError:
        if request.method == "GET" and "text/html" in request.headers.get("Accept", ""):
            return web.Response(text=STARTING_HTML, content_type="text/html", status=503)
        return web.json_response({"error": "ComfyUI is not running yet"}, status=503)
    except (ConnectionResetError, asyncio.CancelledError):
        raise
    except Exception as e:
        log.warning("proxy error %s %s: %s", request.method, request.path, e)
        return web.json_response({"error": f"proxy: {e}"}, status=502)


async def proxy_ws(request: web.Request):
    ws = web.WebSocketResponse(max_msg_size=0, heartbeat=30)
    await ws.prepare(request)
    session = request.app["session"]
    try:
        async with session.ws_connect(COMFY_WS + request.raw_path, headers={"Origin": COMFY}, max_msg_size=0) as up:
            async def down_to_up():
                async for m in ws:
                    if m.type == aiohttp.WSMsgType.TEXT:
                        await up.send_str(m.data)
                    elif m.type == aiohttp.WSMsgType.BINARY:
                        await up.send_bytes(m.data)
                    else:
                        break

            async def up_to_down():
                async for m in up:
                    if m.type == aiohttp.WSMsgType.TEXT:
                        await ws.send_str(m.data)
                    elif m.type == aiohttp.WSMsgType.BINARY:
                        await ws.send_bytes(m.data)
                    else:
                        break

            tasks = [asyncio.create_task(down_to_up()), asyncio.create_task(up_to_down())]
            _, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for t in pending:
                t.cancel()
    except Exception as e:
        log.info("websocket closed: %s", e)
    await ws.close()
    return ws


# ------------------------------------------------------------------ app

async def on_startup(app):
    app["session"] = aiohttp.ClientSession(
        auto_decompress=False,
        cookie_jar=aiohttp.DummyCookieJar(),
        timeout=aiohttp.ClientTimeout(total=None, sock_connect=5),
        connector=aiohttp.TCPConnector(limit=64),
    )
    app["supervisor"] = asyncio.create_task(COMFY_PROC.supervise(app["session"]))


async def on_cleanup(app):
    app["supervisor"].cancel()
    await app["session"].close()


def make_app() -> web.Application:
    app = web.Application(middlewares=[guard], client_max_size=1024 ** 3)
    app.router.add_get("/isb/status", h_status)
    app.router.add_post("/isb/restart", h_restart)
    app.router.add_get("/isb/folders", h_folders)
    app.router.add_get("/isb/config", h_get_config)
    app.router.add_post("/isb/config", h_set_config)
    app.router.add_get("/isb/pending", h_pending)
    app.router.add_get("/isb/file", h_file)
    app.router.add_post("/isb/ack", h_ack)
    app.router.add_get("/isb/local/info", h_local_info)
    app.router.add_get("/isb/local/pair", h_local_pair)
    app.router.add_route("*", "/{tail:.*}", proxy)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    return app


async def main():
    runner = web.AppRunner(make_app(), access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", PORT, reuse_address=False)
    try:
        await site.start()
    except OSError as e:
        log.error("port %s busy (already running?): %s", PORT, e)
        print(f"Port {PORT} is busy - is the bridge already running?", file=sys.stderr)
        await runner.cleanup()
        return
    log.info("bridge %s listening on :%s, output=%s, sync=%s", VERSION, PORT, OUTPUT, sync_folders())
    while True:
        await asyncio.sleep(3600)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
