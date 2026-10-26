// Единый список разговоров для веба: группы, личные и секретные чаты вместе.
//
// Раньше группы жили на главной, а личная переписка — на отдельной странице,
// и человек не видел всё в одном месте. Правила те же, что на телефоне, чтобы
// два клиента вели себя одинаково.

import { getMessages } from './messages';
import { isReadFlag } from './messageFlags';

const API = 'https://unlimitedpossibilities12.org/api';

export const ASSISTANT_ID = 'assistant';

const jsonOrEmpty = async (url, signal) => {
  try {
    const response = await fetch(url, { signal });
    if (!response.ok) return [];
    return await response.json();
  } catch (error) {
    if (error && error.name === 'AbortError') throw error; // опрос отменён новым
    console.warn('Request failed:', url, error);
    return [];
  }
};

/**
 * Очередь с ограничением: браузер держит к одному домену всего шесть
 * соединений, а список запрашивал историю каждого чата разом. Запрос
 * открываемой переписки вставал в хвост этой очереди, и чат появлялся на
 * экране через несколько секунд — со стороны «нажатие не сработало».
 * Четыре одновременных запроса оставляют место остальному приложению.
 */
const mapLimited = async (items, limit, worker) => {
  const results = new Array(items.length);
  let next = 0;
  const runners = new Array(Math.min(limit, items.length)).fill(null).map(async () => {
    while (next < items.length) {
      const index = next;
      next += 1;
      results[index] = await worker(items[index]);
    }
  });
  await Promise.all(runners);
  return results;
};

/**
 * Имя собеседника в чате на двоих.
 *
 * Сервер присылает участников вместе со списком — берём того, кто не я.
 * Разбор имени («я_он») оставлен запасным путём: имя человека само может
 * содержать «_», и тогда в строке списка показывался обрезок вместо имени.
 */
const partnerName = (chat, me, myId) => {
  const people = Array.isArray(chat.participants) ? chat.participants : null;
  if (people) {
    const partner = people.find((person) => Number(person.id) !== Number(myId));
    if (partner && partner.username) return partner.username;
  }
  const parts = String(chat.name || '').replace('_secret', '').split('_');
  const guess = parts.find((part) => part && part !== me);
  return guess || chat.name;
};

/** Ссылка целиком съедает строку, поэтому оставляем адрес сайта. */
const shortUrl = (url) => {
  try {
    const parsed = new URL(url);
    const host = parsed.host.replace(/^www\./, '');
    const hasMore = parsed.pathname.length > 1 || parsed.hash || parsed.search;
    return hasMore ? `${host}/…` : host;
  } catch {
    return url;
  }
};

const shortenLinks = (text) =>
  String(text || '')
    .split(/\s+/)
    .map((word) => (word.startsWith('http://') || word.startsWith('https://') ? shortUrl(word) : word))
    .join(' ')
    .trim();

const isImage = (message) => {
  if (message.contentType && message.contentType.startsWith('image')) return true;
  const url = (message.fileUrl || '').toLowerCase();
  return ['.jpg', '.jpeg', '.png', '.webp', '.gif'].some((ext) => url.endsWith(ext));
};

/** Строка под именем: кто написал, а у вложений — их тип. */
const previewOf = (chat, message, currentUserId) => {
  if (!message) return 'No messages yet';

  let body;
  if (message.messageType === 'audio') body = 'Voice message';
  else if (message.messageType === 'file') body = isImage(message) ? 'Photo' : 'File';
  else body = message.content;

  if (!body || !String(body).trim()) {
    body = chat.isSecret || message.encryptedContent ? 'Encrypted message' : 'No messages yet';
  }

  body = shortenLinks(body);

  const mine = message.sender && Number(message.sender.id) === Number(currentUserId);
  if (mine) return `You: ${body}`;

  const group = !chat.private && !chat.isSecret;
  if (group && message.sender && message.sender.username) return `${message.sender.username}: ${body}`;
  return body;
};

/**
 * Непрочитанные считаются по-разному: у личных чатов есть признак «прочитано»
 * с сервера, у групп его нет — там сравниваем со временем последнего захода.
 */
const unreadOf = (chat, messages, currentUserId) => {
  if (chat.private || chat.isSecret) {
    return messages.filter(
      (message) => !isReadFlag(message) && message.sender && Number(message.sender.id) !== Number(currentUserId)
    ).length;
  }
  const lastVisit = localStorage.getItem(`last_visit_chat_${chat.id}`);
  if (!lastVisit) return messages.length;
  const since = new Date(lastVisit).getTime();
  return messages.filter((message) => message.timestamp && new Date(message.timestamp).getTime() > since).length;
};

/** Метка возможности: сначала секретность, иначе включённый перевод. */
const badgeOf = (chat) => {
  if (chat.isSecret) return 'SECRET';
  try {
    if (localStorage.getItem(`translation_enabled_${chat.id}`) === 'true') {
      const language = localStorage.getItem(`translation_language_${chat.id}`) || '';
      return language && language !== 'auto' ? `→${language.toUpperCase()}` : 'TRANSLATE';
    }
  } catch {
    /* приватный режим */
  }
  return null;
};

const routeOf = (chat) => {
  if (chat.isSecret) return `/secret-chat/${chat.id}`;
  if (chat.private) return `/private-chat/${chat.id}`;
  return `/chat/${chat.id}`;
};

/**
 * Превью для строки, когда сообщение прилетело по сокету: у строки уже нет
 * исходного чата, зато известен её тип.
 */
export const previewForRow = (row, message, currentUserId) =>
  previewOf(
    { id: row.id, private: row.kind === 'private', isSecret: row.kind === 'secret' },
    message,
    currentUserId
  );

export const assistantConversation = () => ({
  id: ASSISTANT_ID,
  kind: 'assistant',
  name: 'UP12 Assistant',
  preview: 'Ask me to draft a reply or explain a text',
  timestamp: '',
  lastMessageId: 0,
  unreadCount: 0,
  badge: null,
  avatar: null,
  route: null,
});

/**
 * Собрать список: оба запроса параллельно, затем последние сообщения каждого
 * чата. Свежие разговоры сверху, ассистент закреплён первым.
 */
export const loadConversations = async (currentUserId, currentUsername, signal) => {
  const [allChats, privateChats] = await Promise.all([
    jsonOrEmpty(`${API}/chats`, signal),
    currentUserId ? jsonOrEmpty(`${API}/users/${currentUserId}/private-chats`, signal) : Promise.resolve([]),
  ]);

  const groups = (allChats || []).filter((chat) => !chat.private && !chat.isSecret);
  const chats = [...groups, ...(privateChats || [])];

  const items = await mapLimited(chats, 4, async (chat) => {
    let messages = [];
    try {
      messages = await getMessages(chat.id, { signal });
    } catch (error) {
      if (error && error.name === 'AbortError') throw error;
      console.warn('No messages for chat', chat.id, error);
    }
    const last = messages.length ? messages[messages.length - 1] : null;
    const oneToOne = chat.private || chat.isSecret;
    return {
      id: chat.id,
      kind: chat.isSecret ? 'secret' : chat.private ? 'private' : 'group',
      name: oneToOne ? partnerName(chat, currentUsername, currentUserId) : chat.name,
      avatar: chat.avatar || null,
      preview: previewOf(chat, last, currentUserId),
      timestamp: last ? last.timestamp : '',
      lastMessageId: last ? Number(last.id) : 0,
      unreadCount: unreadOf(chat, messages, currentUserId),
      badge: badgeOf(chat),
      route: routeOf(chat),
    };
  });

  items.sort((a, b) => String(b.timestamp || '').localeCompare(String(a.timestamp || '')));
  return [assistantConversation(), ...items];
};
