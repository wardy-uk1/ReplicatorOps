// Small fixes to Kiri:Moto, applied to the grid-apps source before it is built for the beta app.
//
// 1. A pool worker ("minion") that hits an error used to log it and never reply, so the
//    slice waited forever (seen on phones as "stuck at 71%" during the layer-delta step).
//    Now it replies with the error, and the layer-delta subtract falls back to working locally.
// 2. The number of pool workers can be set with ?pool=N on the worker URL, so phones can use
//    fewer workers (each loads its own WebAssembly, which is heavy on memory).
// 3. ?fault=sub on the pool URL makes every minion subtract fail, so CI can prove the fallback.
import fs from 'fs';

const root = process.argv[2] || 'grid-apps';
function patch(file, pairs) {
    const path = `${root}/${file}`;
    let s = fs.readFileSync(path, 'utf8');
    for (const [from, to] of pairs) {
        if (!s.includes(from)) { console.error(`patch-kiri: not found in ${file}:\n${from}`); process.exit(1); }
        s = s.replace(from, to);
    }
    fs.writeFileSync(path, s);
    console.log(`patch-kiri: ${file} (${pairs.length})`);
}

patch('src/kiri/run/minion.js', [
    [`        log('MINION.dispatch.error', error);`,
     `        log('MINION.dispatch.error', error);
        if (data && data.seq !== undefined) reply({ seq: data.seq, error: String(error && error.message || error) });`],
    [`    subtract(data, seq) {
        let { arg, opt } = data;`,
     `    subtract(data, seq) {
        if (/[?&]fault=sub/.test(self.location.search)) throw new Error('injected minion fault (self-test)');
        let { arg, opt } = data;`],
]);

patch('src/kiri/run/worker.js', [
    [`concurrent = Math.round(Math.max(4, self.Worker && ccvalue > 3 ? ccvalue * 0.75 : 0)),`,
     `concurrent = Number(new URLSearchParams(self.location.search).get('pool')) || Math.round(Math.max(4, self.Worker && ccvalue > 3 ? ccvalue * 0.75 : 0)),`],
    [`    delete minifns[seq];
    fn(data);`,
     `    delete minifns[seq];
    if (data.error) console.log('kiri | minion error:', data.error);
    fn(data);`],
    [`            }, result => {
                if (outA) outA.push(...codec.decode(result.outA));`,
     `            }, result => {
                if (result.error) {
                    POLY.subtract(a, b, outA, outB, z, area, { wasm: false });
                    resolve();
                    return;
                }
                if (outA) outA.push(...codec.decode(result.outA));`],
]);
