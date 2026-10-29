// Превью ссылок: раз разобранное помним, чтобы не спрашивать заново.
//
// Сообщения приходят с сервера новыми объектами, поэтому при каждом входе в
// чат превью запрашивались заново — и секунду была видна голая ссылка. Теперь
// ответ живёт в памяти вкладки и в localStorage.

const MEMORY = new Map();
const PREFIX = 'linkPreview:';
// Заголовки страниц меняются редко — двух недель достаточно.
const TTL_MS = 14 * 24 * 60 * 60 * 1000;
// Чтобы localStorage не рос без конца.
const MAX_ENTRIES = 300;

const API = 'https://unlimitedpossibilities12.org/api/preview';

/** Готовое превью для адреса или null, если его ещё не спрашивали. */
export function getCachedPreview(url) {
  const inMemory = MEMORY.get(url);
  if (inMemory) return inMemory;

  try {
    const raw = localStorage.getItem(PREFIX + url);
    if (!raw) return null;

    const saved = JSON.parse(raw);
    if (Date.now() - saved.ts > TTL_MS) {
      localStorage.removeItem(PREFIX + url);
      return null;
    }

    MEMORY.set(url, saved.data);
    return saved.data;
  } catch {
    return null;
  }
}

/** Запомнить превью. */
export function cachePreview(url, data) {
  if (!data) return;
  MEMORY.set(url, data);

  try {
    const keys = Object.keys(localStorage).filter((key) => key.startsWith(PREFIX));
    if (keys.length >= MAX_ENTRIES) keys.forEach((key) => localStorage.removeItem(key));

    localStorage.setItem(PREFIX + url, JSON.stringify({ ts: Date.now(), data }));
  } catch {
    // Место кончилось или хранилище запрещено — обойдёмся памятью вкладки.
  }
}

/**
 * Превью для адреса: из кэша, иначе спрашиваем бэкенд. Возвращает null, если
 * ничего не вышло — такой ответ не запоминаем, чтобы попробовать в другой раз.
 */
export async function loadPreview(url) {
  const cached = getCachedPreview(url);
  if (cached) return cached;

  try {
    const res = await fetch(`${API}?url=${encodeURIComponent(url)}&ua=facebookexternalhit`);
    if (!res.ok) return null;

    const body = await res.json();
    if (!body.title && !body.description && !body.image) return null;
    if (body.title === 'Preview unavailable' && !body.image) return null;

    const data = {
      title: body.title || 'Untitled',
      description: body.description || '',
      imageUrl: body.image || '',
      url,
    };
    cachePreview(url, data);
    return data;
  } catch {
    return null;
  }
}
