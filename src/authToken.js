// Токен доступа к API.
//
// Выдаётся сервером при логине/регистрации (поле token в ответе), хранится в
// localStorage и подставляется во все запросы к unlimitedpossibilities12.org:
//   - fetch — через обёртку над window.fetch (одна точка на ~50 вызовов);
//   - axios — через default-заголовок;
//   - STOMP — через connectHeaders (см. stompAuthHeaders).

const API_HOST = 'unlimitedpossibilities12.org';
export const AUTH_TOKEN_KEY = 'authToken';

export const getAuthToken = () => {
  try { return localStorage.getItem(AUTH_TOKEN_KEY) || null; } catch { return null; }
};

export const setAuthToken = (token) => {
  try {
    if (token) localStorage.setItem(AUTH_TOKEN_KEY, token);
    else localStorage.removeItem(AUTH_TOKEN_KEY);
  } catch { /* приватный режим и т.п. */ }
};

/** Заголовки для STOMP CONNECT. */
export const stompAuthHeaders = () => {
  const token = getAuthToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
};

const isApiUrl = (input) => {
  try {
    const url = typeof input === 'string' ? input : (input && input.url) || '';
    return url.includes(API_HOST);
  } catch { return false; }
};

/**
 * Токен протух или подписан прежним секретом: дальше работать нечем.
 *
 * Без этого страница показывала «Error fetching chats» и оставалась наполовину
 * живой: список чатов открыт без токена и грузился, а сообщения, звонки и
 * WebSocket молча получали 401. Человеку неоткуда догадаться, что надо просто
 * войти заново, — поэтому чистим вход и возвращаем на начальный экран.
 */
let sessionExpiredHandled = false;
const handleExpiredSession = () => {
  if (sessionExpiredHandled) return; // 401 прилетает сразу по нескольким запросам
  sessionExpiredHandled = true;
  setAuthToken(null);
  try {
    localStorage.removeItem('userId');
    localStorage.removeItem('username');
    localStorage.removeItem('userAvatar');
  } catch { /* приватный режим */ }
  try {
    window.alert('Your session has expired. Please sign in again.');
  } catch { /* alert может быть заблокирован */ }
  // На начальный экран: с него и выполняется вход
  window.location.hash = '#/';
  window.location.reload();
};

/** Один раз оборачивает window.fetch, чтобы добавлять Authorization к запросам на бэкенд. */
export const installFetchAuth = () => {
  if (typeof window === 'undefined' || !window.fetch || window.fetch.__upAuthInstalled) return;
  const original = window.fetch.bind(window);
  const wrapped = async (input, init) => {
    const token = getAuthToken();
    if (token && isApiUrl(input)) {
      const headers = new Headers((init && init.headers) || (input instanceof Request ? input.headers : undefined) || {});
      if (!headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`);
      const response = await original(input, { ...(init || {}), headers });
      // Токен был, но сервер его не принял — значит, он истёк или подделан
      if (response.status === 401) handleExpiredSession();
      return response;
    }
    return original(input, init);
  };
  wrapped.__upAuthInstalled = true;
  window.fetch = wrapped;
};

/** Default-заголовок для axios. Вызывать при старте и после логина. */
export const applyAxiosAuth = (axios) => {
  const token = getAuthToken();
  if (token) axios.defaults.headers.common.Authorization = `Bearer ${token}`;
  else delete axios.defaults.headers.common.Authorization;
};
