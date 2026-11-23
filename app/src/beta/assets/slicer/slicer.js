// Replicator Ops Beta — glue between the app and the Kiri:Moto slicing engine.
//
// The app calls:   slicer.loadModel(url, colour)  slicer.slice(json)  slicer.cancel()
// and receives:    Android.onReady()  onModel(json)  onProgress(stage, pct)
//                  onChunk(text)  onDone(json)  onError(message)
import { newEngine } from '/kiri/kiri-eng.js';

const A = window.Android || {
    onReady() {}, onModel(j) { console.log('model', j) }, onProgress(s, p) { console.log(s, p) },
    onChunk(t) { (window.__gcode = window.__gcode || []).push(t) },
    onDone(j) { window.__done = JSON.parse(j) }, onError(m) { window.__error = m; console.error(m) },
};
const THREE = self.THREE;
const msg = document.getElementById('msg');

let modelData = null;      // ArrayBuffer (binary STL) of the plate to slice
let cancelled = false;

// ---- 3D preview -------------------------------------------------------------

const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
document.body.appendChild(renderer.domElement);
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x000000);
const camera = new THREE.PerspectiveCamera(40, 1, 1, 5000);
scene.add(new THREE.AmbientLight(0xffffff, 0.55));
const sun = new THREE.DirectionalLight(0xffffff, 0.9);
sun.position.set(200, -300, 400);
scene.add(sun);

// LCARS-style bed: 270 × 270 mm with a 30 mm grid.
const bed = new THREE.Group();
const plate = new THREE.Mesh(new THREE.PlaneGeometry(270, 270), new THREE.MeshBasicMaterial({ color: 0x1a1410 }));
bed.add(plate);
const grid = new THREE.GridHelper(270, 9, 0x4a3320, 0x4a3320);
grid.rotation.x = Math.PI / 2;
bed.add(grid);
const edge = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(270, 270, 0.1)),
    new THREE.LineBasicMaterial({ color: 0xff9900 }));
bed.add(edge);
scene.add(bed);

let model = null;
let turn = -0.6, tilt = 1.0, dist = 520, targetZ = 20;

function layout() {
    const w = window.innerWidth, h = window.innerHeight;
    renderer.setSize(w, h, false);
    camera.aspect = w / Math.max(h, 1);
    camera.updateProjectionMatrix();
    draw();
}

function draw() {
    camera.position.set(
        dist * Math.sin(tilt) * Math.sin(turn),
        -dist * Math.sin(tilt) * Math.cos(turn),
        targetZ + dist * Math.cos(tilt));
    camera.up.set(0, 0, 1);
    camera.lookAt(0, 0, targetZ);
    renderer.render(scene, camera);
}

// Drag to turn and tilt, pinch to zoom.
const pointers = new Map();
let pinch = 0;
renderer.domElement.addEventListener('pointerdown', e => { pointers.set(e.pointerId, e); renderer.domElement.setPointerCapture(e.pointerId); });
renderer.domElement.addEventListener('pointerup', e => { pointers.delete(e.pointerId); pinch = 0; });
renderer.domElement.addEventListener('pointercancel', e => { pointers.delete(e.pointerId); pinch = 0; });
renderer.domElement.addEventListener('pointermove', e => {
    const prev = pointers.get(e.pointerId);
    if (!prev) return;
    if (pointers.size === 1) {
        turn += (e.clientX - prev.clientX) / 150;
        tilt = Math.min(1.5, Math.max(0.1, tilt - (e.clientY - prev.clientY) / 200));
    }
    pointers.set(e.pointerId, e);
    if (pointers.size === 2) {
        const [a, b] = [...pointers.values()];
        const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
        if (pinch) dist = Math.min(1500, Math.max(80, dist * pinch / d));
        pinch = d;
    }
    draw();
});
window.addEventListener('resize', layout);

function showModel(vertices, colour) {
    if (model) scene.remove(model);
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(vertices, 3));
    geo.computeVertexNormals();
    geo.computeBoundingBox();
    const bb = geo.boundingBox;
    // Centre on the bed, sitting on it.
    geo.translate(-(bb.min.x + bb.max.x) / 2, -(bb.min.y + bb.max.y) / 2, -bb.min.z);
    model = new THREE.Mesh(geo, new THREE.MeshPhongMaterial({ color: colour || 0xffcc66, shininess: 30 }));
    scene.add(model);
    const size = [bb.max.x - bb.min.x, bb.max.y - bb.min.y, bb.max.z - bb.min.z];
    targetZ = size[2] / 3;
    dist = Math.max(300, Math.max(...size) * 2.6);
    msg.style.display = 'none';
    layout();
    return size;
}

/** PNG thumbnail (base64) of the model for the printer's screen. */
function thumbnail(px) {
    const keep = [turn, tilt, dist, renderer.getPixelRatio()];
    const w = window.innerWidth, h = window.innerHeight;
    renderer.setPixelRatio(1);
    renderer.setSize(px, px, false);
    camera.aspect = 1; camera.updateProjectionMatrix();
    bed.visible = false;
    turn = -0.6; tilt = 0.95;
    draw();
    const url = renderer.domElement.toDataURL('image/png');
    bed.visible = true;
    [turn, tilt, dist] = keep;
    renderer.setPixelRatio(keep[3]);
    renderer.setSize(w, h, false);
    layout();
    return url.substring(url.indexOf(',') + 1);
}

// ---- Slicing ----------------------------------------------------------------

async function loadModel(url, colour) {
    try {
        msg.textContent = 'LOADING PATTERN…';
        const res = await fetch(url);
        if (!res.ok) throw new Error(`Couldn't read the model (${res.status})`);
        modelData = await res.arrayBuffer();
        const view = new DataView(modelData);
        const count = view.getUint32(80, true);
        const verts = new Float32Array(count * 9);
        for (let t = 0, p = 84; t < count; t++, p += 50) {
            for (let k = 0; k < 9; k++) verts[t * 9 + k] = view.getFloat32(p + 12 + k * 4, true);
        }
        const size = showModel(verts, colour ? parseInt(colour, 16) : 0xffcc66);
        A.onModel(JSON.stringify({ triangles: count, size }));
    } catch (e) {
        A.onError(String(e && e.message || e));
    }
}

async function slice(json) {
    cancelled = false;
    try {
        if (!modelData) throw new Error('No model loaded');
        const { device, process } = JSON.parse(json);
        A.onProgress('Preparing', 0);
        const engine = newEngine({ workURL: '/kiri/kiri-work.js', poolURL: '/kiri/kiri-pool.js' });
        engine.setListener(ev => {
            if (cancelled) return;
            if (ev.slice && ev.slice.update != null) A.onProgress('Slicing', Math.round(ev.slice.update * 100));
            if (ev.prepare && ev.prepare.update != null) A.onProgress('Planning paths', Math.round(ev.prepare.update * 100));
        });
        engine.setMode('FDM').setDevice(device).setProcess(process);
        await engine.parse(modelData.slice(0));
        engine.setTopOffset(0);
        A.onProgress('Slicing', 0);
        await engine.slice();
        if (cancelled) return;
        A.onProgress('Planning paths', 0);
        await engine.prepare();
        if (cancelled) return;
        A.onProgress('Writing G-code', 0);
        const gcode = await engine.export();
        if (cancelled) return;
        // Hand the G-code to the app in pieces (it can be tens of MB).
        const step = 512 * 1024;
        for (let i = 0; i < gcode.length; i += step) {
            A.onChunk(gcode.substring(i, i + step));
            if (i % (step * 8) === 0) A.onProgress('Writing G-code', Math.round(i / gcode.length * 100));
        }
        const lines = gcode.split('\n');
        const info = { layers: 0, seconds: 0, filamentMm: 0, thumbnail: thumbnail(300), thumbSmall: thumbnail(96) };
        for (const l of lines.slice(0, 80)) {
            let m;
            if ((m = l.match(/;\s*--- print time:\s*([\d.]+)s/i)) || (m = l.match(/;\s*print time[^\d]*([\d.]+)/i))) info.seconds = parseFloat(m[1]);
            if ((m = l.match(/;\s*--- filament used:\s*([\d.]+)\s*mm/i)) || (m = l.match(/;\s*filament used[^\d]*([\d.]+)/i))) info.filamentMm = parseFloat(m[1]);
        }
        info.layers = (gcode.match(/^;LAYER_CHANGE/gm) || []).length;
        A.onDone(JSON.stringify(info));
    } catch (e) {
        A.onError(String(e && e.message || e));
    }
}

window.slicer = {
    loadModel,
    slice,
    cancel() { cancelled = true; },
};
layout();
msg.textContent = 'AWAITING PATTERN';
A.onReady();

// Self-test (CI): ?selftest=1 slices a 20 mm cube with default Q2C-like settings.
if (location.search.includes('selftest')) {
    (async () => {
        const tri = [];
        const v = (x, y, z) => [x * 20, y * 20, z * 20];
        const quad = (a, b, c, d) => { tri.push(...a, ...b, ...c, ...a, ...c, ...d); };
        const p = [v(0,0,0), v(1,0,0), v(1,1,0), v(0,1,0), v(0,0,1), v(1,0,1), v(1,1,1), v(0,1,1)];
        quad(p[0], p[3], p[2], p[1]); quad(p[4], p[5], p[6], p[7]); quad(p[0], p[1], p[5], p[4]);
        quad(p[1], p[2], p[6], p[5]); quad(p[2], p[3], p[7], p[6]); quad(p[3], p[0], p[4], p[7]);
        const count = tri.length / 9;
        const buf = new ArrayBuffer(84 + count * 50);
        const dv = new DataView(buf);
        dv.setUint32(80, count, true);
        for (let t = 0; t < count; t++) for (let k = 0; k < 9; k++) dv.setFloat32(84 + t * 50 + 12 + k * 4, tri[t * 9 + k], true);
        const blob = URL.createObjectURL(new Blob([buf]));
        await loadModel(blob);
        const cfg = await (await fetch('/slicer/selftest.json')).json();
        await slice(JSON.stringify(cfg));
        window.__selftest = { done: window.__done, error: window.__error, bytes: (window.__gcode || []).join('').length,
            head: (window.__gcode || [''])[0].substring(0, 5000) };
    })();
}
