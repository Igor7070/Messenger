// Пересылка логов браузера на бэкенд (POST /api/client-log), откуда они
// попадают в лог Railway строками «CLIENT-LOG [web] …». Нужно, чтобы читать
// консоль веб-клиента без ручного копирования.
//
// Что уходит: console.error, console.warn, необработанные исключения и
// отклонённые промисы. Обычные console.log — только если включён подробный
// режим (localStorage.remoteLog = 'all'). Выключить целиком:
// localStorage.remoteLog = 'off'.

const ENDPOINT = 'https://unlimitedpossibilities12.org/api/client-log';
const FLUSH_MS = 1500;
const MAX_QUEUE = 200;
const MAX_TEXT = 2000;

let queue = [];
let timer = null;
let installed = false;

const mode = () => {
  try { return localStorage.getItem('remoteLog') || 'errors'; } catch { return 'errors'; }
};

const fmt = (args) => {
  try {
    return args.map((a) => {
      if (a instanceof Error) return `${a.name}: ${a.message}${a.stack ? ' | ' + a.stack.split('\n').slice(0, 3).join(' > ') : ''}`;
      if (typeof a === 'string') return a;
      try { return JSON.stringify(a); } catch { return String(a); }
    }).join(' ').slice(0, MAX_TEXT);
  } catch { return '[unformattable]'; }
};

const flush = () => {
  timer = null;
  if (!queue.length) return;
  const entries = queue.splice(0, 50);
  let userId = '-';
  try { userId = localStorage.getItem('userId') || '-'; } catch { /* ignore */ }
  const body = JSON.stringify({
    userId,
    page: (typeof window !== 'undefined' && window.location && window.location.hash) || '-',
    entries,
  });
  try {
    // keepalive — чтобы последние ошибки успели уйти при закрытии вкладки
    fetch(ENDPOINT, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body, keepalive: true })
      .catch(() => {});
  } catch { /* ignore */ }
  if (queue.length) schedule();
};

const schedule = () => {
  if (!timer) timer = setTimeout(flush, FLUSH_MS);
};

const push = (level, text) => {
  if (mode() === 'off') return;
  if (text.includes(ENDPOINT)) return; // не логировать собственные сбои отправки
  if (queue.length >= MAX_QUEUE) queue.shift();
  queue.push({ level, text, t: Date.now() });
  schedule();
};

export const installRemoteLog = () => {
  if (installed || typeof window === 'undefined') return;
  installed = true;

  const origError = console.error.bind(console);
  const origWarn = console.warn.bind(console);
  const origLog = console.log.bind(console);

  console.error = (...args) => { origError(...args); push('error', fmt(args)); };
  console.warn = (...args) => { origWarn(...args); push('warn', fmt(args)); };
  console.log = (...args) => { origLog(...args); if (mode() === 'all') push('log', fmt(args)); };

  window.addEventListener('error', (e) => {
    push('error', `Uncaught: ${e.message} @ ${e.filename}:${e.lineno}:${e.colno}`);
  });
  window.addEventListener('unhandledrejection', (e) => {
    push('error', `Unhandled rejection: ${fmt([e.reason])}`);
  });
  window.addEventListener('beforeunload', flush);
};
