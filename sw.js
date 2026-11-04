// 電波がなくても開けるようにする Service Worker
// 通信できるときはいつも最新を取りに行き（そのたびに端末の控えを新しくする）、
// 圏外や通信が遅いときは端末の控えを出す
// 控えに入れるファイルを増やしたら VERSION を上げる

const VERSION = 'v1';
const CACHE = 'yw2zukan-' + VERSION;
const WAIT_MS = 3000; // これより遅ければ控えを出す
const FILES = [
  './',
  'index.html',
  'css/app.css',
  'js/app.js',
  'js/constants.js',
  'js/util.js',
  'js/store.js',
  'js/filter.js',
  'js/pages.js',
  'js/lookup.js',
  'data/yokai.json',
  'manifest.webmanifest',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/apple-touch-icon.png',
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES.map(f => new Request(f, { cache: 'reload' })))).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k.startsWith('yw2zukan-') && k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  e.respondWith(networkFirst(req));
});

async function networkFirst(req) {
  const cache = await caches.open(CACHE);
  // 画面の読み込み（#/yokai/… など）は index.html の控えで答える
  const key = req.mode === 'navigate' ? new URL('index.html', self.registration.scope).href : req;
  const fromNet = fetch(req).then(res => {
    if (res.ok) cache.put(key, res.clone());
    return res;
  });
  const timeout = new Promise(resolve => setTimeout(resolve, WAIT_MS));
  try {
    const res = await Promise.race([fromNet, timeout]);
    if (res) return res;
  } catch { /* 圏外 */ }
  const saved = await cache.match(key, { ignoreSearch: true });
  if (saved) return saved;
  return fromNet; // 控えもなければ通信の結果を待つ（失敗ならそのままエラー）
}
