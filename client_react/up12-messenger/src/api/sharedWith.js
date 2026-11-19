import { getMessages } from './messages';
import { getMembers } from './chatMembers';

const API = 'https://unlimitedpossibilities12.org';

const isImage = (url) => /\.(jpg|jpeg|png|gif|webp|bmp)$/i.test(url || '');
const isVideo = (url) => /\.(mp4|webm|mov|mkv|avi)$/i.test(url || '');
const hasLink = (text) => /\bhttps?:\/\/\S+/i.test(text || '');

/**
 * Что накопилось в переписке с человеком: снимки, видео, файлы, ссылки,
 * голосовые — и сколько групп у вас общих. Так же устроена карточка человека
 * в телеграме, только там это ещё и открывается; у нас пока счёт.
 *
 * История читается без пометки «открыл»: заглянуть в карточку — не то же
 * самое, что прочитать переписку, и чужие сообщения прочитанными не станут.
 */
export const sharedWith = async (person, currentUserId, currentUsername) => {
  const empty = { photos: 0, videos: 0, files: 0, links: 0, voices: 0, groups: 0 };
  if (!person || !person.username || !currentUsername) return empty;

  const result = { ...empty };

  // 1. Личная переписка — если она вообще заводилась
  try {
    const found = await fetch(
      `${API}/api/chats/check?name1=${encodeURIComponent(person.username)}`
      + `&name2=${encodeURIComponent(currentUsername)}`
    );
    if (found.ok) {
      const { chatId } = await found.json();
      if (chatId && chatId !== -1) {
        const messages = await getMessages(chatId);
        (Array.isArray(messages) ? messages : []).forEach((message) => {
          const url = message.fileUrl || message.file_url;
          if (message.messageType === 'audio') result.voices += 1;
          else if (url && isImage(url)) result.photos += 1;
          else if (url && isVideo(url)) result.videos += 1;
          else if (url) result.files += 1;
          if (hasLink(message.content)) result.links += 1;
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
      const checks = await Promise.all(groups.map(async (chat) => {
        try {
          const members = await getMembers(chat.id);
          return (members || []).some((m) => Number(m.userId) === Number(person.id));
        } catch {
          return false;
        }
      }));
      result.groups = checks.filter(Boolean).length;
    }
  } catch {
    /* не вышло — просто не покажем строку */
  }

  return result;
};

export default sharedWith;
