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

/** Один раз оборачивает window.fetch, чтобы добавлять Authorization к запросам на бэкенд. */
export const installFetchAuth = () => {
  if (typeof window === 'undefined' || !window.fetch || window.fetch.__upAuthInstalled) return;
  const original = window.fetch.bind(window);
  const wrapped = (input, init) => {
    const token = getAuthToken();
    if (token && isApiUrl(input)) {
      const headers = new Headers((init && init.headers) || (input instanceof Request ? input.headers : undefined) || {});
      if (!headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`);
      return original(input, { ...(init || {}), headers });
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
