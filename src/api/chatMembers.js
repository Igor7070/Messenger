// Участники групповых чатов и приглашения.
//
// Права проверяет сервер; здесь мы лишь показываем то, что он разрешит, чтобы
// человек не жал кнопку, которая всё равно ответит 403.

const API = 'https://unlimitedpossibilities12.org/api';

const request = async (url, options = {}) => {
  const response = await fetch(url, options);
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(text || `HTTP ${response.status}`);
  }
  const body = await response.text();
  return body ? JSON.parse(body) : null;
};

const json = (method, data) => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(data),
});

export const getMembers = (chatId) => request(`${API}/chats/${chatId}/members`);

export const addMember = (chatId, userId) =>
  request(`${API}/chats/${chatId}/members/${userId}`, { method: 'POST' });

export const removeMember = (chatId, userId) =>
  request(`${API}/chats/${chatId}/members/${userId}`, { method: 'DELETE' });

export const leaveChat = (chatId) => request(`${API}/chats/${chatId}/leave`, { method: 'POST' });

export const joinChat = (chatId) => request(`${API}/chats/${chatId}/join`, { method: 'POST' });

/** role: 'ADMIN' — назначить помощником, 'MEMBER' — снять. Только владельцу. */
export const setMemberRole = (chatId, userId, role) =>
  request(`${API}/chats/${chatId}/members/${userId}/role`, json('PUT', { role }));

export const transferOwnership = (chatId, userId) =>
  request(`${API}/chats/${chatId}/transfer/${userId}`, { method: 'POST' });

export const getInvites = (chatId) => request(`${API}/chats/${chatId}/invites`);

/** ttlHours: 0 или пусто — бессрочная; maxUses: 0 — без предела. */
export const createInvite = (chatId, ttlHours, maxUses) =>
  request(`${API}/chats/${chatId}/invites`, json('POST', { ttlHours, maxUses }));

export const revokeInvite = (chatId, token) =>
  request(`${API}/chats/${chatId}/invites/${token}`, { method: 'DELETE' });

export const acceptInvite = (token) => request(`${API}/invites/${token}/accept`, { method: 'POST' });

/** Имя — админам, публичность — владельцу. */
export const updateChat = (chatId, changes) => request(`${API}/chats/${chatId}`, json('PUT', changes));

/** Ссылка, которую человек отправляет друзьям. */
export const inviteLink = (token) =>
  `${window.location.origin}${window.location.pathname}#/invite/${token}`;
