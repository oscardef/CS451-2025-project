#!/usr/bin/env python3
import argparse, os, subprocess, sys

HERE = os.path.abspath(os.path.dirname(__file__))
STRESS = os.path.abspath(os.path.join(HERE, "stress.py"))  # reuse existing tool
VERIFY = os.path.abspath(os.path.join(HERE, "verify_pl_outputs.py"))

def run_scenario(runscript, logsdir, p, m):
    print(f"\n=== Scenario: processes={p}, messages={m} ===")
    # Use stress.py to generate hosts+config and run processes
    cmd = [
        sys.executable, STRESS, "perfect",
        "-r", runscript,
        "-l", logsdir,
        "-p", str(p),
        "-m", str(m),
    ]
    print("Running:", " ".join(cmd))
    ret = subprocess.run(cmd)
    if ret.returncode != 0:
        print(f"[ERR] stress.py returned {ret.returncode}; aborting this scenario.")
        return ret.returncode

    # stress.py writes files into logsdir: hosts + config
    hosts = os.path.join(logsdir, "hosts")
    config = os.path.join(logsdir, "config")

    # Now verify outputs
    vcmd = [sys.executable, VERIFY, "--hosts", hosts, "--config", config, "--outdir", logsdir]
    print("Verifying:", " ".join(vcmd))
    vret = subprocess.run(vcmd)
    return vret.returncode

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runscript", required=True, help="Path to ./run.sh")
    ap.add_argument("--logsdir", required=True, help="Directory where stress.py stores outputs")
    ap.add_argument("--processes", nargs="+", type=int, required=True, help="List of process counts to test, e.g. 3 5")
    ap.add_argument("--messages", nargs="+", type=int, required=True, help="List of message counts to test, e.g. 10 100 1000")
    args = ap.parse_args()

    # sanity
    if not os.path.isfile(args.runscript):
        print(f"[ERR] runscript {args.runscript} not found", file=sys.stderr); sys.exit(1)
    if not os.path.isdir(args.logsdir):
        print(f"[ERR] logsdir {args.logsdir} not found", file=sys.stderr); sys.exit(1)
    if not os.path.isfile(STRESS):
        print(f"[ERR] stress.py not found next to this tool", file=sys.stderr); sys.exit(1)
    if not os.path.isfile(VERIFY):
        print(f"[ERR] verify_pl_outputs.py not found next to this tool", file=sys.stderr); sys.exit(1)

    code=0
    for p in args.processes:
        for m in args.messages:
            rc = run_scenario(args.runscript, args.logsdir, p, m)
            if rc != 0: code = rc
    sys.exit(code)

if __name__ == "__main__":
    main()
