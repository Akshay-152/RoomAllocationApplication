/**
 * Service worker: native background push + notification clicks.
 *
 * Ported from the self-hosted notification tooling (same payload schema) and
 * adapted to this SPA's notification protocol: a click posts
 * {type:'notification-click', id, link} to an open portal so it can mark the
 * notification read and jump to the right section; without an open window the
 * worker opens the portal itself.
 *
 * Payload schema (JSON, versioned with "v"):
 *   { v: 1, id, title, body, url, icon, badge, tag, timestamp }
 *
 * Duplicate handling: `tag` groups notifications (browsers replace an existing
 * visible notification with the same tag instead of stacking duplicates).
 *
 * Trust: payloads are encrypted end-to-end for this subscription, but we still
 * validate shape, clamp lengths and only navigate to URLs on this origin.
 */
const SW_VERSION = 'sw-v1';

/* Install/activate: new worker takes control immediately so the next
 * notification is handled by fresh code. Browsers still check sw.js for
 * updates on navigation (served with no-cache). */
self.addEventListener('install', (event) => {
  event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      await self.clients.claim();
    })()
  );
});

/** Clamp strings from the payload so a hostile/broken sender can't bloat UI. */
function clamp(value, max) {
  return typeof value === 'string' ? value.slice(0, max) : null;
}

/** Only allow navigation targets on this exact origin (no open redirects). */
function safeUrl(raw) {
  const fallback = '/index.html';
  if (!raw || typeof raw !== 'string') return fallback;
  try {
    const resolved = new URL(raw, self.location.origin);
    if (resolved.origin !== self.location.origin) return fallback;
    return resolved.pathname + resolved.search + resolved.hash;
  } catch {
    return fallback;
  }
}

/** Section token carried in the URL hash ("seat", "exams", ...). */
function sectionOf(url) {
  const hash = String(url || '').split('#')[1] || '';
  return hash.replace(/[^a-z]/gi, '') || 'overview';
}

self.addEventListener('push', (event) => {
  const process = async () => {
    let payload = null;
    let rawText = '';
    try {
      if (event.data) {
        rawText = event.data.text();
        payload = JSON.parse(rawText);
      }
    } catch {
      payload = null; // malformed JSON -> handled below
    }

    const valid = payload && typeof payload === 'object';
    const title = valid ? clamp(payload.title && String(payload.title).trim(), 120) : '';
    const body = valid ? clamp(payload.body, 400) : clamp(rawText, 400);
    const url = safeUrl(valid ? payload.url : null);

    const options = {
      body: body || 'A new notification is available.',
      icon: valid && typeof payload.icon === 'string' ? payload.icon : '/icons/icon-192.png',
      badge: '/icons/badge.png',
      tag: (valid && clamp(payload.tag, 64)) || 'exam-notification',
      data: {
        url: url,
        id: valid && Number.isFinite(Number(payload.id)) ? Number(payload.id) : 0,
        link: sectionOf(url),
      },
      renotify: false,
      requireInteraction: false,
    };

    await self.registration.showNotification(title || 'Notification', options);
  };

  event.waitUntil(process().catch(() => {
    // Last resort: never drop a push silently without telling the user.
    return self.registration.showNotification('Notification', {
      body: 'A new notification is available.',
      icon: '/icons/icon-192.png',
      badge: '/icons/badge.png',
      tag: 'exam-notification-fallback',
      data: { url: '/index.html', id: 0, link: 'overview' },
    });
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();

  const data = event.notification.data || {};
  const url = new URL(safeUrl(data.url || '/index.html'), self.location.origin).href;

  event.waitUntil(
    (async () => {
      const allClients = await self.clients.matchAll({
        type: 'window',
        includeUncontrolled: true,
      });
      for (const client of allClients) {
        if (client.url.startsWith(self.location.origin) && 'focus' in client) {
          await client.focus();
          // The SPA marks the notification read and opens the section itself.
          if ('postMessage' in client) {
            client.postMessage({
              type: 'notification-click',
              id: Number(data.id) || 0,
              link: data.link || 'overview',
            });
          }
          return;
        }
      }
      await self.clients.openWindow(url);
    })()
  );
});
