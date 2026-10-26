// Собеседник в личном и секретном чате.
//
// Имя такого чата складывается из имён обоих («А_Б», у секретного —
// «А_Б_secret»), и собеседник раньше вычислялся разбором этой строки по «_».
// Имя пользователя само может содержать «_», и тогда разбор давал
// несуществующее имя: запрос такого пользователя возвращал 404, переписка не
// открывалась и выбрасывала на главную. Спрашиваем участников у сервера — он
// знает их по связи, а не по названию.

const API = 'https://unlimitedpossibilities12.org/api';

/** Участники чата, либо null, если сервер их не отдал. */
export const getParticipants = async (chatId) => {
  try {
    const response = await fetch(`${API}/chats/${chatId}/participants`);
    if (!response.ok) return null;
    const people = await response.json();
    return Array.isArray(people) ? people : null;
  } catch (error) {
    console.warn('Could not load chat participants:', error);
    return null;
  }
};

/**
 * Собеседник: тот участник, который не я.
 *
 * Возвращает null, если спросить не удалось, — вызывающий код тогда
 * разбирает имя чата по-старому, и ничего не ломается на прежних данных.
 */
export const findPartner = async (chatId, currentUserId) => {
  const people = await getParticipants(chatId);
  if (!people || people.length === 0) return null;
  const partner = people.find((person) => Number(person.id) !== Number(currentUserId));
  return partner || null;
};

/** Имя собеседника из названия чата — запасной путь для старых данных. */
export const partnerNameFromChat = (chatName, myUsername) => {
  const parts = String(chatName || '').replace('_secret', '').split('_');
  return parts.find((part) => part && part !== myUsername) || '';
};
