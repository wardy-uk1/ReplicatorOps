#!/usr/bin/env python3
"""Checks the app's Kotlin resolver against resolve.py (Cura's formulas run by Python).
Usage: compare.py <defs dir> <cura-check dir>  (uses <name>.overrides.json / <name>.kotlin.json)"""
import glob, json, math, os, subprocess, sys

defs, check = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
bad = 0
names = sorted(p[:-len('.overrides.json')] for p in glob.glob(f'{check}/*.overrides.json'))
if not names: sys.exit('no overrides found')

def same(a, b):
    if isinstance(a, bool) or isinstance(b, bool): return a == b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-9) or (math.isnan(a) and math.isnan(b))
    if isinstance(a, list) and isinstance(b, list): return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    return a == b

for n in names:
    py = f'{n}.python.json'
    subprocess.run([sys.executable, '-I', f'{here}/resolve.py', defs, f'{n}.overrides.json', py, '--values'], check=True, stdout=subprocess.DEVNULL)
    p, k = json.load(open(py)), json.load(open(f'{n}.kotlin.json'))
    diffs = [key for key in p if not same(p[key], k.get(key))]
    for key in diffs[:30]: print(f'{os.path.basename(n)}: {key}: python={p[key]!r} kotlin={k.get(key)!r}')
    print(f'{os.path.basename(n)}: {len(p)} settings, {len(diffs)} differ')
    bad += len(diffs)
sys.exit(1 if bad else 0)
