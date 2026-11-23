// CI self-test for the Beta's on-phone slicer: serves the app's beta assets the way
// the app does (one origin, /kiri, /wasm, /slicer) and slices a 20 mm cube in
// headless Chrome with the app's own Q2C settings. Fails the build on any error.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(path.join(process.cwd(), 'package.json'));
const puppeteer = require('puppeteer-core');
const root = process.argv[2];
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json' };

const server = http.createServer((req, res) => {
    const file = path.join(root, decodeURIComponent(new URL(req.url, 'http://x').pathname));
    if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
}).listen(8099);

const chrome = ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium-browser'].find(p => fs.existsSync(p));
const browser = await puppeteer.launch({ executablePath: chrome, args: ['--no-sandbox', '--use-gl=swiftshader', '--enable-unsafe-swiftshader'] });
async function run(test) {
    const page = await browser.newPage();
    page.on('console', m => { const t = m.text(); if (!/^(Slicing|Planning|Writing|Preparing) /.test(t)) console.log(`[page ${test}]`, t); });
    page.on('pageerror', e => console.log(`[page ${test} error]`, e.message));
    page.on('response', r => { if (r.status() >= 400 && !r.url().endsWith('favicon.ico')) console.log('[missing]', r.status(), r.url()); });
    await page.goto(`http://localhost:8099/slicer/index.html?selftest=${test}`);
    let result = null;
    for (let i = 0; i < 360 && !result; i++) {
        await new Promise(r => setTimeout(r, 500));
        result = await page.evaluate(() => window.__selftest || null);
    }
    await page.close();
    return result;
}

let failed = false;
for (const test of [1, 2]) {
    const result = await run(test);
    if (!result) { console.error(`Self-test ${test} timed out`); failed = true; continue; }
    console.log(`Test ${test}:`, JSON.stringify({ done: result.done && { layers: result.done.layers, seconds: result.done.seconds, filamentMm: result.done.filamentMm },
        error: result.error, bytes: result.bytes, ms: result.ms }));
    if (test === 1) console.log(result.head);
    if (result.error || !result.done || result.bytes < 2000 || !result.head.includes('PRINT_START') || !(result.done.seconds > 0)) failed = true;
}
await browser.close();
server.close();
if (failed) { console.error('Slicer self-test FAILED'); process.exit(1); }
console.log('Slicer self-test passed');
