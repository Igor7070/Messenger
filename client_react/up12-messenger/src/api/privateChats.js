const API = 'https://unlimitedpossibilities12.org';

/**
 * Найти переписку с человеком, а если её нет — завести.
 *
 * Порядок тот же, что был в карточке человека: сервер знает личные чаты по
 * имени вида «он_я», поэтому сначала спрашиваем, есть ли такой; если он есть,
 * но не числится за нами (так бывает, когда разговор завёл собеседник) —
 * добавляем себе; если нет вовсе — создаём.
 *
 * Возвращает адрес переписки, по которому остаётся перейти.
 */
export const findOrCreatePrivateChat = async (person, currentUserId) => {
  const myName = localStorage.getItem('username');
  const theirName = person.username;
  if (!currentUserId || String(person.id) === String(currentUserId)) {
    throw new Error('self');
  }

  const found = await fetch(
    `${API}/api/chats/check?name1=${encodeURIComponent(theirName)}&name2=${encodeURIComponent(myName)}`
  );
  if (!found.ok) throw new Error(`check ${found.status}`);
  const { chatId } = await found.json();

  if (chatId !== -1) {
    const mine = await fetch(`${API}/api/users/${currentUserId}/private-chats/${chatId}/exists`);
    if (mine.ok) return `/private-chat/${chatId}`;
    if (mine.status === 404) {
      const added = await fetch(`${API}/api/users/${currentUserId}/private-chats/${chatId}/add`, { method: 'POST' });
      if (!added.ok) throw new Error('add');
      return `/private-chat/${chatId}`;
    }
    throw new Error('check-mine');
  }

  const created = await fetch(`${API}/api/chats`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: `${theirName}_${myName}`, private: true }),
  });
  if (!created.ok) throw new Error('create');
  const chat = await created.json();
  return `/private-chat/${chat.id}`;
};
