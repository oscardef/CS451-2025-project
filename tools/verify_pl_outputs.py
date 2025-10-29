#!/usr/bin/env python3
import argparse, os, re, sys

LINE_RE_B = re.compile(r'^b\s+(\d+)\s*$')
LINE_RE_D = re.compile(r'^d\s+(\d+)\s+(\d+)\s*$')

def parse_hosts(path):
    # lines: "<id> <host> <port>"
    ids = []
    with open(path, 'r') as f:
        for line in f:
            line=line.strip()
            if not line: continue
            parts=line.split()
            if len(parts) < 3: raise ValueError(f"Bad hosts line: {line}")
            pid=int(parts[0])
            ids.append(pid)
    ids.sort()
    return ids

def parse_config(path):
    # "m i" for perfect links
    with open(path, 'r') as f:
        txt = f.read().strip().split()
    if len(txt) < 2:
        raise ValueError(f"Config {path} must contain two integers: m i")
    m=int(txt[0]); receiver=int(txt[1])
    return m, receiver

def find_output_files(outdir):
    files=[]
    for name in os.listdir(outdir):
        if name.endswith(".output"):
            files.append(os.path.join(outdir,name))
    if not files:
        raise FileNotFoundError(f"No .output files in {outdir}")
    return sorted(files)

def pid_from_filename(fname):
    base=os.path.basename(fname)
    # stress.py style: procNN.output
    m=re.match(r'^proc(\d+)\.output$', base)
    if m: return int(m.group(1))
    # direct style: N.output
    m=re.match(r'^(\d+)\.output$', base)
    if m: return int(m.group(1))
    return None  # unknown pattern

def file_ends_with_newline(path):
    with open(path, 'rb') as f:
        try:
            f.seek(-1, os.SEEK_END)
        except OSError:
            return True  # empty file; treat as ok
        return f.read(1) == b'\n'

def read_output(path):
    lines=[]
    with open(path, 'r') as f:
        for raw in f:
            line=raw.rstrip('\n')
            lines.append(line)
    return lines

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--hosts', required=True)
    ap.add_argument('--config', required=True)
    ap.add_argument('--outdir', required=True)
    args=ap.parse_args()

    all_pids=parse_hosts(args.hosts)
    m, receiver=parse_config(args.config)
    outs=find_output_files(args.outdir)

    # Map pid -> file path (best effort)
    pid2file={}
    unknown_files=[]
    for p in outs:
        pid=pid_from_filename(p)
        if pid is not None:
            pid2file[pid]=p
        else:
            unknown_files.append(p)

    # If some files have unknown names, just keep them aside (won't break validation).
    missing=[pid for pid in all_pids if pid not in pid2file]
    if missing:
        print(f"[WARN] Missing output files for pids: {missing}", file=sys.stderr)

    ok=True
    receiver_file = pid2file.get(receiver)
    if not receiver_file:
        print(f"[ERR] Receiver pid {receiver} has no output file", file=sys.stderr)
        ok=False

    # Parse all files into events
    b_counts = {}          # pid -> set of seqs broadcast
    d_counts = {}          # (receiver pid) -> { sender -> set of seqs delivered }
    malformed = []

    for pid, path in pid2file.items():
        lines = read_output(path)
        if not file_ends_with_newline(path):
            print(f"[WARN] {os.path.basename(path)} does not end with newline; grader may reject last line.", file=sys.stderr)

        has_b=False; has_d=False
        for i, line in enumerate(lines, start=1):
            mB=LINE_RE_B.match(line)
            mD=LINE_RE_D.match(line)
            if mB:
                has_b=True
                seq=int(mB.group(1))
                b_counts.setdefault(pid, set()).add(seq)
            elif mD:
                has_d=True
                sender=int(mD.group(1)); seq=int(mD.group(2))
                d_counts.setdefault(pid, {}).setdefault(sender, set()).add(seq)
            else:
                malformed.append((pid, path, i, line))

        # Quick sanity: receiver should not broadcast in this milestone design
        if pid == receiver and has_b:
            print(f"[ERR] Receiver pid {receiver} has 'b' lines in {os.path.basename(path)} — expected only deliveries", file=sys.stderr)
            ok=False

        # Senders should not deliver app messages (ACKs are not logged)
        if pid != receiver and has_d:
            print(f"[ERR] Sender pid {pid} has 'd' lines in {os.path.basename(path)} — expected only broadcasts", file=sys.stderr)
            ok=False

    # Malformed lines
    if malformed:
        ok=False
        for pid, path, ln, content in malformed:
            print(f"[ERR] Malformed line in {os.path.basename(path)} [pid {pid}] line {ln}: '{content}'", file=sys.stderr)

    # Validate senders' broadcasts: exactly m broadcasts with seq 1..m, no duplicates
    for pid in all_pids:
        if pid == receiver: continue
        seqs = b_counts.get(pid, set())
        if len(seqs) != m or seqs != set(range(1, m+1)):
            print(f"[ERR] Sender pid {pid}: expected broadcasts 1..{m}, got {len(seqs)} unique seqs; missing={sorted(set(range(1,m+1))-seqs)[:10]}", file=sys.stderr)
            ok=False

    # Validate receiver deliveries: for each sender j != receiver, exactly m deliveries with seq 1..m
    if receiver_file:
        rec_map = d_counts.get(receiver, {})
        for sender in all_pids:
            if sender == receiver: continue
            seqs = rec_map.get(sender, set())
            if len(seqs) != m or seqs != set(range(1, m+1)):
                print(f"[ERR] Receiver pid {receiver}: from sender {sender} expected deliveries 1..{m}, got {len(seqs)}; missing={sorted(set(range(1,m+1))-seqs)[:10]}", file=sys.stderr)
                ok=False

    # Final summary
    if ok:
        print("[OK] Perfect Links outputs look correct:")
        print(f"     - Senders broadcasted exactly 1..{m}")
        print(f"     - Receiver {receiver} delivered exactly 1..{m} from each sender")
        if unknown_files:
            print(f"     - Ignored unknown named files: {[os.path.basename(x) for x in unknown_files]}")
        sys.exit(0)
    else:
        print("[FAIL] Output validation failed", file=sys.stderr)
        sys.exit(2)

if __name__ == '__main__':
    main()
