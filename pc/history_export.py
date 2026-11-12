"""
Builds run_history.csv: one row per ComfyUI run (job), from
  * ComfyUI's asset database (every image ever saved: time, file, job id),
  * the settings and prompts saved inside each image that is still on the PC,
  * exact run times from the speed tests and from ComfyUI's in-memory history.
Run by the ImgSampah Bridge when it finds history_export_request.json.
"""
import csv
import ntpath
import datetime as dt
import json
import sqlite3
import struct
import urllib.request
import zlib
from pathlib import Path

PNG_SIG = b"\x89PNG\r\n\x1a\n"


def png_text(path: Path) -> dict:
    out = {}
    try:
        with open(path, "rb") as f:
            if f.read(8) != PNG_SIG:
                return out
            while True:
                head = f.read(8)
                if len(head) < 8:
                    break
                n, typ = struct.unpack(">I4s", head)
                if typ in (b"tEXt", b"iTXt", b"zTXt"):
                    data = f.read(n)
                    k, _, rest = data.partition(b"\0")
                    key = k.decode("latin-1")
                    if typ == b"tEXt":
                        out[key] = rest.decode("latin-1")
                    elif typ == b"zTXt":
                        out[key] = zlib.decompress(rest[1:]).decode("latin-1")
                    else:
                        comp = rest[0]
                        rest = rest[2:]
                        _, _, rest = rest.partition(b"\0")
                        _, _, rest = rest.partition(b"\0")
                        out[key] = (zlib.decompress(rest) if comp else rest).decode("utf-8", "replace")
                    f.seek(4, 1)
                elif typ in (b"IDAT", b"IEND"):
                    break
                else:
                    f.seek(n + 4, 1)
    except Exception:
        pass
    return out


def settings_from_prompt(prompt: dict) -> dict:
    s = {}
    if not isinstance(prompt, dict):
        return s

    def node(ref):
        return prompt.get(str(ref[0])) if isinstance(ref, list) and ref else None

    def text_of(ref, depth=0):
        n = node(ref)
        if not n or depth > 6:
            return ""
        i = n.get("inputs", {})
        if isinstance(i.get("text"), str):
            return i["text"]
        for v in i.values():
            if isinstance(v, list):
                t = text_of(v, depth + 1)
                if t:
                    return t
        return ""

    loras = []
    for n in prompt.values():
        ct, i = n.get("class_type", ""), n.get("inputs", {})
        if ct in ("CheckpointLoaderSimple", "CheckpointLoader") and "ckpt_name" in i:
            s["model"] = i["ckpt_name"]
        elif ct in ("UNETLoader",) and "unet_name" in i:
            s.setdefault("model", i["unet_name"])
        elif ct.startswith("LoraLoader") and "lora_name" in i:
            loras.append(f"{i['lora_name']} ({i.get('strength_model', '')})")
        elif ct in ("KSampler", "KSamplerAdvanced"):
            s.update(sampler=i.get("sampler_name"), scheduler=i.get("scheduler"), steps=i.get("steps"), cfg=i.get("cfg"),
                     seed=i.get("seed", i.get("noise_seed")), denoise=i.get("denoise"))
            s["positive_prompt"] = text_of(i.get("positive"))
            s["negative_prompt"] = text_of(i.get("negative"))
        elif ct == "SamplerCustom":
            s.update(cfg=i.get("cfg"), seed=i.get("noise_seed"))
            s["positive_prompt"] = text_of(i.get("positive"))
            s["negative_prompt"] = text_of(i.get("negative"))
        elif ct == "KSamplerSelect":
            s["sampler"] = i.get("sampler_name")
        elif ct == "AlignYourStepsScheduler":
            s.update(scheduler="align_your_steps", steps=i.get("steps"), denoise=i.get("denoise"))
        elif ct in ("EmptyLatentImage", "EmptySD3LatentImage"):
            s.update(width=i.get("width"), height=i.get("height"), batch_size=i.get("batch_size"))
        elif ct == "SaveImage":
            s["filename_prefix"] = i.get("filename_prefix")
    if loras:
        s["loras"] = "; ".join(loras)
    return s


def export(app_dir: Path, comfy_dir: Path, comfy_url: str, out_csv: Path) -> dict:
    db = comfy_dir / "user" / "comfyui.db"
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=30)
    rows = con.execute("""
        select a.job_id, a.name, a.created_at, ac.path, ac.is_missing, a.system_metadata
        from assets a join asset_contents ac on ac.id = a.content_id
        join asset_tags t on t.asset_id = a.id and t.tag_name = 'output'
        where a.job_id is not null order by a.created_at""").fetchall()
    con.close()

    jobs = {}
    for job_id, name, created, path, missing, meta in rows:
        j = jobs.setdefault(job_id, {"files": [], "created": [], "present": []})
        j["files"].append(path or name)
        j["created"].append(created)
        if path and not missing and Path(path).exists():
            j["present"].append(Path(path))

    # saved workflow names by their id
    wf_names = {}
    wdir = comfy_dir / "user" / "default" / "workflows"
    for f in wdir.rglob("*.json"):
        try:
            wid = json.loads(f.read_text(encoding="utf-8")).get("id")
            if wid:
                wf_names.setdefault(wid, f.relative_to(wdir).as_posix()[:-5])
        except Exception:
            pass

    # exact times: speed tests, then ComfyUI's in-memory history
    exact = {}
    for d in app_dir.iterdir():
        st = d / "state.json"
        if d.is_dir() and st.exists():
            try:
                s = json.loads(st.read_text(encoding="utf-8-sig"))
                for r in s.get("results", []):
                    if r.get("prompt_id") and r.get("seconds"):
                        exact[r["prompt_id"]] = (r["seconds"], f"speed test ({d.name}) as {r.get('wf')}", r.get("wf"))
            except Exception:
                pass
    try:
        with urllib.request.urlopen(comfy_url + "/history?max_items=100000", timeout=60) as r:
            hist = json.loads(r.read())
        for pid, h in hist.items():
            st, en = None, None
            for m in (h.get("status") or {}).get("messages") or []:
                if m[0] == "execution_start":
                    st = m[1].get("timestamp")
                elif m[0] in ("execution_success", "execution_error", "execution_interrupted"):
                    en = m[1].get("timestamp")
            if st and en and pid not in exact:
                exact[pid] = (round((en - st) / 1000, 2), "ComfyUI history", None)
    except Exception:
        pass

    def parse_t(s):
        return dt.datetime.fromisoformat(s).replace(tzinfo=dt.timezone.utc)

    order = sorted(jobs.items(), key=lambda kv: min(kv[1]["created"]))
    out = []
    prev_end = None
    for job_id, j in order:
        start_t, end_t = parse_t(min(j["created"])), parse_t(max(j["created"]))
        meta, wf_name, src = {}, "", ""
        for p in j["present"][:1]:
            t = png_text(p)
            try:
                meta = settings_from_prompt(json.loads(t.get("prompt", "{}")))
            except Exception:
                meta = {}
            try:
                wid = json.loads(t["workflow"]).get("id") if "workflow" in t else None
                if wid and wid in wf_names:
                    wf_name, src = wf_names[wid], "saved workflow"
            except Exception:
                pass
            if not wf_name and "workflow" not in t and "prompt" in t:
                wf_name, src = "(sent by a program, e.g. Hermes)", "no workflow in image"
        secs, ssrc = None, "unknown"
        if job_id in exact:
            secs, ssrc, test_wf = exact[job_id]
            if test_wf and not wf_name:
                wf_name, src = test_wf, "speed test"
        elif prev_end is not None:
            gap = (end_t - prev_end).total_seconds()
            if 0 < gap < 1800:
                secs, ssrc = round(gap, 1), "estimated (time since previous run's images)"
        prev_end = end_t
        first = j["files"][0] or ""
        low = first.replace("/", "\\")
        cut = low.lower().rfind("\\output\\")
        rel = low[cut + 8:] if cut >= 0 else ntpath.basename(low)
        folder = ntpath.dirname(rel).replace("\\", "/")
        local = end_t.astimezone().strftime("%Y-%m-%d %H:%M:%S")
        out.append({
            "finished_local": local, "date": local[:10], "time": local[11:], "workflow": wf_name or "(unknown)", "workflow_source": src or "image not on PC",
            "seconds": secs, "minutes": round(secs / 60, 2) if secs else None, "time_source": ssrc,
            "images": len(j["files"]), "images_still_on_pc": len(j["present"]), "output_folder": folder or "(output root)", "first_file": ntpath.basename(rel),
            "model": meta.get("model"), "loras": meta.get("loras"), "sampler": meta.get("sampler"), "scheduler": meta.get("scheduler"),
            "steps": meta.get("steps"), "cfg": meta.get("cfg"), "seed": meta.get("seed"), "denoise": meta.get("denoise"),
            "width": meta.get("width"), "height": meta.get("height"), "batch_size": meta.get("batch_size"), "filename_prefix": meta.get("filename_prefix"),
            "positive_prompt": meta.get("positive_prompt"), "negative_prompt": meta.get("negative_prompt"), "job_id": job_id,
        })
    out.reverse()  # newest first
    cols = list(out[0].keys()) if out else ["finished_local"]
    tmp = out_csv.with_suffix(".tmp")
    with open(tmp, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        w.writerows(out)
    tmp.replace(out_csv)
    return {"runs": len(out), "with_settings": sum(1 for r in out if r["model"]), "exact_times": sum(1 for r in out if r["time_source"] not in ("unknown",) and not r["time_source"].startswith("estimated")),
            "estimated_times": sum(1 for r in out if r["time_source"].startswith("estimated")), "file": str(out_csv)}
