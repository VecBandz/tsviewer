#!/usr/bin/env python3
"""Append events to a session transcript (JSONL).

  tlog.py note  <session> <text>          free-form note
  tlog.py run   <session> -- <cmd...>     run a command, log cmd/output/exit code
  tlog.py ssh   <session> -- <cmd...>     same, but via ssh (edit SSH_ARGS below)
"""
import json, subprocess, sys, time, pathlib

SSH_ARGS = ["-p", "22", "localhost"]
LOGS = pathlib.Path(__file__).resolve().parent / "logs"

def emit(session, **ev):
    LOGS.mkdir(exist_ok=True)
    ev = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"), **ev}
    with open(LOGS / f"{session}.jsonl", "a") as f:
        f.write(json.dumps(ev) + "\n")

def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    kind, session = sys.argv[1], sys.argv[2]
    if kind == "note":
        emit(session, type="note", text=" ".join(sys.argv[3:]))
        return
    if kind not in ("run", "ssh") or "--" not in sys.argv:
        sys.exit(__doc__)
    cmd = sys.argv[sys.argv.index("--") + 1:]
    if not cmd:
        sys.exit(__doc__)
    if kind == "ssh":
        cmd = ["ssh", *SSH_ARGS, " ".join(cmd)]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True)
        out, err, code = p.stdout, p.stderr, p.returncode
    except FileNotFoundError:
        out, err, code = "", f"command not found: {cmd[0]}\n", 127
    emit(session, type="command", via=kind, cmd=" ".join(cmd),
         stdout=out, stderr=err, exit=code)
    sys.stdout.write(out); sys.stderr.write(err)
    sys.exit(code)

main()
