// Minimal service worker so the health dashboard can show phone notifications on Android.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const page = all.find(c => c.url.includes('health.html'));
    if (page) return page.focus();
    return self.clients.openWindow('health.html');
  })());
});
