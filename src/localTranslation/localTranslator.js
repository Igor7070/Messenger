// Локальный перевод для секретных чатов (веб).
//
// Тот же принцип и тот же формат, что на Android (LocalTranslator / SecretText):
//  - перевод на устройстве, после расшифровки входящих и до шифрования исходящих;
//  - исходный язык определяется автоматически (по алфавиту), переводится всё,
//    что не на целевом языке;
//  - в шифртекст уходит обёртка {"text","translated","lang"}.
//
// Движок — Transformers.js в Web Worker, модели OPUS-MT с нашего хостинга
// (public/models). Первая загрузка пары — по явному согласию пользователя.

export const SUPPORTED = [
  { code: 'en', label: 'English' },
  { code: 'ru', label: 'Русский' },
  { code: 'uk', label: 'Українська' },
];

/** Примерный размер одного направления (encoder + decoder, q8), МБ. */
export const DIRECTION_SIZE_MB = 110;

const MODELS_BASE = `${process.env.PUBLIC_URL || '/Messenger'}/models/`;

let worker = null;
let seq = 0;
const pending = new Map();
const loaded = new Set();          // "ru>en"
const progressListeners = new Set();

const ensureWorker = () => {
  if (worker) return worker;
  worker = new Worker(new URL('./translator.worker.js', import.meta.url), { type: 'module' });
  worker.onmessage = (e) => {
    const m = e.data || {};
    if (m.type === 'progress') {
      progressListeners.forEach((fn) => { try { fn(m); } catch { /* ignore */ } });
      return;
    }
    const p = pending.get(m.id);
    if (!p) return;
    pending.delete(m.id);
    if (m.type === 'error') p.reject(new Error(m.error));
    else p.resolve(m);
  };
  worker.onerror = (err) => {
    pending.forEach((p) => p.reject(new Error(err.message || 'worker error')));
    pending.clear();
  };
  return worker;
};

const call = (msg) => new Promise((resolve, reject) => {
  const id = ++seq;
  pending.set(id, { resolve, reject });
  ensureWorker().postMessage({ ...msg, id, base: MODELS_BASE });
});

export const onProgress = (fn) => { progressListeners.add(fn); return () => progressListeners.delete(fn); };

// ---------- настройки чата (localStorage) ----------

const key = (chatId, k) => `secretTranslate_${k}_${chatId}`;
export const isEnabled = (chatId) => { try { return localStorage.getItem(key(chatId, 'enabled')) === '1'; } catch { return false; } };
export const targetLanguage = (chatId) => { try { return localStorage.getItem(key(chatId, 'target')) || null; } catch { return null; } };
export const saveSettings = (chatId, enabled, target) => {
  try {
    localStorage.setItem(key(chatId, 'enabled'), enabled ? '1' : '0');
    if (target) localStorage.setItem(key(chatId, 'target'), target);
  } catch { /* ignore */ }
};

// ---------- языки ----------

/** Определение языка по алфавиту: достаточно для ru / uk / en. */
export const detectLanguage = (text) => {
  if (!text) return null;
  const cyr = (text.match(/[Ѐ-ӿ]/g) || []).length;
  const lat = (text.match(/[A-Za-z]/g) || []).length;
  if (cyr === 0 && lat === 0) return null;
  if (lat > cyr) return 'en';
  // украинские буквы: і ї є ґ
  if (/[іїєґІЇЄҐ]/.test(text)) return 'uk';
  // русские, которых нет в украинском: ы э ъ ё
  if (/[ыэъёЫЭЪЁ]/.test(text)) return 'ru';
  return 'ru'; // по умолчанию среди кириллических
};

/** Направления, нужные для перевода на target с любого поддерживаемого языка (через en). */
export const directionsForTarget = (target) => {
  const dirs = new Set();
  for (const { code } of SUPPORTED) {
    if (code === target) continue;
    if (code === 'en' || target === 'en') dirs.add(`${code}>${target}`);
    else { dirs.add(`${code}>en`); dirs.add(`en>${target}`); }
  }
  return [...dirs];
};

/**
 * Направления ровно для одной пары языков.
 *
 * Модель OPUS-MT — это отдельный файл на каждое направление, поэтому «скачать
 * всё для моего языка» растёт вместе со списком языков: для трёх это два файла,
 * для десяти было бы девять, почти гигабайт в браузер. А в разговоре нужна одна
 * пара, и язык собеседника известен заранее — его присылает сервер.
 */
export const directionsForPair = (from, to) => {
  if (!from || !to || from === to) return [];
  if (!SUPPORTED.some((l) => l.code === from) || !SUPPORTED.some((l) => l.code === to)) return [];
  return (from === 'en' || to === 'en') ? [`${from}>${to}`] : [`${from}>en`, `en>${to}`];
};

// ---------- загрузка моделей ----------

export const isDirectionLoaded = (dir) => loaded.has(dir);

const loadDirections = async (dirs) => {
  for (const dir of dirs) {
    if (loaded.has(dir)) continue;
    const [src, tgt] = dir.split('>');
    await call({ type: 'load', src, tgt });
    loaded.add(dir);
  }
};

/** Есть ли перечисленные направления в кэше браузера (без загрузки в память). */
const areDirectionsCached = async (dirs) => {
  try {
    if (!dirs.length) return true;
    if (!('caches' in window)) return false;
    const cache = await caches.open('transformers-cache');
    for (const dir of dirs) {
      const [src, tgt] = dir.split('>');
      const url = `${MODELS_BASE}opus-mt-${src}-${tgt}/onnx/encoder_model_quantized.onnx`;
      const hit = await cache.match(new URL(url, window.location.origin).href);
      if (!hit) return false;
    }
    return true;
  } catch { return false; }
};

/** Одна пара: ровно то, что нужно для разговора с этим собеседником. */
export const loadPair = (from, to) => loadDirections(directionsForPair(from, to));
export const isPairLoaded = (from, to) => directionsForPair(from, to).every((d) => loaded.has(d));
export const isPairCached = (from, to) => areDirectionsCached(directionsForPair(from, to));
export const pairSizeMb = (from, to) => directionsForPair(from, to).length * DIRECTION_SIZE_MB;

/** Все направления для target — заранее и для любого собеседника. */
export const loadForTarget = (target) => loadDirections(directionsForTarget(target));
export const isTargetCached = (target) => areDirectionsCached(directionsForTarget(target));

// ---------- перевод ----------

const translateDir = async (text, src, tgt) => {
  const r = await call({ type: 'translate', src, tgt, text });
  return r.translated || '';
};

/**
 * «Перевести на target, если текст не на target». Через английский для
 * пар без прямой модели (ru↔uk). Возвращает null, если переводить не нужно.
 */
export const translateToIfNeeded = async (text, target) => {
  if (!text || !text.trim()) return null;
  const src = detectLanguage(text);
  if (!src || src === target) return null;
  if (!SUPPORTED.some((l) => l.code === src)) return null;
  if (src === 'en' || target === 'en') {
    return translateDir(text, src, target);
  }
  const viaEn = await translateDir(text, src, 'en');
  return translateDir(viaEn, 'en', target);
};

// ---------- формат сообщения (совместим с Android SecretText) ----------

export const parseSecretText = (content) => {
  if (content == null) return { text: '', translated: null, lang: null };
  const s = String(content).trim();
  if (s.startsWith('{') && s.endsWith('}') && s.includes('"text"')) {
    try {
      const o = JSON.parse(s);
      if (typeof o.text === 'string') {
        return { text: o.text, translated: o.translated || null, lang: o.lang || null };
      }
    } catch { /* обычный текст */ }
  }
  return { text: String(content), translated: null, lang: null };
};

export const wrapSecretText = (text, translated, lang) =>
  JSON.stringify({ text, translated, lang });

/**
 * Готовит исходящий текст: если перевод включён и модели загружены — переводит
 * и заворачивает; иначе возвращает текст как есть. Никогда не бросает.
 */
export const prepareOutgoingText = async (chatId, text) => {
  try {
    const target = targetLanguage(chatId);
    if (!isEnabled(chatId) || !target) return text;
    const src = detectLanguage(text);
    if (!src || src === target) return text;
    // переводим только если нужные направления уже загружены (иначе не блокируем отправку)
    const need = src === 'en' || target === 'en' ? [`${src}>${target}`] : [`${src}>en`, `en>${target}`];
    if (!need.every((d) => loaded.has(d))) return text;
    const translated = await translateToIfNeeded(text, target);
    return translated ? wrapSecretText(text, translated, target) : text;
  } catch (e) {
    console.warn('Local translation failed, sending original:', e);
    return text;
  }
};
