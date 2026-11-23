#!/usr/bin/env python3
"""Compiles Cura's fdmprinter/fdmextruder definitions into the app's settings table.

Usage: compile_defs.py <definitions dir> <out settings.json>
Output: {"version": ..., "settings": {key: {"t": type, "d": default, "v": formula}}}
where a formula is the Python expression pre-parsed into a small JSON tree, so the app
only has to evaluate it (see CuraSettings.kt):
  ["c", value]                     constant
  ["n", name]                      setting / variable
  ["a", obj, attr]                 attribute (math.sqrt)
  ["call", func, [args]]           call
  ["bin", op, left, right]         + - * / // % **
  ["un", op, operand]              - + not
  ["bool", "and"|"or", [values]]
  ["cmp", left, [ops], [rights]]   == != < <= > >= in "not in"
  ["if", test, then, else]
  ["list", [items]]
  ["sub", value, index]
  ["gen", element, var, iterable, [conditions]]
"""
import ast, json, sys

BIN = {ast.Add: '+', ast.Sub: '-', ast.Mult: '*', ast.Div: '/', ast.FloorDiv: '//', ast.Mod: '%', ast.Pow: '**'}
UN = {ast.USub: '-', ast.UAdd: '+', ast.Not: 'not'}
CMP = {ast.Eq: '==', ast.NotEq: '!=', ast.Lt: '<', ast.LtE: '<=', ast.Gt: '>', ast.GtE: '>=', ast.In: 'in', ast.NotIn: 'not in'}

def tree(n):
    if isinstance(n, ast.Expression): return tree(n.body)
    if isinstance(n, ast.Constant): return ['c', n.value]
    if isinstance(n, ast.Name): return ['n', n.id]
    if isinstance(n, ast.Attribute): return ['a', tree(n.value), n.attr]
    if isinstance(n, ast.Call):
        if n.keywords: raise ValueError('keyword arguments')
        return ['call', tree(n.func), [tree(a) for a in n.args]]
    if isinstance(n, ast.BinOp): return ['bin', BIN[type(n.op)], tree(n.left), tree(n.right)]
    if isinstance(n, ast.UnaryOp): return ['un', UN[type(n.op)], tree(n.operand)]
    if isinstance(n, ast.BoolOp): return ['bool', 'and' if isinstance(n.op, ast.And) else 'or', [tree(v) for v in n.values]]
    if isinstance(n, ast.Compare): return ['cmp', tree(n.left), [CMP[type(o)] for o in n.ops], [tree(c) for c in n.comparators]]
    if isinstance(n, ast.IfExp): return ['if', tree(n.test), tree(n.body), tree(n.orelse)]
    if isinstance(n, (ast.List, ast.Tuple, ast.Set)): return ['list', [tree(e) for e in n.elts]]
    if isinstance(n, ast.Subscript): return ['sub', tree(n.value), tree(n.slice)]
    if isinstance(n, (ast.GeneratorExp, ast.ListComp)):
        if len(n.generators) != 1 or not isinstance(n.generators[0].target, ast.Name): raise ValueError('complex comprehension')
        g = n.generators[0]
        return ['gen', tree(n.elt), g.target.id, tree(g.iter), [tree(i) for i in g.ifs]]
    raise ValueError(f'unsupported {type(n).__name__}')

def main():
    defs_dir, out = sys.argv[1], sys.argv[2]
    table, version = {}, None
    for f in ('fdmprinter.def.json', 'fdmextruder.def.json'):
        doc = json.load(open(f'{defs_dir}/{f}'))
        version = version or doc.get('version')
        def walk(d):
            for k, v in d.items():
                if v.get('type') != 'category' and k not in table:
                    e = {'t': v.get('type')}
                    if 'default_value' in v: e['d'] = v['default_value']
                    if 'value' in v:
                        val = v['value']
                        if isinstance(val, str):
                            try:
                                e['v'] = tree(ast.parse(val, mode='eval'))
                            except Exception as ex:
                                print(f'skip formula {k}: {ex}', file=sys.stderr)
                        else:
                            e['v'] = ['c', val]
                    table[k] = e
                if 'children' in v: walk(v['children'])
        walk(doc['settings'])
    json.dump({'version': version, 'settings': table}, open(out, 'w'), separators=(',', ':'))
    print(f'{len(table)} settings -> {out}')

if __name__ == '__main__':
    main()
