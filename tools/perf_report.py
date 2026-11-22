"""Analyse d'un bench Paranoia (rapport .txt + enregistrement .jfr).

Usage : python tools/perf_report.py <bench-xxx.jfr> [top]

Sortie :
  - lignes FPS / frametimes du rapport texte (mesure sans profileur) ;
  - part du thread de rendu passée dans du code Paranoia (échantillons CPU, 2 ms) ;
  - coût par point d'entrée Paranoia (inclusif) et méthodes Paranoia les plus chaudes (exclusif) ;
  - allocations totales et attribuées à Paranoia (Mo/s), par point d'entrée ;
  - GC.
"""
import collections
import json
import os
import subprocess
import sys


def frames_of(ev):
    st = ev["values"].get("stackTrace")
    if not st:
        return []
    out = []
    for f in st["frames"]:
        m = f["method"]
        t = m["type"]["name"].replace("/", ".")
        out.append(t + "." + m["name"])
    return out  # du plus profond au plus externe


def is_aurora(name):
    return name.startswith("gg.aurora.") or "aurora$" in name or "$aurora" in name


def short(name):
    return name.replace("gg.aurora.", "")


def jfr_json(path, events):
    raw = subprocess.run(["jfr", "print", "--json", "--stack-depth", "96", "--events", events, path],
                         capture_output=True, check=True).stdout
    return json.loads(raw.decode("utf8", "replace"))["recording"]["events"]


def main():
    path = sys.argv[1]
    top = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    txt = path[:-4] + ".txt"
    if os.path.exists(txt):
        for line in open(txt, encoding="utf8"):
            if line.startswith(("FPS", "Images", "GC", "Modules", "Cosm")):
                print(line.rstrip())
    ev = jfr_json(path, "jdk.ExecutionSample")
    render = [e for e in ev if e["values"]["sampledThread"].get("javaName") == "Render thread"]
    n = len(render)
    entry = collections.Counter()
    selfc = collections.Counter()
    au = 0
    for e in render:
        fr = frames_of(e)
        a = [x for x in fr if is_aurora(x)]
        if a:
            au += 1
            entry[short(a[-1])] += 1  # point d'entrée = frame Paranoia la plus externe
        # exclusif : la méthode Paranoia la plus profonde (le travail fait « chez nous » ou appelé par nous)
        if a:
            selfc[short(a[0])] += 1
    print(f"\nCPU thread de rendu : {n} échantillons, Paranoia {au} ({100 * au / max(1, n):.1f} %)")
    print("  Inclusif par point d'entrée :")
    for k, v in entry.most_common(top):
        print(f"    {100 * v / max(1, n):5.2f} %  {k}")
    print("  Méthode Paranoia la plus profonde :")
    for k, v in selfc.most_common(top):
        print(f"    {100 * v / max(1, n):5.2f} %  {k}")

    al = jfr_json(path, "jdk.ObjectAllocationSample")
    if al:
        t0 = min(e["values"]["startTime"] for e in al)
        t1 = max(e["values"]["startTime"] for e in al)
    tot = 0
    aur = 0
    by = collections.Counter()
    types = collections.Counter()
    for e in al:
        w = e["values"].get("weight", 0)
        tot += w
        fr = frames_of(e)
        a = [x for x in fr if is_aurora(x)]
        if a:
            aur += w
            by[short(a[-1])] += w
            types[(e["values"]["objectClass"]["name"], short(a[0]))] += w
    secs = 20.0
    print(f"\nAllocations : {tot / secs / 1048576:.1f} Mo/s au total, Paranoia {aur / secs / 1048576:.2f} Mo/s"
          f" ({100 * aur / max(1, tot):.1f} %)")
    for k, v in by.most_common(top):
        print(f"    {v / secs / 1048576:6.3f} Mo/s  {k}")
    print("  Types alloués (par méthode Paranoia) :")
    for (t, m), v in types.most_common(top):
        print(f"    {v / secs / 1048576:6.3f} Mo/s  {t}  <- {m}")

    gc = jfr_json(path, "jdk.GarbageCollection")
    pauses = [e["values"].get("sumOfPauses", 0) for e in gc]
    print(f"\nGC : {len(gc)} collections pendant le JFR")


if __name__ == "__main__":
    main()
