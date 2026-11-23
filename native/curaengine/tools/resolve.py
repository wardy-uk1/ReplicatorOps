#!/usr/bin/env python3
"""Reference resolver for Cura setting formulas (single extruder).

Usage: resolve.py <definitions dir> <overrides.json> <out resolved.json>
Evaluates every setting's "value" formula the way Cura's front end does, with the
overrides fixed, and writes a flat definition CuraEngine can load with -j.
Used in CI to check the app's Kotlin resolver gives the same answers."""
import json, math, sys

def flatten(defs_dir):
    out = {}
    for f in ('fdmprinter.def.json', 'fdmextruder.def.json'):
        doc = json.load(open(f'{defs_dir}/{f}'))
        def walk(d):
            for k, v in d.items():
                if v.get('type') != 'category':
                    out.setdefault(k, v)
                if 'children' in v:
                    walk(v['children'])
        walk(doc['settings'])
    return out

def typed(t, v):
    if v is None: return None
    if t == 'float': return float(v)
    if t == 'int': return int(float(v)) if isinstance(v, str) else int(v)
    if t == 'bool': return bool(v)
    if t in ('extruder', 'optional_extruder'): return str(v)
    return v

class Resolver:
    def __init__(self, defs, overrides):
        self.defs, self.over, self.cache, self.stack = defs, overrides, {}, set()

    def get(self, key):
        if key in self.cache: return self.cache[key]
        d = self.defs.get(key)
        if d is None: raise KeyError(key)
        if key in self.over:
            v = typed(d.get('type'), self.over[key])
        elif 'value' in d and key not in self.stack:
            self.stack.add(key)
            try:
                v = typed(d.get('type'), self.eval(d['value']) if isinstance(d['value'], str) else d['value'])
            except Exception:
                v = typed(d.get('type'), d.get('default_value'))
            finally:
                self.stack.discard(key)
        else:
            v = typed(d.get('type'), d.get('default_value'))
        self.cache[key] = v
        return v

    def eval(self, expr):
        r = self
        class Env(dict):
            def __missing__(self, name):
                if name in r.defs: return r.get(name)
                raise NameError(name)
        env = Env(math=math, max=max, min=min, round=round, sum=sum, len=len, any=any, all=all, map=map,
                  int=int, float=float, str=str, abs=abs, bool=bool,
                  resolveOrValue=lambda k: r.get(k),
                  extruderValue=lambda i, k: r.get(k),
                  extruderValues=lambda k: [r.get(k)],
                  defaultExtruderPosition=lambda: '0',
                  anyExtruderWithMaterial=lambda k: False,
                  anyExtruderNrWithOrDefault=lambda k: '0',
                  valueFromContainer=lambda *a: 0)
        return eval(expr, {'__builtins__': {}}, env)

def main():
    defs = flatten(sys.argv[1])
    over = json.load(open(sys.argv[2]))
    r = Resolver(defs, over)
    resolved = {}
    for k in defs:
        try:
            resolved[k] = r.get(k)
        except Exception as e:
            print(f'warn: {k}: {e}', file=sys.stderr)
    if len(sys.argv) > 4 and sys.argv[4] == '--values':
        json.dump(resolved, open(sys.argv[3], 'w'), indent=0, sort_keys=True)
        print(len(resolved), 'values'); return
    doc = {'version': 2, 'name': 'resolved', 'metadata': {}, 'settings': {'resolved': {'type': 'category', 'children':
           {k: {'type': defs[k].get('type'), 'default_value': v} for k, v in resolved.items() if v is not None}}}}
    json.dump(doc, open(sys.argv[3], 'w'), indent=0)
    print(len(resolved), 'settings')

if __name__ == '__main__':
    main()
