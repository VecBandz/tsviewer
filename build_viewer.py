#!/usr/bin/env python3
"""Bundle logs/*.jsonl into one self-contained viewer.html and open it.

On Android, browsers can't read files inside Termux's private folder, so the
page is served on http://127.0.0.1:8765 (local only) and opened from there.

  python build_viewer.py            build, then open in the browser
  python build_viewer.py --no-open  build only
"""
import json, pathlib, shutil, socket, subprocess, sys, time

PORT = 8765
root = pathlib.Path(__file__).resolve().parent
(root / "logs").mkdir(exist_ok=True)
sessions = {p.stem: [json.loads(l) for l in p.read_text().splitlines() if l.strip()]
            for p in sorted((root / "logs").glob("*.jsonl"))}
data = json.dumps(sessions).replace("</", "<\\/")
page = (root / "TSViewer.html").read_text().replace("__DATA__", data)
(root / "viewer.html").write_text(page)
print(f"viewer.html: {len(sessions)} session(s), {sum(map(len, sessions.values()))} events")

if "--no-open" in sys.argv:
    sys.exit()

def serving():
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", PORT)) == 0

opener = shutil.which("termux-open-url") or shutil.which("xdg-open") or shutil.which("open")
if not opener:
    print(f"Open {root / 'viewer.html'} in a browser.")
    sys.exit()
if not serving():
    subprocess.Popen([sys.executable, "-m", "http.server", str(PORT), "--bind", "127.0.0.1"],
                     cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)
    for _ in range(20):
        if serving():
            break
        time.sleep(0.1)
url = f"http://127.0.0.1:{PORT}/viewer.html?t={int(time.time())}"
print(f"Opening {url}")
subprocess.run([opener, url])
