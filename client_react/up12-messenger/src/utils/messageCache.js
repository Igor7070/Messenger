// Хвост переписки в браузере.
//
// Без него открытие чата начиналось с пустого поля: сообщения каждый раз
// шли с сервера. Сохраняем последние и показываем сразу, а ответ сервера
// приходит следом и заменяет список.
//
// Секретные чаты сюда не попадают: их содержимое расшифровано только в
// памяти вкладки, и класть его в localStorage открытым текстом нельзя.

const PREFIX = 'chatMessages:';
// Хватает на пару экранов — больше на открытии всё равно не видно.
const MAX_MESSAGES = 50;

/** Сохранённые сообщения чата; пустой список, если их ещё нет. */
export function loadCachedMessages(chatId) {
  if (!chatId) return [];
  try {
    const raw = localStorage.getItem(PREFIX + chatId);
    if (!raw) return [];
    const saved = JSON.parse(raw);
    return Array.isArray(saved) ? saved : [];
  } catch {
    return [];
  }
}

/** Запомнить последние сообщения чата. */
export function cacheMessages(chatId, messages) {
  if (!chatId || !Array.isArray(messages) || messages.length === 0) return;
  try {
    const tail = messages.slice(-MAX_MESSAGES);
    localStorage.setItem(PREFIX + chatId, JSON.stringify(tail));
  } catch {
    // Место кончилось или хранилище запрещено — обойдёмся без кэша.
  }
}

/** Забыть переписку чата — например, когда чат удалён. */
export function clearCachedMessages(chatId) {
  if (!chatId) return;
  try {
    localStorage.removeItem(PREFIX + chatId);
  } catch {
    // Хранилище недоступно — и не нужно.
  }
}
