import axios from 'axios';
import { setAuthToken, applyAxiosAuth } from '../authToken';

const API_URL = 'https://unlimitedpossibilities12.org/api/messages';
const API_CHATS_URL = 'https://unlimitedpossibilities12.org/api/chats'; // URL для чатов

// Переводы сервер хранит по языкам: попросив свой, получаем готовый
// и не заказываем его заново — и чужой язык в чат не попадёт
export const getMessages = async (chatId, options = {}, language = null) => {
    const query = language ? `?language=${encodeURIComponent(language)}` : '';
    const response = await fetch(`${API_CHATS_URL}/${chatId}/messages${query}`, options);
    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Failed to fetch messages: ${errorText}`);
    }
    return await response.json();
};

export const sendMessage = async (message) => {
    const response = await fetch(API_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(message),
    });
    if (!response.ok) throw new Error('Failed to send message');
    return await response.json();
};

/**
 * Удалить чат целиком (групповой — только его создателю; это проверяет сервер,
 * и правило в интерфейсе должно совпадать, иначе кнопка будет давать 403).
 */
export const deleteChat = async (chatId) => {
    const response = await fetch(`${API_CHATS_URL}/${chatId}`, { method: 'DELETE' });
    if (!response.ok) throw new Error(`Failed to delete chat: ${response.status}`);
};

// Добавляем функцию для удаления сообщения
export const deleteMessage = async (id) => {
    const response = await fetch(`${API_URL}/${id}`, {
        method: 'DELETE',
    });
    if (!response.ok) throw new Error('Failed to delete message');
};


const rememberToken = (user) => {
    if (user && user.token) { setAuthToken(user.token); applyAxiosAuth(axios); }
    return user;
};

export const createUser = async (user) => {
    const response = await fetch('https://unlimitedpossibilities12.org/api/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(user),
    });
    if (!response.ok) throw new Error('Failed to create user');
    return rememberToken(await response.json());
};

export const loginUser = async (user) => {
    const response = await fetch('https://unlimitedpossibilities12.org/api/users/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(user),
    });

    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText);
    }
    
    return rememberToken(await response.json());
};

export const createChat = async (chat) => {
    const response = await fetch('https://unlimitedpossibilities12.org/api/chats', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(chat),
    });

    if (!response.ok) {
        // Сервер отвечает 409 и объясняет причину («имя занято»), а на экране
        // всегда было одно «Failed to create chat» — человек не понимал, что
        // поправить. Показываем ответ сервера, если он есть.
        const text = await response.text().catch(() => '');
        throw new Error(text.trim() || 'Failed to create chat');
    }

    const data = await response.json();
    return data;
};

export const translateMessage = async (content, targetLanguage) => {
  const response = await fetch('https://unlimitedpossibilities12.org/api/translate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt: content, targetLanguage }),
  });
  if (!response.ok) throw new Error('Failed to translate message');
  // Сервер отдаёт перевод простой строкой, как и андроиду. Раньше модель
  // возвращала её в кавычках, и строка случайно читалась как JSON; когда
  // кавычки из ответа ушли, разбор стал падать — и подписи под снимками
  // в переписке остались без перевода. Читаем как текст, кавычки по краям
  // снимаем, если они ещё встретятся.
  const body = (await response.text()).trim();
  if (body.length > 1 && body.startsWith('"') && body.endsWith('"')) {
    try {
      return JSON.parse(body);
    } catch {
      return body.slice(1, -1);
    }
  }
  return body;
};

export const saveTranslation = async (messageId, translatedContent, targetLanguage) => {
  const response = await fetch(`https://unlimitedpossibilities12.org/api/messages/${messageId}/translate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ translatedContent, targetLanguage }),
  });
  if (!response.ok) throw new Error('Failed to save translation');
};