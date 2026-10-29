// Вложения, которых больше нет на сервере.
//
// Файлы пропадают при переразвёртывании, а ссылка в сообщении остаётся. Без
// памяти об этом картинка при каждом открытии чата занимает место, пытается
// загрузиться и схлопывается в строку «Image unavailable». Запоминаем такие
// адреса на сутки — потом пробуем снова, файл могут вернуть.

const PREFIX = 'missingAttachment:';
const RECHECK_MS = 24 * 60 * 60 * 1000;

const MEMORY = new Map();

/** Известно ли, что файла по этому адресу нет. */
export function isMissingAttachment(url) {
  if (!url) return false;

  let since = MEMORY.get(url);
  if (since === undefined) {
    try {
      const raw = localStorage.getItem(PREFIX + url);
      if (!raw) return false;
      since = Number(raw);
    } catch {
      return false;
    }
    MEMORY.set(url, since);
  }

  if (!since || Date.now() - since > RECHECK_MS) {
    forgetMissingAttachment(url);
    return false;
  }
  return true;
}

/** Запомнить, что файл не загрузился. */
export function rememberMissingAttachment(url) {
  if (!url) return;
  const now = Date.now();
  MEMORY.set(url, now);
  try {
    localStorage.setItem(PREFIX + url, String(now));
  } catch {
    // Место кончилось или хранилище запрещено — обойдёмся памятью вкладки.
  }
}

/** Файл загрузился — забыть прежнюю неудачу. */
export function forgetMissingAttachment(url) {
  if (!url) return;
  MEMORY.delete(url);
  try {
    localStorage.removeItem(PREFIX + url);
  } catch {
    // Хранилище недоступно — и не нужно.
  }
}
