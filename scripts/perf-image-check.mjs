/**
 * 图片加载优化验证：上传大图 → 原图 vs ?w=192 缩略图 的字节数/缓存头。
 *
 * 用法：node scripts/perf-image-check.mjs <ws-url> <本地图片>
 */
import { readFileSync } from 'node:fs';

const WS = process.argv[2] || 'ws://127.0.0.1:18080/ws';
const IMAGE = process.argv[3] || '.perf-test.png';
const HTTP = WS.replace(/^ws/, 'http').replace(/\/ws$/, '');

function connect() {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(WS);
        const pending = new Map();
        let seq = 0;
        ws.onopen = () => resolve({
            call(cmd, data) {
                return new Promise((res, rej) => {
                    const id = ++seq;
                    const timer = setTimeout(() => rej(new Error(cmd + ' timeout')), 8000);
                    pending.set(id, { res, rej, timer });
                    ws.send(JSON.stringify({ cmd, seq: id, code: 0, msg: null, data }));
                });
            },
            close() { ws.close(); }
        });
        ws.onerror = () => reject(new Error('connect failed'));
        ws.onmessage = (event) => {
            const env = JSON.parse(event.data);
            const entry = pending.get(env.seq);
            if (!entry) return;
            pending.delete(env.seq);
            clearTimeout(entry.timer);
            env.code === 0 ? entry.res(env.data) : entry.rej(new Error(env.msg));
        };
    });
}

const conn = await connect();
const login = await conn.call('login', { account: '演示账号1', password: '123456', deviceId: 'perf-check' });

const bytes = readFileSync(IMAGE);
const form = new FormData();
form.append('file', new Blob([bytes], { type: 'image/png' }), 'perf-test.png');
const uploadResponse = await fetch(HTTP + '/api/upload', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + login.token },
    body: form
});
const uploaded = await uploadResponse.json();
const originalUrl = HTTP + uploaded.url;
const thumbUrl = originalUrl + (originalUrl.includes('?') ? '&' : '?') + 'w=192';

const original = await fetch(originalUrl);
const originalBytes = new Uint8Array(await original.arrayBuffer());
const thumb = await fetch(thumbUrl);
const thumbBytes = new Uint8Array(await thumb.arrayBuffer());

console.log('original:', original.status, originalBytes.length, 'bytes',
    original.headers.get('content-type'), 'cache-control=' + original.headers.get('cache-control'));
console.log('thumb   :', thumb.status, thumbBytes.length, 'bytes',
    thumb.headers.get('content-type'), 'cache-control=' + thumb.headers.get('cache-control'));

let failed = false;
if (original.status !== 200 || thumb.status !== 200) {
    console.log('FAIL 状态码不对');
    failed = true;
}
if (thumbBytes.length >= originalBytes.length) {
    console.log('FAIL 缩略图没有变小');
    failed = true;
}
if (!(thumb.headers.get('content-type') || '').startsWith('image/')) {
    console.log('FAIL 缩略图不是图片');
    failed = true;
}
if (!original.headers.get('cache-control') || !thumb.headers.get('cache-control')) {
    console.log('FAIL 缺少 Cache-Control');
    failed = true;
}
if (thumbBytes.length > 200 * 1024) {
    console.log('FAIL 缩略图仍然太大: ' + thumbBytes.length);
    failed = true;
}
console.log(failed ? '\nFAILED' : '\nPASS：缩略图 ' + Math.round(originalBytes.length / thumbBytes.length) + '× 更小');
conn.close();
process.exit(failed ? 1 : 0);
