#!/usr/bin/env python3
"""Bundle logs/*.jsonl into one self-contained viewer.html and open it."""
import json, pathlib, shutil, subprocess
root = pathlib.Path(__file__).resolve().parent
(root / "logs").mkdir(exist_ok=True)
sessions = {p.stem: [json.loads(l) for l in p.read_text().splitlines() if l.strip()]
            for p in sorted((root / "logs").glob("*.jsonl"))}
data = json.dumps(sessions).replace("</", "<\\/")
page = (root / "viewer_template.html").read_text().replace("__DATA__", data)
(root / "viewer.html").write_text(page)
print(f"viewer.html: {len(sessions)} session(s), {sum(map(len, sessions.values()))} events")
if shutil.which("termux-open"):
    subprocess.run(["termux-open", str(root / "viewer.html")])
