import { getMessages } from './messages';
import { getMembers } from './chatMembers';

const API = 'https://unlimitedpossibilities12.org';

const isImage = (url) => /\.(jpg|jpeg|png|gif|webp|bmp)$/i.test(url || '');
const isVideo = (url) => /\.(mp4|webm|mov|mkv|avi)$/i.test(url || '');
const LINK = /\bhttps?:\/\/\S+/gi;

/**
 * Что накопилось в переписке с человеком: снимки, видео, файлы, ссылки,
 * голосовые — и какие группы у вас общие. Так же устроена карточка человека
 * в телеграме: под именем счёт, а по нажатию — сам список.
 *
 * История читается без пометки «открыл»: заглянуть в карточку — не то же
 * самое, что прочитать переписку, и чужие сообщения прочитанными не станут.
 * Секретную переписку не трогаем вовсе: её содержимое зашифровано, и
 * вскрывать его ради подсчёта снимков карточка не должна.
 */
export const sharedWith = async (person, currentUserId, currentUsername) => {
  const found = {
    photos: [], videos: [], files: [], links: [], voices: [], groups: [],
  };
  if (!person || !person.username || !currentUsername) return found;

  // 1. Личная переписка — если она вообще заводилась
  try {
    const check = await fetch(
      `${API}/api/chats/check?name1=${encodeURIComponent(person.username)}`
      + `&name2=${encodeURIComponent(currentUsername)}`
    );
    if (check.ok) {
      const { chatId } = await check.json();
      if (chatId && chatId !== -1) {
        const messages = await getMessages(chatId);
        (Array.isArray(messages) ? messages : []).forEach((message) => {
          const url = message.fileUrl || message.file_url;
          // Адрес приходит уже полным; но попадались и короткие пути —
          // достраиваем только их
          const full = !url ? null : (/^https?:\/\//i.test(url) ? url : `${API}${url}`);
          const item = {
            id: message.id,
            url: full,
            name: full ? decodeURIComponent(full.split('/').pop()) : '',
            caption: message.content || '',
            timestamp: message.timestamp,
            mine: Number(message.sender?.id) === Number(currentUserId),
          };
          if (message.messageType === 'audio') found.voices.push(item);
          else if (url && isImage(url)) found.photos.push(item);
          else if (url && isVideo(url)) found.videos.push(item);
          else if (url) found.files.push(item);

          const links = String(message.content || '').match(LINK);
          if (links) {
            links.forEach((href, index) => found.links.push({
              id: `${message.id}-${index}`,
              href,
              timestamp: message.timestamp,
              mine: item.mine,
            }));
          }
        });
      }
    }
  } catch {
    /* переписки может и не быть — тогда и считать нечего */
  }

  // 2. Общие группы: берём свои и смотрим, есть ли он среди участников
  try {
    const mine = await fetch(`${API}/api/chats`, { credentials: 'include' });
    if (mine.ok) {
      const chats = await mine.json();
      const groups = (Array.isArray(chats) ? chats : [])
        .filter((chat) => !chat.private && !chat.isSecret)
        .slice(0, 12); // в общий счёт лезем не бесконечно
      const checked = await Promise.all(groups.map(async (chat) => {
        try {
          const members = await getMembers(chat.id);
          const shares = (members || []).some(
            (member) => Number(member.userId) === Number(person.id)
          );
          return shares ? chat : null;
        } catch {
          return null;
        }
      }));
      found.groups = checked.filter(Boolean);
    }
  } catch {
    /* не вышло — просто не покажем строку */
  }

  // Новое сверху — как в любом списке такого рода
  const newestFirst = (a, b) => String(b.timestamp || '').localeCompare(String(a.timestamp || ''));
  found.photos.sort(newestFirst);
  found.videos.sort(newestFirst);
  found.files.sort(newestFirst);
  found.links.sort(newestFirst);
  found.voices.sort(newestFirst);

  return found;
};

export default sharedWith;
