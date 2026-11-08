/**
 * 自毁 Service Worker
 *
 * 原始 Workbox 预缓存会把 index.html 一起缓存，导致前端样式/脚本更新后
 * 浏览器仍然拿到旧页面。这里改为「安装即接管、激活即清缓存并注销」，
 * 让浏览器回归纯网络请求，之后的静态资源更新可以立即生效。
 */
self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys();
      await Promise.all(keys.map((k) => caches.delete(k)));
      await self.registration.unregister();
    })()
  );
});
