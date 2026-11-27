import MessageChecks from './MessageChecks';
import AudioWaveform from './AudioWaveform';
import { nameOf } from '../api/names';
import { findPartner, partnerNameFromChat } from '../api/participants';
import { isReadFlag, isDeliveredFlag } from '../api/messageFlags';
import { stompAuthHeaders } from '../authToken';
import { computeSafetyNumber } from './SafetyNumber';
import SecretTranslationDialog from '../localTranslation/SecretTranslationDialog';
import LocalTranslatedLine, { clearLocalTranslationCache } from '../localTranslation/LocalTranslatedLine';
import { prepareOutgoingText, parseSecretText, isEnabled as isLocalTranslationEnabled, targetLanguage as localTargetLanguage } from '../localTranslation/localTranslator';
import React, { useEffect, useState, useRef, useMemo } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages } from '../api/messages';
import '../styles/SecretPrivateChat.css';
import { toast } from 'react-toastify';
import { useParams, useNavigate } from 'react-router-dom';
import { getChatById } from '../api/chats';
import IncomingCallNotification from './IncomingCallNotification';
import { format, isToday, parseISO } from 'date-fns';
import { useUserInteraction } from '../context/UserInteractionContext';
import { useUserStatusContext } from '../context/UserStatusContext';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import { usePrivateMessageNotification } from '../context/PrivateMessageNotificationContext';
import { FFmpeg } from '@ffmpeg/ffmpeg';
import * as sodium from 'libsodium-wrappers';
import AssistantIcon from './AssistantIcon';

const saveOwnMessageContent = (chatId, userId, clientMessageId, content) => {
  try {
    const key = `ownMessageContent_${chatId}_${userId}`;
    const stored = localStorage.getItem(key);
    const messages = stored ? JSON.parse(stored) : {};
    messages[clientMessageId] = content;
    localStorage.setItem(key, JSON.stringify(messages));
    console.log(`Saved own message content to localStorage: clientMessageId=${clientMessageId}, content=${content}`);
  } catch (error) {
    console.error('Failed to save own message content to localStorage:', error);
  }
};

const getOwnMessageContent = (chatId, userId, clientMessageId) => {
  try {
    const key = `ownMessageContent_${chatId}_${userId}`;
    const stored = localStorage.getItem(key);
    if (stored) {
      const messages = JSON.parse(stored);
      const content = messages[clientMessageId];
      console.log(`Retrieved own message content from localStorage: clientMessageId=${clientMessageId}, content=${content || 'not found'}`);
      return content || null;
    }
    return null;
  } catch (error) {
    console.error('Failed to get own message content from localStorage:', error);
    return null;
  }
};

export const generateChatName = (username1, username2, isSecret = false) => {
  const names = [username1, username2].sort();
  return isSecret ? `${names[0]}_${names[1]}_secret` : `${names[0]}_${names[1]}`;
};

const fetchPublicKeyWithRetry = async (recipientId, retries = 3, delay = 2000) => {
  for (let attempt = 1; attempt <= retries; attempt++) {
    try {
      console.log(`Fetching public key for userId: ${recipientId}, attempt ${attempt}/${retries}`);
      const publicKeyResponse = await fetch(
        `https://unlimitedpossibilities12.org/api/public_keys/${recipientId}`
      );
      if (publicKeyResponse.ok) {
        const publicKeyData = await publicKeyResponse.json();
        console.log(`Public key fetched for userId: ${recipientId}: ${publicKeyData.publicKey}`);
        return publicKeyData.publicKey;
      } else if (publicKeyResponse.status === 404) {
        console.warn(`Public key not found for userId: ${recipientId}, attempt ${attempt}/${retries}`);
        if (attempt < retries) {
          await new Promise((resolve) => setTimeout(resolve, delay));
          continue;
        }
        throw new Error(`Failed to fetch public key for userId: ${recipientId}, status: ${publicKeyResponse.status}`);
      } else {
        throw new Error(`Unexpected response: ${publicKeyResponse.status}`);
      }
    } catch (error) {
      console.error(`Error fetching public key for userId: ${recipientId}, attempt ${attempt}/${retries}`, error);
      if (attempt === retries) {
        throw error;
      }
      await new Promise((resolve) => setTimeout(resolve, delay));
    }
  }
};

/**
 * Нынешние ключи, за которыми только что ходили.
 *
 * При открытии переписки за ключом собеседника ходят по разу на каждое
 * сообщение и каждое вложение — выходило по пять-семь походов подряд, и
 * каждый по четверти секунды. Держим ответ недолго: ключ может
 * смениться, и тогда через полминуты за ним сходят заново.
 */
const нынешниеКлючи = new Map();
const СРОК_КЛЮЧА = 30000;

const getPublicKey = async (userId, publicKeyId = null) => {
  console.log(`Fetching public key for userId: ${userId}, publicKeyId: ${publicKeyId}`);
  if (!publicKeyId) {
    const свежий = нынешниеКлючи.get(String(userId));
    if (свежий && Date.now() - свежий.когда < СРОК_КЛЮЧА) return свежий.обещание;
    const обещание = (async () => {
      const ответ = await fetch(`https://unlimitedpossibilities12.org/api/public_keys/${userId}`);
      if (!ответ.ok) throw new Error(`Failed to fetch public key: ${ответ.status}`);
      return (await ответ.json()).publicKey;
    })();
    нынешниеКлючи.set(String(userId), { когда: Date.now(), обещание });
    обещание.catch(() => нынешниеКлючи.delete(String(userId)));   // неудачу не храним
    return обещание;
  }
  if (publicKeyId) {
    // Исторические ключи не меняются — если уже забирали, второй раз не ходим
    try {
      const saved = localStorage.getItem(`history_key_${publicKeyId}`);
      if (saved) return saved;
    } catch (e) {
      // хранилище недоступно — просто сходим на сервер
    }
  }
  try {
    const url = publicKeyId
      ? `https://unlimitedpossibilities12.org/api/public_keys/history/${publicKeyId}` // [ADD] Новый эндпоинт
      : `https://unlimitedpossibilities12.org/api/public_keys/${userId}`;
    const response = await fetch(url);
    if (!response.ok) {
      if (publicKeyId) {
        console.warn(`History fetch failed for publicKeyId=${publicKeyId}, fallback to current for userId=${userId}`);
        const fallbackUrl = `https://unlimitedpossibilities12.org/api/public_keys/${userId}`;  // Current key
        const fallbackResponse = await fetch(fallbackUrl);
        if (!fallbackResponse.ok) throw new Error(`Fallback failed: ${fallbackResponse.status}`);
        const fallbackData = await fallbackResponse.json();
        const fallbackKey = fallbackData.publicKey;
        console.log(`Fallback to current key for userId=${userId}: ${fallbackKey}`);
        // Кэш fallback as history
        localStorage.setItem(`history_key_${publicKeyId}`, fallbackKey);
        return fallbackKey;
      }
      throw new Error(`Failed to fetch public key: ${response.status}`);
    }
    const publicKeyData = await response.json();
    const key = publicKeyData.publicKey;
    if (publicKeyId) localStorage.setItem(`history_key_${publicKeyId}`, key);  // Кэш history
    console.log(`Fetched public key for userId: ${userId}, publicKeyId: ${publicKeyId}, value: ${publicKeyData.publicKey}`);
    try {
      const publicKeyBytes = sodium.from_base64(publicKeyData.publicKey);
      console.log(`Public key bytes length for userId: ${userId}: ${publicKeyBytes.length}, hex: ${sodium.to_hex(publicKeyBytes)}`);
    } catch (e) {
      console.error(`Invalid fetched public key for userId: ${userId}, publicKeyId: ${publicKeyId}, error: ${e.message}`);
    }
    return publicKeyData.publicKey;
  } catch (error) {
    console.error(`Error fetching public key for userId: ${userId}, publicKeyId: ${publicKeyId}, error: ${error.message}`);
    throw new Error(`Failed to fetch public key for userId: ${userId}`);
  }
};


/** Разделители дат в ленте секретного чата — как в остальных. */
const dayKey = (timestamp) => {
  const date = new Date(timestamp);
  return Number.isNaN(date.getTime()) ? '' : date.toDateString();
};

const dayLabel = (timestamp) => {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '';
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (date.toDateString() === today.toDateString()) return 'Today';
  if (date.toDateString() === yesterday.toDateString()) return 'Yesterday';
  const sameYear = date.getFullYear() === today.getFullYear();
  return date.toLocaleDateString('en-GB', sameYear
    ? { day: 'numeric', month: 'long' }
    : { day: 'numeric', month: 'long', year: 'numeric' });
};


/**
 * Где показать меню: у самого курсора, как в телеграме.
 *
 * Меню стоит «намертво» в окне (position: fixed), и прибавлять к его
 * месту прокрутку страницы нельзя — оно улетает вниз тем дальше, чем
 * ниже прокручено. У нижнего края экрана ему не хватает места под
 * курсором: тогда показываем над ним.
 */

/* Значки для меню — по образцу телеграма: тонкий контур, без заливки. */
const ЗначокПравки = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M4 20h4L19 9l-4-4L4 16v4z" />
    <path d="M14 6l4 4" />
  </svg>
);

const ЗначокКорзины = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M5 7h14" />
    <path d="M9 7V5h6v2" />
    <path d="M7 7l1 12h8l1-12" />
  </svg>
);

const ЗначокВыбора = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <circle cx="12" cy="12" r="8.5" />
    <path d="M8.5 12.3l2.4 2.4 4.6-4.9" />
  </svg>
);

const местоМеню = (событие) => {
  const ширина = 150, высота = 92, поле = 8;
  const x = Math.max(поле, Math.min(событие.clientX, window.innerWidth - ширина - поле));
  let y = событие.clientY + 4;
  if (y + высота > window.innerHeight - поле) y = Math.max(поле, событие.clientY - высота - 4);
  return { x, y };
};

const startsNewDay = (messages, index) => {
  const current = dayKey(messages[index]?.timestamp);
  if (!current) return false;
  if (index === 0) return true;
  return current !== dayKey(messages[index - 1]?.timestamp);
};

/**
 * Подряд ли эта строка за строкой того же человека.
 *
 * В телеграме реплики одного стоят почти вплотную, а при смене
 * отправителя просвет шире — так видно, где кончилась одна речь.
 * Карточка звонка считается своей стороной: исходящие подряд тоже
 * сходятся. Над первой строкой дня просвет всегда широкий.
 */
const чьяСтрока = (строка, userId) => {
  if (!строка) return null;
  if (строка.звонок) return String(строка.звонок.caller) === String(userId) ? 'звонок-свой' : 'звонок-чужой';
  return строка.sender ? `кто-${строка.sender.id}` : null;
};

const тотЖеПодряд = (лента, index, userId) => {
  if (index === 0 || startsNewDay(лента, index)) return false;
  const чья = чьяСтрока(лента[index], userId);
  return чья !== null && чья === чьяСтрока(лента[index - 1], userId);
};

const SecretPrivateChat = () => {
  const [messages, setMessages] = useState([]);
  const [content, setContent] = useState('');
  const [selectedFile, setSelectedFile] = useState(null);
  const [userId, setUserId] = useState(Number(localStorage.getItem('userId')));
  const [username, setUsername] = useState(localStorage.getItem('username'));
  const [client, setClient] = useState(null);
  const [chatId, setChatId] = useState(null);
  const [chatName, setChatName] = useState('');
  // Имя чата в ссылке: им пользуется запасной путь определения собеседника,
  // вызываемый из обработчиков, где состояние могло остаться прежним.
  const chatNameRef = useRef('');
  chatNameRef.current = chatName;
  const [displayChatName, setDisplayChatName] = useState('');
  const [editingMessageId, setEditingMessageId] = useState(null);
  const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, message: null });
  const contextMenuRef = useRef(null);
  const [isRecording, setIsRecording] = useState(false);
  const [audioBlob, setAudioBlob] = useState(null);
  const [micIconState, setMicIconState] = useState('mic');
  const [audioProgress, setAudioProgress] = useState({});
  const [rows, setRows] = useState(1);
  const [charPerLine, setCharPerLine] = useState(50);
  const [isDragging, setIsDragging] = useState(null);
  const [ffmpegLoaded, setFfmpegLoaded] = useState(false);
  const [publicKeys, setPublicKeys] = useState({}); // Хранит публичные ключи участников чата

  /**
   * Ключ, которым шифровали именно это сообщение.
   *
   * У сообщения свой publicKeyId: собеседник мог сменить ключ, и старое
   * сообщение расшифровывается только прежним. Память держим под текущий
   * ключ человека и историческим её не портим — иначе соседние сообщения
   * перестают открываться, и выходит «то работает, то нет».
   */
  const keyForMessage = async (ownerId, publicKeyId) => {
    if (publicKeyId) return await getPublicKey(ownerId, publicKeyId);
    const known = publicKeys[ownerId];
    if (known) return known;
    const fetched = await getPublicKey(ownerId, null);
    setPublicKeys((prev) => ({ ...prev, [ownerId]: fetched }));
    return fetched;
  };
  const [keyPair, setKeyPair] = useState(null); // Локальная пара ключей пользователя
  const [sentMessagesCache, setSentMessagesCache] = useState({}); // Кэш отправленных сообщений: { clientMessageId: content }
  const [processedMessages, setProcessedMessages] = useState(new Set());
  const [decryptedAudioBlobs, setDecryptedAudioBlobs] = useState({}); // { [msgId]: { blobUrl: string, mp3Blob: Blob } }
  const [isTyping, setIsTyping] = useState(false); // Для индикатора печатания
  const [typingName, setTypingName] = useState(''); // Для индикатора печатания
  const [partnerId, setPartnerId] = useState(null);

  /** Звонки с этим собеседником — карточками в ленте, как в обычной переписке. */
  /** Сведения о вложениях, пришедшие вместе с историей. */
  const сведенияОВложениях = useRef(new Map());

  const [звонки, setЗвонки] = useState([]);

  /**
   * Карточки прошлого захода — сразу, как открыли переписку.
   *
   * Ждать ответа сервера нельзя: переписка рисуется прежде него, и
   * звонки приезжали бы следом. Собеседника тут тоже не ждём — он
   * выясняется отдельным запросом, а ключ у нас по самой переписке.
   */
  useEffect(() => {
    /* Номер переписки выясняется не мгновенно, а в адресе он есть сразу —
       берём оттуда, иначе карточки опять отстают на полсекунды. */
    const изАдреса = (window.location.hash.match(/chat\/(\d+)/) || [])[1];
    const какойЧат = chatId || изАдреса;
    if (!какойЧат) return;
    try {
      const сохранённое = JSON.parse(localStorage.getItem(`up12-calls-${какойЧат}`) || '[]');
      if (Array.isArray(сохранённое) && сохранённое.length) setЗвонки(сохранённое);
    } catch (ошибка) {
      /* ничего не сохранено или память браузера недоступна */
    }
  }, [chatId]);


  useEffect(() => {
    if (!userId || !partnerId) return undefined;
    let живо = true;
    const ключЗвонков = `up12-calls-${chatId}`;
    (async () => {
      try {
        const ответ = await fetch(`https://unlimitedpossibilities12.org/api/calls?userId=${userId}`, {
          headers: { Authorization: `Bearer ${localStorage.getItem('authToken')}` },
        });
        if (!ответ.ok) return;
        const все = await ответ.json();
        const наши = (Array.isArray(все) ? все : []).filter((з) => {
          if (з.callType === 'group') return false;
          const от = String(з.caller);
          const кому = String(з.receiver || '');
          const наш = (от === String(userId) && кому === String(partnerId))
                   || (от === String(partnerId) && кому === String(userId));
          if (!наш) return false;
          // Эта переписка тайная: сюда только звонки из неё
          return String(з.roomId || '').startsWith('secure');
        });
        if (живо) {
          /* Показываем все звонки этой пары. Прежде брали последние
             тридцать, и выходило обидное: человек убирал видимые
             карточки, заходил снова — а на их место вставали те, что
             раньше были отрезаны, будто удаление не сработало. */
          const свежие = наши.map((з) => ({
            id: `call-${з.id}`,
            звонок: з,
            timestamp: з.timestamp,
            sender: { id: String(з.caller) === String(userId) ? userId : Number(partnerId) },
          }));
          /* Если ничего не поменялось, ленту не трогаем вовсе: лишняя
             перерисовка видна глазу как мигание. */
          setЗвонки((прежние) => {
            const тоЖе = прежние.length === свежие.length
              && прежние.every((к, i) => к.id === свежие[i].id);
            if (тоЖе) return прежние;
            try {
              localStorage.setItem(ключЗвонков, JSON.stringify(свежие));
            } catch (ошибка) {
              /* память браузера может быть переполнена — не беда */
            }
            return свежие;
          });
        }
      } catch (ошибка) {
        console.warn('Список звонков не пришёл:', ошибка);
      }
    })();
    return () => { живо = false; };
    /* Список читаем один раз на собеседника: он не меняется от каждого
       пришедшего сообщения, а перечитывать его на каждое — верный способ
       подвесить страницу. */
  }, [userId, partnerId]);


  /**
   * Что собираемся убрать и уберём ли у собеседника.
   *
   * Окно одно на сообщения и на звонки — как в телеграме, где надпись не
   * меняется, что бы ни убирали. В `убрать` лежит либо
   * `{ вид: 'звонок', id }`, либо `{ вид: 'сообщение', id }`.
   */
  const [убрать, setУбрать] = useState(null);
  const [убратьУОбоих, setУбратьУОбоих] = useState(false);

  /**
   * Выбор нескольких строк, как в телеграме. В наборе лежат ключи:
   * у сообщения «м-номер», у звонка «з-номер».
   */
  const [выбранные, setВыбранные] = useState(() => new Set());
  const выборИдёт = выбранные.size > 0;
  const ключСтроки = (строка) => (строка && строка.звонок ? `з-${строка.звонок.id}` : `м-${строка && строка.id}`);
  const выбрана = (строка) => выбранные.has(ключСтроки(строка));

  const переключитьВыбор = (строка) => {
    setВыбранные((прежние) => {
      const новые = new Set(прежние);
      const ключ = ключСтроки(строка);
      if (новые.has(ключ)) новые.delete(ключ); else новые.add(ключ);
      return новые;
    });
  };

  const закончитьВыбор = () => setВыбранные(new Set());


  /** Убрать звонок или сообщение: у себя всегда, у собеседника — по желанию. */
  const убратьВыбранное = async () => {
    const что = убрать;
    if (!что) return;
    const иУСобеседника = убратьУОбоих;
    setУбрать(null);
    setУбратьУОбоих(false);
    if (что.вид === 'набор') {
      const ключи = что.ключи || [];
      закончитьВыбор();
      /* Разом, а не вереницей: два десятка строк по очереди убирались
         на глазах и долго. Об отказах говорим один раз в конце, а не
         всплывашкой на каждую. */
      const итоги = await Promise.allSettled(ключи.map((ключ) => {
        const звонок = ключ.startsWith('з-');
        const номер = Number(ключ.slice(2));
        return убратьОдно(звонок ? { вид: 'звонок', id: номер } : { вид: 'сообщение', id: номер },
                          иУСобеседника, true);
      }));
      const неудач = итоги.filter((и) => и.status === 'rejected').length;
      if (неудач) toast.error(`Could not delete ${неудач} of ${ключи.length}`);
      return;
    }
    await убратьОдно(что, иУСобеседника);
  };

  /** Одна строка: звонок или сообщение, у себя или у обоих. */
  const убратьОдно = async (что, иУСобеседника, молча = false) => {
    const токен = localStorage.getItem('authToken');
    try {
      if (что.вид === 'звонок') {
        const ответ = await fetch(
          `https://unlimitedpossibilities12.org/api/calls/${что.id}?userId=${userId}&forBoth=${иУСобеседника}`,
          { method: 'DELETE', headers: { Authorization: `Bearer ${токен}` } }
        );
        if (!ответ.ok) throw new Error('сервер отказал: ' + ответ.status);
        setЗвонки((прежние) => прежние.filter((к) => к.звонок.id !== что.id));
        return;
      }
      if (иУСобеседника) {
        // у обоих — прежней дорогой, она же известит собеседника
        deleteMessage(что.id);
        return;
      }
      // только у себя — запись остаётся жить, но нам её больше не показывают
      const ответ = await fetch(
        `https://unlimitedpossibilities12.org/api/messages/${что.id}/hide`,
        { method: 'POST', headers: { Authorization: `Bearer ${токен}` } }
      );
      if (!ответ.ok) throw new Error('сервер отказал: ' + ответ.status);
      setMessages((прежние) => прежние.filter((м) => м.id !== что.id));
    } catch (ошибка) {
      console.warn('Убрать не удалось:', ошибка);
      if (!молча) toast.error('Could not delete');
      throw ошибка;   // набор сосчитает неудачи сам
    }
  };

  const лента = useMemo(() => {
    /* Пока переписка не пришла, карточки придерживаем: иначе в тайной
       сперва мелькают одни звонки, а сообщения выходят следом. */
    const всё = messages.length ? [...messages, ...звонки] : [...messages];
    всё.sort((а, б) => new Date(а.timestamp || 0) - new Date(б.timestamp || 0));
    return всё;
  }, [messages, звонки]);

  const длительность = (секунд) => {
    if (!секунд && секунд !== 0) return null;
    if (секунд < 60) return `${секунд} s`;
    const м = Math.floor(секунд / 60);
    const с = секунд % 60;
    return `${м}:${String(с).padStart(2, '0')}`;
  };
  // Отпечаток ключей (safety number) и предупреждение о смене ключа собеседника
  const [safetyNumber, setSafetyNumber] = useState(null);
  const [showSafetyNumber, setShowSafetyNumber] = useState(false);
  const [partnerKeyChanged, setPartnerKeyChanged] = useState(false);
  // Перевод на устройстве (секретный чат)
  const [showTranslationDialog, setShowTranslationDialog] = useState(false);
  const [chatMenuOpen, setChatMenuOpen] = useState(false);
  // Если картинка не открылась (файлы пропадают при переразвёртывании сервера),
  // показываем кружок с буквой вместо битого значка
  const [brokenAvatars, setBrokenAvatars] = useState({});
  const chatMenuRef = useRef(null);
  const [translationRefresh, setTranslationRefresh] = useState(0);

  // Запоминает ключ собеседника и поднимает предупреждение, если он изменился
  // по сравнению с тем, что видели раньше. Молчаливая замена ключа — это то,
  // как выглядит подмена (MITM), поэтому пользователь должен это увидеть.
  const notePartnerKey = (id, key) => {
    if (!id || !key) return;
    const storageKey = `public_key_${id}`;
    const previous = localStorage.getItem(storageKey);
    if (previous && previous !== key) {
      console.warn(`Partner ${id} public key CHANGED`);
      setPartnerKeyChanged(true);
      toast.warn('Your contact\'s encryption key has changed. Compare the fingerprint.', { autoClose: false });
    }
    localStorage.setItem(storageKey, key);
  };
  const [partnerAvatar, setPartnerAvatar] = useState(null);

  /*
    Номер собеседника для отправки и расшифровки.

    Он уже известен — его дают участники чата, — поэтому берём готовый.
    Раньше каждая отправка, подписка на ключи и расшифровка начинались с
    разбора имени чата («А_Б_secret») по «_»; у человека с «_» в имени
    получался обрезок, и всё падало с «Failed to fetch recipient: 404»:
    сообщение не уходило, картинки не открывались. Разбор имени оставлен
    запасным путём на случай, когда участников получить не удалось.
  */
  /*
    Время ставим в конец последней строки текста — короткая реплика тогда не
    занимает отдельную строку под подпись. Когда в чате включён перевод, под
    текстом появляется вторая строка, и время остаётся отдельной строкой,
    иначе оно наложилось бы на перевод. Вложения и голосовые — тоже отдельной.
  */
  const inlineTime = (msg) =>
    msg.messageType !== 'audio' && msg.messageType !== 'file' && !msg.fileUrl;
  const hasLocalTranslation = () => isLocalTranslationEnabled(chatIdParam);

  const partnerRef = useRef(null);
  const partnerNameRef = useRef('');
  const resolveRecipientId = async () => {
    if (partnerRef.current) return partnerRef.current;
    const partner = await findPartner(chatIdParam, localStorage.getItem('userId'));
    if (partner) {
      partnerRef.current = partner.id;
      partnerNameRef.current = nameOf(partner);
      return partner.id;
    }
    const name = partnerNameFromChat(chatNameRef.current, username);
    const response = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${name}`);
    if (!response.ok) throw new Error(`Failed to fetch recipient: ${response.status}`);
    const recipient = await response.json();
    partnerRef.current = recipient.id;
    return recipient.id;
  };

  /**
   * Ключ, которым открывается это сообщение.
   *
   * Чужое шифровали на наш ключ — открываем ключом отправителя, тем
   * самым, что был при отправке (publicKeyId сообщения). Своё шифровали
   * на ключ собеседника, а publicKeyId у него наш собственный, и брать
   * ключ по нему нельзя: пара не сойдётся.
   */
  const keyToOpen = async (message) => {
    const senderId = message?.sender?.id;
    if (senderId === userId) {
      const partnerId = await resolveRecipientId();
      return await keyForMessage(partnerId, null);
    }
    return await keyForMessage(senderId, message?.publicKeyId);
  };
  const typingTimerRef = useRef(null); // для таймера скрытия
  const messagesEndRef = useRef(null);
  const messageListRef = useRef(null);
  const fileInputRef = useRef(null);
  const mediaRecorderRef = useRef(null);
  const audioRefs = useRef({});
  const textAreaRef = useRef(null);
  const ffmpeg = useRef(new FFmpeg());
  const audioBlobRef = useRef(null);  // ← Добавление в начало компонента
  const { chatId: chatIdParam } = useParams();
  const navigate = useNavigate();
  const { userInteracted, handleInteraction } = useUserInteraction();
  const { usersStatus } = useUserStatusContext();
  const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
  const { setCurrentUserId: setNotificationUserId, setCurrentChatId } = usePrivateMessageNotification();
  const [fullScreenImage, setFullScreenImage] = useState(null);
  const [decryptedImageBlobs, setDecryptedImageBlobs] = useState({}); // { [msg.id]: blobUrl }

  // Инициализация libsodium и генерация ключей
  useEffect(() => {
    const initializeSodium = async () => {
      await sodium.ready;
      const storedKeyPair = localStorage.getItem(`keyPair_${userId}`);
      console.log(`Checking keyPair for userId: ${userId}, exists: ${!!storedKeyPair}`);

      let parsedKeyPair = null;
      let publicKeyBase64 = '';   // ← Объявляем заранее, чтобы избежать no-undef
      let privateKeyBase64 = '';  // ← Объявляем заранее

      if (storedKeyPair) {
        try {
          parsedKeyPair = JSON.parse(storedKeyPair);
          console.log(`Private key length: ${parsedKeyPair.privateKey?.length}, Public key length: ${parsedKeyPair.publicKey?.length}`);
        } catch (e) {
          console.error(`Failed to parse stored keyPair, regenerating...`, e);
          localStorage.removeItem(`keyPair_${userId}`);
          parsedKeyPair = null;
        }
      }

      if (parsedKeyPair) {
        privateKeyBase64 = parsedKeyPair.privateKey || '';
        publicKeyBase64 = parsedKeyPair.publicKey || '';

        // Проверяем, корректна ли длина ключа
        let privateKeyBytes;
        try {
          privateKeyBytes = sodium.from_base64(privateKeyBase64);
          console.log(`Private key bytes length: ${privateKeyBytes.length}`);
        } catch (e) {
          console.error(`Private key decode error for userId: ${userId}, error: ${e.message}`);
          privateKeyBytes = null;
        }

        // Если длина неверная — чистим строку и пробуем снова
        if (!privateKeyBytes || privateKeyBytes.length !== sodium.crypto_box_SECRETKEYBYTES) {
          console.warn(`Invalid private key length (${privateKeyBytes?.length || 'invalid'}), attempting cleanup`);

          privateKeyBase64 = privateKeyBase64
            .trim()
            .replace(/\s+/g, '')          // убираем все пробелы, табы
            .replace(/[\r\n]+/g, '');     // убираем переносы строк

          publicKeyBase64 = publicKeyBase64
            .trim()
            .replace(/\s+/g, '')
            .replace(/[\r\n]+/g, '');

          try {
            privateKeyBytes = sodium.from_base64(privateKeyBase64);
            console.log(`After cleanup - Private key bytes length: ${privateKeyBytes.length}`);

            if (privateKeyBytes.length === sodium.crypto_box_SECRETKEYBYTES) {
              // Успех — перезаписываем в localStorage чистые строки
              localStorage.setItem(`keyPair_${userId}`, JSON.stringify({
                publicKey: publicKeyBase64,
                privateKey: privateKeyBase64
              }));
              console.log(`Cleaned key pair saved back to localStorage`);
            } else {
              console.warn(`Cleanup failed - length still wrong: ${privateKeyBytes.length}, regenerating key pair`);
              localStorage.removeItem(`keyPair_${userId}`);
              parsedKeyPair = null; // заставим сгенерировать новый
            }
          } catch (cleanError) {
            console.error(`Cleanup attempt failed:`, cleanError);
            localStorage.removeItem(`keyPair_${userId}`);
            parsedKeyPair = null;
          }
        }

        // Если после всех проверок ключ валиден — используем
        if (parsedKeyPair) {
          setKeyPair({
            publicKey: publicKeyBase64,
            privateKey: privateKeyBase64
          });

          setPublicKeys((prev) => ({
            ...prev,
            [userId]: publicKeyBase64,
          }));
        }
      }

      // Если ключа нет или он был удалён — генерируем новый
      if (!parsedKeyPair) {
        console.log(`Generating new key pair for userId: ${userId}`);
        const newKeyPair = sodium.crypto_box_keypair();
        publicKeyBase64 = sodium.to_base64(newKeyPair.publicKey).trim();
        privateKeyBase64 = sodium.to_base64(newKeyPair.privateKey).trim();

        localStorage.setItem(`keyPair_${userId}`, JSON.stringify({
          publicKey: publicKeyBase64,
          privateKey: privateKeyBase64
        }));

        setKeyPair({
          publicKey: publicKeyBase64,
          privateKey: privateKeyBase64
        });

        setPublicKeys((prev) => ({
          ...prev,
          [userId]: publicKeyBase64,
        }));
      }

      // Force-sync key всегда при reload/update (уведомление партнёра)
      const currentPublicKey = keyPair?.publicKey || publicKeyBase64; // ← Теперь publicKeyBase64 всегда определена
      try {
        console.log(`Syncing public key for userId: ${userId}`);
        const syncResponse = await fetch(`https://unlimitedpossibilities12.org/api/public_keys`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId, publicKey: currentPublicKey }),
        });
        if (!syncResponse.ok) throw new Error(`Sync failed: ${syncResponse.status}`);
        console.log(`Key synced for userId: ${userId}`);

        // WS-broadcast для партнёра (он получит в /topic/publicKey/{recipientId})
        if (client && client.connected) {
          client.publish({
            destination: `/app/publicKey/${userId}`,
            body: JSON.stringify({ userId, publicKey: currentPublicKey }),
          });
          console.log('WS broadcast sent to partner');
        }

        toast.info('Key updated and synced with partner');  // Уведомление юзеру
      } catch (syncError) {
        console.error('Sync error:', syncError);
        toast.error('Key sync failed — retry chat');
      }
    };

    initializeSodium();
  }, [userId]);

  // Загрузка данных чата и сообщений
  useEffect(() => {
    const fetchChatData = async () => {
      if (!chatIdParam || !username || !keyPair) return;

      try {
        // Сообщения не ждут ни собеседника, ни ключей — запрос уходит сразу,
        // иначе лента появляется только через пару секунд
        const messagesPromise = getMessages(chatIdParam, {}, null, true);
        const chatData = await getChatById(chatIdParam);
        if (!chatData.isSecret) {
          toast.error('This is not a secret chat');
          navigate('/');
          return;
        }

        /*
          Собеседника берём из участников чата. Прежде его вычисляли
          разбором имени («А_Б_secret») по «_», а имя человека само может
          содержать «_»: разбор давал несуществующее имя, запрос возвращал
          пустой ответ, разбор ответа падал — и секретная переписка
          закрывалась с выбросом на главную. Старый путь оставлен запасным.
        */
        let interlocutorName;
        let recipient;
        const partner = await findPartner(chatIdParam, localStorage.getItem('userId'));
        if (partner) {
          interlocutorName = nameOf(partner);
          recipient = partner;
        } else {
          interlocutorName = partnerNameFromChat(chatData.name, username);
          const recipientResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${interlocutorName}`);
          if (!recipientResponse.ok) {
            throw new Error(`Could not find "${interlocutorName}"`);
          }
          recipient = await recipientResponse.json();
        }
        const recipientId = recipient.id;

        const normalizedChatName = generateChatName(username, interlocutorName, true);
        setChatName(normalizedChatName);
        setDisplayChatName(`Secret Chat with ${interlocutorName}`);
        setChatId(chatIdParam);
        setCurrentChatId(chatIdParam);

        setPartnerId(recipientId);
        partnerRef.current = recipientId;
        partnerNameRef.current = interlocutorName;
        setPartnerAvatar(recipient.avatar); // предполагается, что в ответе есть поле avatar (строка типа "/uploads/avatars/xxx.jpg")

        /*
          Ключи заводятся при первом входе в приложение. Если собеседник ещё
          ни разу не заходил, шифровать нечем: сервер отвечает, что открытого
          ключа нет. Прежде это попадало в общий перехват ошибок, и человека
          просто выбрасывало на главную без единого слова — объясняем, в чём
          дело, вместо молчаливого возврата.
        */
        let recipientPublicKey;
        try {
          recipientPublicKey = await getPublicKey(recipientId);
        } catch (keyError) {
          console.warn('No public key for partner yet:', recipientId, keyError);
          toast.error(`${interlocutorName} has not signed in yet — a secret chat cannot be opened`);
          navigate('/');
          return;
        }
        setPublicKeys(prev => ({ ...prev, [recipientId]: recipientPublicKey }));
        notePartnerKey(recipientId, recipientPublicKey);

        const initialMessages = await messagesPromise;
        const senderIds = [...new Set(initialMessages.map(m => m.sender.id))];
        const keyPromises = senderIds
          .filter(id => id !== userId && !publicKeys[id])
          .map(id => getPublicKey(id).then(pk => ({ senderId: id, publicKey: pk })));
        const newKeys = await Promise.all(keyPromises);
        setPublicKeys(prev => ({ ...prev, ...Object.fromEntries(newKeys.map(k => [k.senderId, k.publicKey])) }));

        // Разбираем каждое сообщение по отдельности: лента должна
        // показаться сразу, а не ждать, пока расшифруется всё
        const decryptOne = async (msg) => {
            // === ТЕКСТ ===
            if (msg.messageType === 'text' && msg.encryptedContent && msg.nonce) {
              if (msg.sender.id === userId) {
                const localContent = getOwnMessageContent(chatIdParam, userId, msg.clientMessageId);
                return { ...msg, content: localContent || 'Content unavailable', delivered: msg.delivered_status, read: msg.read_status };
              }
              try {
                const publicKey = await getPublicKey(msg.sender.id, msg.publicKeyId);
                const decrypted = await decryptMessage(msg.encryptedContent, msg.nonce, msg.sender.id, msg.timestamp, publicKey);
                return { ...msg, content: decrypted, delivered: msg.delivered_status, read: msg.read_status };
              } catch (e) {
                return { ...msg, content: 'Decryption failed', delivered: msg.delivered_status, read: msg.read_status };
              }
            }

            // === ФАЙЛЫ ===
            if (msg.messageType === 'file') {
              let fileName = 'Unknown file';
              let contentType = 'application/octet-stream';
              let fileUrl = msg.fileUrl || msg.file_url;
              let caption = '';

              // Свои файлы — из localStorage
              if (msg.sender.id === userId) {
                const key = `fileContent_${chatIdParam}_${userId}_${msg.clientMessageId}`;
                const stored = localStorage.getItem(key);
                if (stored) {
                  const fileData = JSON.parse(stored);
                  return { ...msg, ...fileData, content: fileData.caption || '', delivered: msg.delivered_status, read: msg.read_status };
                }
                // Попытка по server id
                const key2 = `fileContent_${chatIdParam}_${userId}_${msg.id}`;
                const stored2 = localStorage.getItem(key2);
                if (stored2) {
                  const fileData = JSON.parse(stored2);
                  return { ...msg, ...fileData, content: fileData.caption || '', delivered: msg.delivered_status, read: msg.read_status };
                }
              }

              // Расшифровка метаданных
              if (msg.encryptedContent && msg.nonce) {
                try {
                  const publicKey = await keyToOpen(msg);
                  const decryptedMeta = await decryptMessage(
                    msg.encryptedContent,
                    msg.nonce,
                    msg.sender.id,
                    msg.timestamp,
                    publicKey
                  );
                  const meta = JSON.parse(decryptedMeta);
                  fileName = meta.fileName;
                  contentType = meta.contentType || meta.mimeType || 'application/octet-stream';
                  if (meta.caption) caption = meta.caption;
                } catch (e) {
                  console.error('File metadata decrypt failed:', e);
                  fileName = 'Decryption failed';
                }
              }

              // ПОЛУЧИТЬ fileUrl, если нет
              if (!fileUrl && msg.id) {
                try {
                  console.log(`[FILE] History load: fetching metadata for ${msg.id}`);
                  const metadata = await fetchFileMetadata(msg.id);
                  fileUrl = metadata.fileUrl;
                  console.log(`[FILE] Got fileUrl: ${fileUrl}`);
                } catch (e) {
                  console.error(`[FILE] Failed to fetch metadata for ${msg.id}:`, e);
                }
              }

              return {
                ...msg,
                fileName,
                contentType,
                fileUrl,
                content: caption || '',
                delivered: msg.delivered_status,
                read: msg.read_status
              };
            }

            // === АУДИО — РАСШИФРОВКА ПРИ ЗАГРУЗКЕ (БЕЗ fetchFileMetadata) ===
            if (msg.messageType === 'audio') {
              console.log('[AUDIO] Raw message:', {
                id: msg.id,
                fileUrl: msg.fileUrl,
                file_url: msg.file_url,
                nonce: msg.nonce,
                encryptedContent: msg.encryptedContent,
                publicKeyId: msg.publicKeyId,
                senderId: msg.sender.id
              });

              let fileName = 'audio.mp3';
              let contentType = 'audio/mpeg';
              let fileUrl = msg.fileUrl || msg.file_url;

              // --- МЕТАДАННЫЕ ---
              if (msg.encryptedContent && msg.nonce) {
                try {
                  const publicKey = await keyToOpen(msg);

                  const decryptedMeta = await decryptMessage(
                    msg.encryptedContent,
                    msg.nonce,
                    msg.sender.id,
                    msg.timestamp,
                    publicKey
                  );
                  const meta = JSON.parse(decryptedMeta);
                  fileName = meta.fileName || 'audio.mp3';
                  console.log('[AUDIO] Decrypted meta:', meta);
                } catch (e) {
                  console.error('[AUDIO] Meta decrypt failed:', e);
                  fileName = 'decryption_failed.mp3';
                }
              }

              // --- ЕСЛИ fileUrl НЕТ — БЕРЁМ ИЗ fetchFileMetadata ---
              if (!fileUrl && msg.nonce) {
                console.log(`[AUDIO] fileUrl missing → fetching metadata for ${msg.id}`);
                try {
                  const metadata = await fetchFileMetadata(msg.id);
                  fileUrl = metadata.fileUrl;
                  console.log(`[AUDIO] Got fileUrl from metadata: ${fileUrl}`);
                } catch (e) {
                  console.error(`[AUDIO] Failed to get fileUrl from metadata:`, e);
                  fileUrl = null;
                }
              }

              // --- РАСШИФРОВКА АУДИО ---
              if (fileUrl && msg.nonce && !fileUrl.startsWith('blob:')) {
                console.log(`[AUDIO] Decrypting: ${msg.id}`);
                try {
                  const decryptedBlobUrl = await decryptAudioForPlayback(
                    msg.id,
                    fileUrl,
                    msg.nonce,
                    msg.publicKeyId,
                    msg.sender.id
                  );
                  fileUrl = decryptedBlobUrl;
                  console.log(`[AUDIO] SUCCESS: ${msg.id} → ${decryptedBlobUrl}`);
                } catch (e) {
                  console.error(`[AUDIO] decryptAudioForPlayback failed:`, e);
                }
              } else if (!fileUrl) {
                console.warn(`[AUDIO] NO fileUrl for ${msg.id} — cannot decrypt`);
              }

              return {
                ...msg,
                fileName,
                contentType,
                fileUrl,
                file_url: fileUrl,
                delivered: msg.delivered_status,
                read: msg.read_status,
                _audioReady: fileUrl?.startsWith('blob:')
              };
            }

            return { ...msg, delivered: msg.delivered_status, read: msg.read_status };
        };

        // Что сервер прислал о вложениях — запоминаем, чтобы не спрашивать
        for (const м of initialMessages) {
          /* Ссылку берём из сведений: у части вложений в самом сообщении
             её нет, она только там. */
          const ссылка = м && (м.fileMetaUrl || м.fileUrl);
          if (м && м.fileNonce && ссылка) {
            сведенияОВложениях.current.set(String(м.id), {
              fileUrl: ссылка, nonce: м.fileNonce, publicKeyId: м.filePublicKeyId || null,
            });
          }
        }

        // Разбираем всю переписку, прежде чем показать хоть что-то: по
        // одному сообщения появлялись рывками и расползались
        const decryptedMessages = await Promise.all(initialMessages.map(async (msg) => {
          try {
            return await decryptOne(msg);
          } catch (e) {
            console.error(`Не удалось разобрать сообщение ${msg.id}:`, e);
            return { ...msg, delivered: msg.delivered_status, read: msg.read_status };
          }
        }));

        // Снимки — тем же заходом, а не отдельным кругом после показа
        const looksLikeImage = (msg) =>
          msg.messageType === 'file' && msg.fileUrl && !msg.fileUrl.startsWith('blob:') &&
          (msg.contentType?.startsWith('image/') ||
            (msg.fileName && /\.(jpg|jpeg|png|gif|webp)$/i.test(msg.fileName)));

        const pictures = await Promise.all(
          decryptedMessages.filter(looksLikeImage).map(async (msg) => {
            try {
              return await decryptImageForPreview(
                msg.id, msg.fileUrl, msg.nonce, msg.publicKeyId || null, msg.sender.id
              );
            } catch (e) {
              console.warn(`Снимок ${msg.id} разобрать не вышло:`, e);
              return null;
            }
          })
        );

        // И ждём, пока браузер их раскодирует: иначе снимок дорисовывается
        // уже на экране и пузырь подпрыгивает
        await Promise.all(pictures.filter(Boolean).map((url) => new Promise((done) => {
          const picture = new Image();
          picture.onload = done;
          picture.onerror = done;
          picture.src = url;
        })));

        setMessages(decryptedMessages);
        setTimeout(() => {
          scrollToBottom(false);
          didInitialScroll.current = true;
        }, 0);
      } catch (error) {
        console.error('Error fetching chat data:', error);
        toast.error('Failed to load chat');
        navigate('/');
      }
    };

    fetchChatData();

    const storedUserId = localStorage.getItem('userId');
    if (storedUserId) {
      const uid = parseInt(storedUserId, 10);
      setContextUserId(uid);
      setNotificationUserId(uid);
    }

    return () => setCurrentChatId(null);
  }, [chatIdParam, username, keyPair, navigate, setContextUserId, setNotificationUserId, setCurrentChatId]);

  // WebSocket-подключение
  useEffect(() => {
    if (!chatId || !userId) {
      console.warn('WebSocket setup skipped: chatId or userId missing', { chatId, userId });
      return;
    }

    let reconnectTimeout;
    // Ушли с экрана — соединение больше не поднимаем. Отключение приходит
    // с задержкой, уже после уборки, и прежде оно успевало поставить таймер
    // переподключения: клиент оживал через две секунды и оставался висеть
    // навсегда. Такие «зомби» копились с каждым заходом в секретный чат и
    // занимали соединения, нужные обычным запросам.
    let closedForGood = false;
    let жалобаНаСвязь = null;   // отложенная жалоба: вдруг связь вернётся сама
    const stompClientInstance = new Client({
      connectHeaders: stompAuthHeaders(),
      webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
      connectTimeout: 30000,
      reconnectDelay: 0,
      onConnect: async () => {
        console.log('Connected to WebSocket for SecretPrivateChat at:', new Date().toISOString());
        setClient(stompClientInstance);

        stompClientInstance.subscribe(`/topic/chat/${chatId}/messages`, async (message) => {
          const newMessage = JSON.parse(message.body);
          console.log('NewMessage:', newMessage);

          // Skip if already processed (avoid multiples)
          if (processedMessages.has(newMessage.id)) {
            console.log(`Skipped duplicate NewMessage for id=${newMessage.id}`);
            return;
          }
          setProcessedMessages(prev => new Set([...prev, newMessage.id]));

          // ← ОБРАБАТЫВАЕМ УДАЛЕНИЕ
          if (newMessage.content === 'Message deleted') {
            setMessages(prev => prev.filter(m => m.id !== newMessage.id));
            return;
          }

          // ← ЕСЛИ СООБЩЕНИЕ ОТ НАС — обновляем статусы (галочки)
          if (newMessage.sender?.id === userId) {
            console.log('Updating own message status (delivered/read):', newMessage.id);
            setMessages(prev => prev.map(m =>
              m.id === newMessage.id
                ? { ...m, delivered: newMessage.delivered_status ?? m.delivered, read: newMessage.read_status ?? m.read }
                : m
            ));
            // Trigger preload if audio (use current state fileUrl to avoid null)
            if (newMessage.messageType === 'audio') {
              const currentMsg = messages.find(m => m.id === newMessage.id);
              if (currentMsg && currentMsg.fileUrl) {
                setTimeout(() => {
                  preloadAudio(newMessage.id, currentMsg.fileUrl, currentMsg);
                }, 100);
              } else {
                console.warn(`No fileUrl in state for own audio ${newMessage.id}`);
              }
            }
            return;
          }

          // ← ТЕКСТ
          if (newMessage.messageType === 'text' && newMessage.encryptedContent && newMessage.nonce) {
            try {
              const cleanedEncryptedContent = newMessage.encryptedContent.replace(/\n/g, '');
              const cleanedNonce = newMessage.nonce.replace(/\n/g, '');

              const publicKey = await keyToOpen(newMessage);

              const decryptedContent = await decryptMessage(
                cleanedEncryptedContent,
                cleanedNonce,
                newMessage.sender.id,
                newMessage.timestamp,
                publicKey
              );

              updateMessages({
                ...newMessage,
                content: decryptedContent,
                delivered: newMessage.delivered_status ?? false,
                read: newMessage.read_status ?? false,
              });
            } catch (error) {
              console.error('Decryption failed for new message:', newMessage.id, error);
              updateMessages({
                ...newMessage,
                content: 'Decryption failed',
                delivered: newMessage.delivered_status ?? false,
                read: newMessage.read_status ?? false,
              });
              toast.error(`Failed to decrypt message: ${error.message}`);
            }

          // ← ФАЙЛЫ И АУДИО (add trigger preload)
          } else if (newMessage.messageType === 'file' || newMessage.messageType === 'audio') {
            try {
              let fileName = 'Unknown file';
              let contentType = 'application/octet-stream';
              let fileUrl = newMessage.fileUrl || newMessage.file_url || null;
              let caption = '';

              if (newMessage.encryptedContent && newMessage.nonce) {  // Now has for audio
                const publicKey = await keyToOpen(newMessage);

                const decryptedMetadata = await decryptMessage(
                  newMessage.encryptedContent,
                  newMessage.nonce,
                  newMessage.sender.id,
                  newMessage.timestamp,
                  publicKey
                );
                const metadata = JSON.parse(decryptedMetadata);
                fileName = metadata.fileName;
                contentType = metadata.mimeType || metadata.contentType || 'application/octet-stream';
                if (metadata.caption) caption = metadata.caption;
              }

              if (!fileUrl && newMessage.id) {
                try {
                  const meta = await fetchFileMetadata(newMessage.id);
                  fileUrl = meta.fileUrl;
                } catch (e) {
                  console.error('Failed to fetch fileUrl:', e);
                }
              }

              const updatedNewMessage = {
                ...newMessage,
                fileName,
                contentType,
                fileUrl,
                content: caption || newMessage.content || '',
                delivered: newMessage.delivered_status ?? false,
                read: newMessage.read_status ?? false,
                // Ensure sender for preload
                sender: newMessage.sender || { id: newMessage.senderId || 0 },  // Fallback
              };
              updateMessages(updatedNewMessage);

              // Trigger preload/decrypt after update for audio
              if (newMessage.messageType === 'audio') {
                setTimeout(() => preloadAudio(newMessage.id, fileUrl, updatedNewMessage), 100);
              }
            } catch (error) {
              console.error(`Failed to decrypt file/audio metadata ID=${newMessage.id}:`, error);
              updateMessages({
                ...newMessage,
                fileName: 'Decryption failed',
                contentType: null,
                fileUrl: null,
                delivered: newMessage.delivered_status ?? false,
                read: newMessage.read_status ?? false,
              });
              toast.error('Failed to decrypt file name');
            }

          // ← ДРУГИЕ ТИПЫ (например, системные)
          } else {
            updateMessages({
              ...newMessage,
              delivered: newMessage.delivered_status ?? false,
              read: newMessage.read_status ?? false,
            });
          }
        });

        // ← ПОДПИСКА НА СТАТУСЫ ДОСТАВКИ/ПРОЧТЕНИЯ
        stompClientInstance.subscribe(`/topic/messageStatus/${chatId}`, (statusMessage) => {
          const statusUpdate = JSON.parse(statusMessage.body);
          console.log('Received status update:', statusUpdate);

          setMessages((prevMessages) =>
            prevMessages.map((msg) =>
              msg.id === statusUpdate.messageId
                ? {
                    ...msg,
                    delivered: statusUpdate.delivered_status ?? msg.delivered,
                    read: statusUpdate.read_status ?? msg.read,
                  }
                : msg
            )
          );
        });

        // ← НОВОЕ: Подписка на typing
        /* Убранный звонок: сервер говорит каждой стороне о её записи, и
           карточка уходит из переписки сразу, без перезахода. */
        stompClientInstance.subscribe(`/topic/calls/${userId}/removed`, (весть) => {
          try {
            const данные = JSON.parse(весть.body);
            const номер = данные && данные.removedCallId;
            if (!номер) return;
            setЗвонки((прежние) => прежние.filter((к) => к.звонок.id !== номер));
          } catch (ошибка) {
            console.warn('Весть об убранном звонке не разобрана:', ошибка);
          }
        });

        stompClientInstance.subscribe(`/topic/chat/${chatId}/typing`, (message) => {
          try {
            const data = JSON.parse(message.body);
            console.log('Typing event received in SecretChat:', data);

            const type = data.messageType;
            const sender = data.sender;

            if (sender && sender.id !== userId) {
              if (type === 'TYPING') {
                setTypingName(nameOf(sender) || 'Собеседник');
                setIsTyping(true);

                if (typingTimerRef.current) {
                  clearTimeout(typingTimerRef.current);
                }
                typingTimerRef.current = setTimeout(() => {
                  setIsTyping(false);
                  console.log('Typing indicator hidden by timeout in SecretChat');
                }, 2500);
              } else if (type === 'STOP_TYPING') {
                setIsTyping(false);
                if (typingTimerRef.current) {
                  clearTimeout(typingTimerRef.current);
                }
                console.log('STOP_TYPING received in SecretChat');
              }
            }
          } catch (err) {
            console.error('Error parsing typing event in SecretChat:', err);
          }
        });

        // ← ПОДПИСКА НА ОБНОВЛЕНИЕ КЛЮЧЕЙ
        if (chatName) {
          {
            try {
              const recipientId = await resolveRecipientId();

              stompClientInstance.subscribe(`/topic/publicKey/${recipientId}`, (message) => {
                try {
                  const updatedPublicKey = JSON.parse(message.body);
                  console.log(`Received updated public key for recipientId=${recipientId}: ${updatedPublicKey.publicKey}`);
                  setPublicKeys((prev) => ({
                    ...prev,
                    [recipientId]: updatedPublicKey.publicKey,
                  }));
                  notePartnerKey(recipientId, updatedPublicKey.publicKey);
                } catch (error) {
                  console.error(`Error processing public key update for recipientId=${recipientId}:`, error);
                  toast.error('Failed to process public key update');
                }
              });
              console.log(`Subscribed to public key updates for recipientId=${recipientId}`);
            } catch (error) {
              console.error('Failed to fetch recipientId for public key subscription:', error);
              toast.error('Failed to subscribe to public key updates');
            }
          }
        }
      },

      onWebSocketError: (error) => {
        console.error('WebSocket error in SecretPrivateChat at:', new Date().toISOString(), error);
        /* Молчим о мимолётном обрыве: уход в другую переписку закрывает
           сокет, и человек видел «WebSocket error» просто от перехода.
           Связь поднимается сама; жалуемся, только если и через
           пятнадцать секунд её нет. В обычном чате так и было. */
        if (closedForGood) return;
        clearTimeout(жалобаНаСвязь);
        жалобаНаСвязь = setTimeout(() => {
          if (!closedForGood && !stompClientInstance.connected) {
            toast.error('No connection to the server');
          }
        }, 15000);
      },
      onStompError: (frame) => {
        console.error('STOMP error in SecretPrivateChat:', frame);
        if (!closedForGood) toast.error('STOMP protocol error');
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected in SecretPrivateChat');
        if (closedForGood) return;
        setClient(null);
        reconnectTimeout = setTimeout(() => {
          if (closedForGood) return;
          console.log('Reconnecting WebSocket for SecretPrivateChat');
          stompClientInstance.activate();
        }, 2000);
      },
    });

    stompClientInstance.activate();

    return () => {
      console.log('Deactivating WebSocket in SecretPrivateChat');
      closedForGood = true;
      clearTimeout(жалобаНаСвязь);
      if (stompClientInstance.active) {
        stompClientInstance.deactivate();
      }
      if (reconnectTimeout) {
        clearTimeout(reconnectTimeout);
      }
      if (typingTimerRef.current) {
        clearTimeout(typingTimerRef.current);
      }
    };
  }, [chatId, userId, chatName, username]);

  // Отметка сообщений как прочитанных
  /* Отметка «прочитано».
   *
   * Прежде проверка снимала саму себя первым же разом (clearInterval), и
   * заново её заводила только смена messages или client. Если вкладка
   * уходила в глубину и браузер её замораживал, а соединение после
   * пробуждения поднималось тем же объектом, отметка не уходила вовсе:
   * у собеседника так и висела одна галочка, пока страницу не обновят.
   * Теперь проверка живёт, пока есть непрочитанное, и сама умолкает,
   * когда непрочитанного не осталось. Заодно отмечаем сразу, как только
   * вкладку снова открыли. */
  useEffect(() => {
    const отметить = () => {
      if (client && client.connected && messages.length > 0 &&
          messages.some((msg) => !isReadFlag(msg) && msg.sender && msg.sender.id !== userId)) {
        markAllMessagesAsRead(messages);
      }
    };
    const interval = setInterval(отметить, 1000);
    const приОткрытии = () => { if (document.visibilityState === 'visible') отметить(); };
    document.addEventListener('visibilitychange', приОткрытии);
    window.addEventListener('focus', приОткрытии);

    return () => {
      clearInterval(interval);
      document.removeEventListener('visibilitychange', приОткрытии);
      window.removeEventListener('focus', приОткрытии);
    };
  }, [messages, client, userId]);

  // Обработка взаимодействия пользователя
  useEffect(() => {
    const handleClick = () => {
      handleInteraction();
    };
    document.addEventListener('click', handleClick);
    return () => document.removeEventListener('click', handleClick);
  }, [handleInteraction]);

  // Прокрутка к последнему сообщению
  useEffect(() => {
    if (messages.length > 0) {
      setTimeout(() => {
        /* Пока читаешь выше, новое сообщение ленту не утаскивает — только
           зажигает число на кружке. Первый раз уезжаем всегда. */
        const список = messageListRef.current;
        const внизу = !список
          || список.scrollHeight - список.scrollTop - список.clientHeight < 120;
        /* В конец на первой загрузке — только если возвращаться некуда. */
        const естьКуда = !!оставленноеМесто.current && ждёмВозврата.current;
        const попросили = своёОтправлено.current;
        своёОтправлено.current = false;
        if ((!didInitialScroll.current && !естьКуда) || внизу || попросили) {
          scrollToBottom(didInitialScroll.current, попросили);
        }
        didInitialScroll.current = true;
      }, 0);
    }
  }, [messages.length]);

  // Вычисление символов на строку
  useEffect(() => {
    calculateCharPerLine();
    window.addEventListener('resize', calculateCharPerLine);
    return () => window.removeEventListener('resize', calculateCharPerLine);
  }, []);

  // Обработчики перетаскивания для аудиоплеера
  useEffect(() => {
    if (isDragging) {
      window.addEventListener('mousemove', handleDragMove);
      window.addEventListener('mouseup', handleDragEnd);
    }
    return () => {
      window.removeEventListener('mousemove', handleDragMove);
      window.removeEventListener('mouseup', handleDragEnd);
    };
  }, [isDragging]);

  // Предзагрузка аудио
  useEffect(() => {
    const audioMessages = messages.filter((msg) => msg.messageType === 'audio' && msg.fileUrl);
    audioMessages.slice(-10).forEach(async (msg) => {  // async для preloadAudio
      if (
        !audioProgress[msg.id]?.isReady &&
        !audioProgress[msg.id]?.isPreloading
      ) {
        await preloadAudio(msg.id, msg.fileUrl, msg);  // + msg param
      }
    });
  }, [messages]);

  useEffect(() => {
    const handleClickOutside = (e) => {
      if (contextMenuRef.current && !contextMenuRef.current.contains(e.target)) {
        setContextMenu({ visible: false });
      }
    };

    if (contextMenu.visible) {
      document.addEventListener('mousedown', handleClickOutside);
      document.addEventListener('touchstart', handleClickOutside);
    }
  const лента = messageListRef.current;
  const стоп = (с) => с.preventDefault();
  if (contextMenu.visible && лента) {
      /* Пока меню открыто, лента не едет: иначе строка уходит
         из-под меню, а оно стоит намертво в окне. */
      лента.addEventListener('wheel', стоп, { passive: false });
      лента.addEventListener('touchmove', стоп, { passive: false });
  }

    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('touchstart', handleClickOutside);
    if (лента) {
          лента.removeEventListener('wheel', стоп);
          лента.removeEventListener('touchmove', стоп);
      }
  };
  }, [contextMenu.visible]);

  // Первый раз — сразу в конец: плавная прокрутка через всю переписку
  // проматывает её на глазах. Плавно уезжаем только к новым сообщениям.
  const didInitialScroll = useRef(false);

  // Пока человек у последнего сообщения, лента держится за низ: превью
  // ссылки, снимок или ответ сервера подрастают — и без этого низ уезжал,
  // а потом всё рывком вставало на место
  useEffect(() => {
    const list = messageListRef.current;
    if (!list) return undefined;

    let pinned = true;
    const nearBottom = () => list.scrollHeight - list.scrollTop - list.clientHeight < 40;
    const remember = () => {
      pinned = nearBottom();
      /* Кружок «вниз» показываем, отойдя от конца на пол-экрана, и
         запоминаем место, чтобы вернуться сюда при следующем заходе. */
      setДалекоОтНиза(list.scrollHeight - list.scrollTop - list.clientHeight > 300);
      if (pinned) setНовых(0);   // дошли до конца — считать нечего
      запомнитьМесто(list);
    };
    const keepDown = () => { if (pinned) list.scrollTop = list.scrollHeight; };

    list.addEventListener('scroll', remember, { passive: true });
    // Картинки и превью меняют высоту уже после вставки в разметку
    list.addEventListener('load', keepDown, true);
    const watcher = new MutationObserver(keepDown);
    watcher.observe(list, { childList: true, subtree: true, characterData: true });

    return () => {
      list.removeEventListener('scroll', remember);
      list.removeEventListener('load', keepDown, true);
      watcher.disconnect();
    };
  }, []);


  /** Виден ли кружок «вниз»: только когда лента поднята от конца. */
  const [далекоОтНиза, setДалекоОтНиза] = useState(false);

  /** Где эту переписку оставили в прошлый раз. */
  const ключМеста = () => {
    const изАдреса = (window.location.hash.match(/chat\/(\d+)/) || [])[1];
    return `up12-place-${chatId || изАдреса || ''}`;
  };

  /* Место читаем один раз при открытии: дальше лента сама его затрёт,
     уехав в конец, — а нам надо вернуть человека туда, где он вышел. */
  const оставленноеМесто = useRef((() => {
    try {
      const место = JSON.parse(localStorage.getItem(
        `up12-place-${(window.location.hash.match(/chat\/(\d+)/) || [])[1] || ''}`) || 'null');
      return место && !место.внизу && место.строка ? место : null;
    } catch (ошибка) {
      return null;
    }
  })());

  /** Пока не вернулись на место, вниз не уезжаем и место не переписываем. */
  const ждёмВозврата = useRef(оставленноеМесто.current != null);

  /* Человек тронул ленту, нажал кружок или отправил сообщение — дальше
     он сам решает, где быть, и мы больше не держим место. */
  const человекРешил = useRef(false);

  /** Своё отправленное сообщение уводит ленту в конец — и только оно. */
  const своёОтправлено = useRef(false);

  /* Пока возвращаемся на место, ленту не показываем: иначе в первое
     мгновение мелькает самый низ. */
  const [скрытаЛента, setСкрытаЛента] = useState(ждёмВозврата.current);

  /** Запомнить, докуда долистали. */
  const запомнитьМесто = (список) => {
    if (ждёмВозврата.current) return;
    try {
      const внизу = список.scrollHeight - список.scrollTop - список.clientHeight < 40;
      /* Запоминаем строку, а не пиксели: при следующем заходе высота ленты
         другая — картинки и превью грузятся заново, и отложенные пиксели
         означают уже не то место, а то и вовсе лежат за концом. */
      let верхняя = null;
      if (!внизу) {
        for (const узел of список.querySelectorAll('[data-row]')) {
          if (узел.offsetTop + узел.offsetHeight > список.scrollTop) { верхняя = узел; break; }
        }
      }
      localStorage.setItem(ключМеста(), JSON.stringify({
        внизу,
        строка: верхняя ? верхняя.getAttribute('data-row') : null,
        зазор: верхняя ? Math.round(верхняя.offsetTop - список.scrollTop) : 0,
      }));
    } catch (ошибка) {
      /* память браузера недоступна — не беда */
    }
  };

  /**
   * Вернуть ленту туда, где её оставили.
   *
   * Повторяем полторы секунды: картинки и превью подрастают уже после
   * первой отрисовки и сдвигают ленту. Потом отпускаем — дальше человек
   * листает сам, и место снова запоминается.
   */
  useEffect(() => {
    if (!ждёмВозврата.current) return undefined;
    const куда = оставленноеМесто.current;
    let поставлено = 0;
    const вернуть = () => {
      const список = messageListRef.current;
      if (!список) return;
      /* Ищем ту самую строку: пока её нет, переписка ещё не вся. */
      const узел = список.querySelector(`[data-row="${куда.строка}"]`);
      if (!узел) return;
      список.scrollTop = Math.max(0, узел.offsetTop - (куда.зазор || 0));
      поставлено += 1;
      /* Показываем не с первой постановки, а когда лента устоялась:
             иначе видно, как она доправляется на пару пикселей. */
          if (поставлено >= 3) setСкрытаЛента(false);
      /* Ещё несколько раз: картинки подрастают и сдвигают ленту. */
      if (поставлено >= 6) отпустить();
    };
    const часы = setInterval(вернуть, 200);
    const отпустить = () => {
      clearInterval(часы);
      clearTimeout(срок);
      clearTimeout(показать);
      ждёмВозврата.current = false;
    };
    const срок = setTimeout(отпустить, 12000);
    const показать = setTimeout(() => setСкрытаЛента(false), 3000);
    /* Тронул ленту сам — дальше он хозяин. */
    const сам = () => { человекРешил.current = true; отпустить(); };
    const список = messageListRef.current;
    if (список) {
      список.addEventListener('wheel', сам, { passive: true });
      список.addEventListener('touchstart', сам, { passive: true });
    }
    return () => {
      clearInterval(часы);
      clearTimeout(срок);
      clearTimeout(показать);
      if (список) {
        список.removeEventListener('wheel', сам);
        список.removeEventListener('touchstart', сам);
      }
    };
  }, []);


  /** Сколько сообщений пришло, пока мы не у конца ленты. */
  const [новых, setНовых] = useState(0);
  const былоСообщений = useRef(0);

  useEffect(() => {
    const список = messageListRef.current;
    const внизу = !список || список.scrollHeight - список.scrollTop - список.clientHeight < 60;
    const прибыло = messages.length - былоСообщений.current;
    былоСообщений.current = messages.length;
    /* Первую загрузку не считаем: это не «новые», это вся переписка. */
    if (!didInitialScroll.current) return;
    if (прибыло > 0 && !внизу) setНовых((было) => было + прибыло);
    if (внизу) setНовых(0);
  }, [messages.length]);

  /** К последнему сообщению — по кружку. */
  const кПоследнему = () => {
    ждёмВозврата.current = false;
    человекРешил.current = true;
    setНовых(0);
    scrollToBottom(true, true);
  };

  const scrollToBottom = (smooth = true, поПросьбе = false) => {
    if (ждёмВозврата.current) return;   // сперва вернёмся туда, где вышли
    /* Человек читает выше — ленту вниз не утаскиваем. Уедем, только если
       он сам попросил: нажал кружок или отправил своё сообщение. */
    if (!поПросьбе) {
      const лист = messageListRef.current;
      const внизу = !лист || лист.scrollHeight - лист.scrollTop - лист.clientHeight < 60;
      if (!внизу) return;
    }
    if (messagesEndRef.current && messageListRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto' });
    }
  };

  const handleAvatarClick = (senderId) => {
    navigate(`/user/${senderId}`);
  };

  const markAllMessagesAsRead = (updatedMessages) => {
    if (Array.isArray(updatedMessages)) {
      updatedMessages.forEach((msg) => {
        if (msg.sender.id !== userId && !isReadFlag(msg)) {
          if (client && client.connected) {
            client.publish({ destination: `/app/readMessage/${chatId}`, body: JSON.stringify(msg.id) });
            setMessages((prevMessages) =>
              prevMessages.map((message) =>
                message.id === msg.id ? { ...message, read: true } : message
              )
            );
          }
        }
      });
    }
  };

  const updateMessages = (newMessage) => {
    setMessages(prev => {
      // УДАЛЕНИЕ
      if (newMessage.content === 'Message deleted') {
        return prev.filter(m => m.id !== newMessage.id);
      }

      // ОБНОВЛЕНИЕ ПО id
      const existingIdx = prev.findIndex(m => m.id === newMessage.id);
      if (existingIdx !== -1) {
        const oldMsg = prev[existingIdx];
        const oldFileUrl = oldMsg.fileUrl;

        // ← ДЛЯ СВОИХ СООБЩЕНИЙ: заменяем blob: на server URL
        if (newMessage.sender.id === userId && oldFileUrl?.startsWith('blob:')) {
          const updated = [...prev];
          updated[existingIdx] = {
            ...updated[existingIdx],
            ...newMessage,
            content: newMessage.content || updated[existingIdx].content || '',
            fileUrl: newMessage.fileUrl || newMessage.file_url,
            file_url: newMessage.file_url || newMessage.fileUrl,
          };
          URL.revokeObjectURL(oldFileUrl);
          console.log(`Replaced temp blob with server URL for ${newMessage.id}`);
          return updated;
        }

        const updated = [...prev];
        updated[existingIdx] = {
          ...updated[existingIdx],
          ...newMessage,
          content: newMessage.content || updated[existingIdx].content || '',
        };
        if (oldFileUrl?.startsWith('blob:')) {
          URL.revokeObjectURL(oldFileUrl);
          console.log(`Revoked temp blob in updateMessages for ${newMessage.id}`);
        }
        return updated;
      }

      // ЗАМЕНА ВРЕМЕННОГО СООБЩЕНИЯ
      if (newMessage.sender.id === userId && newMessage.clientMessageId) {
        const tempIdx = prev.findIndex(m => m.id === newMessage.clientMessageId);
        if (tempIdx !== -1) {
          const tempMsg = prev[tempIdx];

          // [ADD] Переносим blob-URL в decryptedImageBlobs для превью (только для изображений)
          if (
            tempMsg.fileUrl?.startsWith('blob:') &&
            (tempMsg.contentType?.startsWith('image/') || 
            (tempMsg.fileName && /\.(jpg|jpeg|png|gif|webp)$/i.test(tempMsg.fileName)))
          ) {
            setDecryptedImageBlobs(prevBlobs => ({
              ...prevBlobs,
              [newMessage.id]: tempMsg.fileUrl,   // Переносим blob на новый ID
            }));
          }
          
          const oldFileUrl = tempMsg.fileUrl;
          const updated = [...prev];
          updated[tempIdx] = {
            ...tempMsg,
            ...newMessage,
            content: newMessage.content || tempMsg.content || '',
            id: newMessage.id,
            clientMessageId: undefined,
            fileUrl: newMessage.fileUrl || newMessage.file_url,
            file_url: newMessage.file_url || newMessage.fileUrl,
            delivered: newMessage.delivered_status ?? false,
            read: newMessage.read_status ?? false,
          };
          if (oldFileUrl?.startsWith('blob:')) {
            URL.revokeObjectURL(oldFileUrl);
            console.log(`Revoked temp blob in updateMessages for ${newMessage.id}`);
          }
          setSentMessagesCache(p => { const c = { ...p }; delete c[newMessage.clientMessageId]; return c; });
          return updated;
        }
      }

      return [...prev, { ...newMessage, delivered: false, read: false }];
    });

    // Trigger preload for audio
    if (newMessage.messageType === 'audio') {
      const fullMsg = { ...newMessage, sender: { id: newMessage.sender?.id || userId } };
      setTimeout(() => preloadAudio(newMessage.id, newMessage.fileUrl || newMessage.file_url, fullMsg), 100);
    }
  };

  /*const fetchFileMetadata = async (messageId) => {
    try {
      const response = await fetch(`https://unlimitedpossibilities12.org/api/messages/file_metadata/${messageId}`);
      if (!response.ok) {
        throw new Error(`Failed to fetch file metadata: ${response.status}`);
      }
      const data = await response.json();
      console.log(`Fetched FileMetadata for messageId=${messageId}:`, data);
      return data; // { fileUrl, nonce, publicKeyId }
    } catch (error) {
      console.error(`Error fetching file metadata for messageId=${messageId}:`, error);
      throw error;
    }
  };*/

  const fetchFileMetadata = async (messageId, retries = 5, delay = 2000) => {
    /* Сервер отдаёт сведения о вложении вместе с историей — если они уже
       есть, лишний круг перед загрузкой файла не нужен. */
    const готовое = сведенияОВложениях.current.get(String(messageId));
    if (готовое) return готовое;
    for (let attempt = 1; attempt <= retries; attempt++) {
      try {
        console.log(`[METADATA] Fetching for messageId=${messageId}, attempt=${attempt}`);
        const response = await fetch(`https://unlimitedpossibilities12.org/api/messages/file_metadata/${messageId}`);
        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(`HTTP ${response.status}: ${errorText}`);
        }
        const data = await response.json();
        console.log(`[METADATA] SUCCESS for ${messageId}:`, data);
        if (!data.fileUrl || !data.nonce) {
          throw new Error(`Invalid metadata: ${JSON.stringify(data)}`);
        }
        return data;
      } catch (error) {
        console.error(`[METADATA] FAIL attempt ${attempt}:`, error.message);
        if (attempt === retries) throw error;
        await new Promise(r => setTimeout(r, delay * attempt));
      }
    }
  };

  const encryptMessage = async (content, recipientId) => {
    if (!keyPair || !publicKeys[recipientId]) {
      throw new Error('Encryption keys not available');
    }
    const nonce = sodium.randombytes_buf(sodium.crypto_box_NONCEBYTES);
    const encrypted = sodium.crypto_box_easy(
      content,
      nonce,
      sodium.from_base64(publicKeys[recipientId]),
      sodium.from_base64(keyPair.privateKey)
    );
    return {
      encryptedContent: sodium.to_base64(encrypted),
      nonce: sodium.to_base64(nonce)
    };
  };

  const decryptMessage = async (encryptedContent, nonce, senderId, timestamp, publicKey, originalPublicKeyId = null) => {
    console.log(
      `decryptMessage called: senderId=${senderId}, timestamp=${timestamp}`
    );
    await sodium.ready;

    if (!publicKey || !keyPair?.privateKey) {
      throw new Error('Encryption keys not available');
    }

    const validateBase64 = (str, name) => {
      try {
        sodium.from_base64(str);
        console.log(`Valid base64: ${name}=${str}`);
        return true;
      } catch (e) {
        console.error(`Invalid base64: ${str}, error: ${e.message}`);
        return false;
      }
    };

    console.log(
      `Validating base64: encryptedContent=${validateBase64(
        encryptedContent,
        'encryptedContent'
      )}, nonce=${validateBase64(nonce, 'nonce')}, publicKey=${validateBase64(
        publicKey,
        'publicKey'
      )}, privateKey=${validateBase64(keyPair.privateKey, 'privateKey')}`
    );

    if (
      !validateBase64(encryptedContent, 'encryptedContent') ||
      !validateBase64(nonce, 'nonce') ||
      !validateBase64(publicKey, 'publicKey') ||
      !validateBase64(keyPair.privateKey, 'privateKey')
    ) {
      console.error(
        `Invalid base64 format: encryptedContent=${encryptedContent}, nonce=${nonce}, publicKey=${publicKey}, privateKey=${keyPair.privateKey}`
      );
      throw new Error('Invalid base64 format');
    }

    // [FIX] Вынести vars из try, чтобы они были доступны в catch
    const encryptedBytes = sodium.from_base64(encryptedContent);
    const nonceBytes = sodium.from_base64(nonce);
    const publicKeyBytes = sodium.from_base64(publicKey);
    let privateKeyBytes = sodium.from_base64(keyPair.privateKey);

    console.log(
      `Decryption input: encryptedBytesLength=${encryptedBytes.length}, nonceBytesLength=${nonceBytes.length}, publicKeyBytesLength=${publicKeyBytes.length}, privateKeyBytesLength=${privateKeyBytes.length}`
    );
    console.log(
      `Decryption input hex: encryptedBytes=${sodium.to_hex(
        encryptedBytes
      )}, nonceBytes=${sodium.to_hex(nonceBytes)}, publicKeyBytes=${sodium.to_hex(
        publicKeyBytes
      )}`
    );

    try {
      const decryptedBytes = sodium.crypto_box_open_easy(
        encryptedBytes,
        nonceBytes,
        publicKeyBytes,
        privateKeyBytes
      );
      const decryptedMessage = sodium.to_string(decryptedBytes);
      console.log(`Decryption successful: decryptedMessage=${decryptedMessage}`);
      return decryptedMessage;
    } catch (error) {
      console.error(`Decryption error for senderId: ${senderId}, encryptedContent: ${encryptedContent}, nonce: ${nonce}, error: ${error.message}`);

      // ← Добавляем ТОЛЬКО обработку конкретной мобильной проблемы
      const errorMsg = error.message.toLowerCase();
      if (
        errorMsg.includes('invalid') ||
        errorMsg.includes('length') ||
        errorMsg.includes('incorrect key pair') ||
        errorMsg.includes('key')
      ) {
        console.warn('Possible mobile browser key corruption detected — attempting one-time cleanup retry');

        // Чистим строку приватного ключа ТОЛЬКО для этой попытки
        const cleanedPrivateKey = keyPair.privateKey
          .trim()
          .replace(/\s+/g, '')           // убираем все пробелы, табы
          .replace(/[\r\n]+/g, '');      // убираем переносы строк

        try {
          privateKeyBytes = sodium.from_base64(cleanedPrivateKey);
          console.log(`Cleaned private key bytes length: ${privateKeyBytes.length}`);

          // Пробуем ещё раз с очищенным ключом
          const decryptedBytes = sodium.crypto_box_open_easy(
            encryptedBytes,
            nonceBytes,
            publicKeyBytes,
            privateKeyBytes
          );
          const decryptedMessage = sodium.to_string(decryptedBytes);
          console.log(`Retry with cleaned private key SUCCESS: ${decryptedMessage}`);

          // Если успех — можно тихо обновить state (не обязательно, но полезно)
          // setKeyPair(prev => ({ ...prev, privateKey: cleanedPrivateKey }));

          return decryptedMessage;
        } catch (cleanError) {
          console.error('Retry with cleaned key failed:', cleanError);
        }
      }

      // ← Твой существующий fallback на current key (остаётся без изменений)
      if (originalPublicKeyId && error.message.includes('incorrect key pair')) {
        console.log(`Retry with current key for senderId=${senderId}`);
        const currentKey = await getPublicKey(senderId);  // No ID = current
        const currentBytes = sodium.from_base64(currentKey);
        try {
          const decryptedBytes = sodium.crypto_box_open_easy(
            encryptedBytes,
            nonceBytes,
            currentBytes,
            privateKeyBytes
          );
          const decryptedMessage = sodium.to_string(decryptedBytes);
          console.log(`Retry success: ${decryptedMessage}`);
          return decryptedMessage;
        } catch (fallbackErr) {
          console.error('Fallback retry failed:', fallbackErr);
        }
      }

      throw new Error(`Decryption failed: ${error.message}`);
    }
  };

  const encryptFile = async (file, recipientId, userId, keyPair, publicKeys, caption = '') => {
    await sodium.ready;

    if (!keyPair || !publicKeys[recipientId]) {
      throw new Error('Encryption keys not available for file');
    }

    const privateKey = sodium.from_base64(keyPair.privateKey);
    const publicKey = sodium.from_base64(publicKeys[recipientId]);

    // Читаем файл как массив байтов
    // Два РАЗНЫХ nonce: один для содержимого файла, другой для метаданных.
    // Один nonce на два открытых текста под одной парой ключей раскрывал бы
    // XOR открытых текстов (а структура метаданных известна) и ломал
    // аутентификацию Poly1305.
    const fileBytes = new Uint8Array(await file.arrayBuffer());
    const fileNonce = sodium.randombytes_buf(sodium.crypto_box_NONCEBYTES);
    const encryptedFile = sodium.crypto_box_easy(fileBytes, fileNonce, publicKey, privateKey);
    const nonce = sodium.randombytes_buf(sodium.crypto_box_NONCEBYTES); // для метаданных

    // Шифруем метаданные (имя файла и MIME-тип)
    // Подпись к файлу едет внутри зашифрованных метаданных: отдельного поля
    // для неё в сообщении нет, а открытым текстом в секретном чате она идти не должна.
    const metadata = {
      fileName: file.name,
      contentType: file.type,
      ...(caption ? { caption } : {}),
    };
    const metadataBytes = sodium.from_string(JSON.stringify(metadata));
    const encryptedContent = sodium.crypto_box_easy(metadataBytes, nonce, publicKey, privateKey);

    return {
      encryptedFile: sodium.to_base64(encryptedFile),
      nonce: sodium.to_base64(nonce),           // nonce метаданных — в сообщение
      fileNonce: sodium.to_base64(fileNonce),   // nonce файла — в file_metadata на сервере
      encryptedContent: sodium.to_base64(encryptedContent),
    };
  };

  const decryptFileContent = async (encryptedFileBytes, nonce, senderId, publicKeyId, publicKeys, keyPair, recipientId) => {
    await sodium.ready;

    let publicKey;

    if (senderId === userId) {
        // === СВОЙ ФАЙЛ — ключ получателя ===
        // Эту логику не трогаем, она работает
        // Ключ собеседника, и именно текущий: publicKeyId у сообщения —
        // наш собственный, по нему пара не сойдётся
        publicKey = publicKeys[recipientId];
        if (!publicKey) {
            console.log(`[OWN FILE] Fetching current public key for recipientId=${recipientId}`);
            publicKey = await getPublicKey(recipientId);
            setPublicKeys((prev) => ({ ...prev, [recipientId]: publicKey }));
        }
    } else {
        // === ВХОДЯЩИЙ ФАЙЛ — ключ отправителя ===

        // Сначала пытаемся взять ключ по publicKeyId из сообщения (исторический)
        if (publicKeyId) {
            console.log(`[INCOMING FILE] Trying history public key: senderId=${senderId}, publicKeyId=${publicKeyId}`);
            try {
                publicKey = await getPublicKey(senderId, publicKeyId);
            } catch (err) {
                console.warn(`Failed to fetch history key for publicKeyId=${publicKeyId}, falling back to current`);
            }
        }

        // Если не получилось или publicKeyId не было — берём текущий ключ
        if (!publicKey) {
            console.log(`[INCOMING FILE] Fetching current public key for senderId=${senderId}`);
            publicKey = await getPublicKey(senderId); // без publicKeyId → текущий
        }

        setPublicKeys((prev) => ({ ...prev, [senderId]: publicKey }));
    }

    if (!publicKey || !keyPair?.privateKey) {
        throw new Error('Decryption keys not available for file');
    }

    const publicKeyBytes = sodium.from_base64(publicKey);
    const privateKeyBytes = sodium.from_base64(keyPair.privateKey);
    const nonceBytes = sodium.from_base64(nonce);

    try {
        const decryptedBytes = sodium.crypto_box_open_easy(
            encryptedFileBytes,
            nonceBytes,
            publicKeyBytes,
            privateKeyBytes
        );

        console.log(`File decryption successful for senderId=${senderId} (used keyId: ${publicKeyId || 'current'})`);
        return decryptedBytes;
    } catch (error) {
        console.error(`File decryption failed with used public key:`, {
            error: error.message,
            usedPublicKeyId: publicKeyId || 'current',
            publicKeyLength: publicKey.length,
        });

        // Если ошибка именно "incorrect key pair" и мы использовали history-ключ — пробуем current ещё раз явно
        if (
            error.message.includes('incorrect key pair') &&
            publicKeyId && 
            senderId !== userId
        ) {
            console.log(`[INCOMING FILE] Retry decryption with current key for senderId=${senderId}`);
            const currentPublicKey = await getPublicKey(senderId); // без ID → current
            const currentPublicKeyBytes = sodium.from_base64(currentPublicKey);

            try {
                const decryptedBytes = sodium.crypto_box_open_easy(
                    encryptedFileBytes,
                    nonceBytes,
                    currentPublicKeyBytes,
                    privateKeyBytes
                );
                console.log(`File decryption succeeded on retry with current key for senderId=${senderId}`);
                // Сохраняем текущий ключ в state, чтобы в будущем не мучиться
                setPublicKeys((prev) => ({ ...prev, [senderId]: currentPublicKey }));
                return decryptedBytes;
            } catch (retryError) {
                console.error(`Retry also failed:`, retryError);
                throw new Error(`File decryption failed even with current key: ${retryError.message}`);
            }
        }

        throw new Error(`File decryption failed: ${error.message}`);
    }
  };

  const uploadFile = async (file, nonce, messageId) => {
    console.log('Uploading file with messageId:', messageId, 'Type:', typeof messageId);
    const formData = new FormData();
    formData.append('file', new Blob([sodium.from_base64(file)], { type: 'application/octet-stream' }));
    formData.append('nonce', nonce);
    formData.append('messageId', messageId);

    const response = await fetch('https://unlimitedpossibilities12.org/api/messages/upload_encrypted_file', {
      method: 'POST',
      body: formData,
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`File upload failed: ${response.status} - ${errorText}`);
    }
    const data = await response.json();
    console.log('File upload response:', data);
    return data.fileUrl;
  };

  useEffect(() => {
    const partnerKey = partnerId ? publicKeys[partnerId] : null;
    if (!keyPair?.publicKey || !partnerKey) { setSafetyNumber(null); return; }
    computeSafetyNumber(userId, keyPair.publicKey, partnerId, partnerKey)
      .then(setSafetyNumber)
      .catch(() => setSafetyNumber(null));
  }, [keyPair, partnerId, publicKeys, userId]);

  const sendMessage = async () => {
    своёОтправлено.current = true;   // своё сообщение уводит в конец
    if ((content || selectedFile || audioBlob) && userId && keyPair) {
      try {
        // [ADD] Получаем ID получателя
        const recipientId = await resolveRecipientId();
        const recipientName = partnerNameRef.current || partnerNameFromChat(chatName, username);

        // [ADD] Генерируем ID временного сообщения
        const clientMessageId = `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
        const timestamp = new Date().toISOString();

        // [ADD] Базовые данные сообщения
        let messageData = {
          sender: { id: userId, username },
          chat: { id: chatId, name: generateChatName(username, recipientName, true), isSecret: true },
          delivered: false,
          read: false,
          timestamp,
        };

        let savedMessage = null;
        let serverFileUrl = null;

        // === ТЕКСТОВОЕ СООБЩЕНИЕ (только если нет файла: с файлом текст уходит подписью) ===
        if (content && !selectedFile) {
          // Перевод на устройстве ДО шифрования: в шифртекст уходит {"text","translated","lang"}
          const outgoingText = await prepareOutgoingText(chatId, content);
          setSentMessagesCache((prev) => ({ ...prev, [clientMessageId]: outgoingText }));
          saveOwnMessageContent(chatId, userId, clientMessageId, outgoingText);

          const { encryptedContent, nonce } = await encryptMessage(outgoingText, recipientId);
          messageData = {
            ...messageData,
            encryptedContent,
            nonce,
            messageType: 'text',
            content,
            id: clientMessageId,
            clientMessageId,
          };

          const response = await fetch('https://unlimitedpossibilities12.org/api/messages', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              ...messageData,
              id: undefined,
              content: undefined,
            }),
          });
          if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Failed to send message: ${response.status} - ${errorText}`);
          }
          savedMessage = await response.json();
          messageData = { ...messageData, id: savedMessage.id };

          setMessages((prev) => {
            if (prev.some(m => m.id === clientMessageId)) return prev;
            return [...prev, { ...messageData, delivered: false, read: false }];
          });

        // === ФАЙЛОВОЕ СООБЩЕНИЕ ===
        } else if (selectedFile) {
          const captionRaw = (content || '').trim();
          const caption = captionRaw ? await prepareOutgoingText(chatId, captionRaw) : '';
          const { encryptedFile, nonce, fileNonce, encryptedContent } = await encryptFile(
            selectedFile,
            recipientId,
            userId,
            keyPair,
            publicKeys,
            caption
          );
          if (caption) {
            // Свою подпись держим локально, как и текст обычных сообщений
            setSentMessagesCache((prev) => ({ ...prev, [clientMessageId]: caption }));
            saveOwnMessageContent(chatId, userId, clientMessageId, caption);
          }

          const tempFileUrl = URL.createObjectURL(selectedFile);

          messageData = {
            ...messageData,
            messageType: 'file',
            contentType: selectedFile.type,
            id: clientMessageId,
            clientMessageId,
            encryptedContent,
            nonce,
            fileName: selectedFile.name,
            fileUrl: tempFileUrl,
            content: caption,
          };

          const fileContentKey = `fileContent_${chatId}_${userId}_${clientMessageId}`;
          localStorage.setItem(fileContentKey, JSON.stringify({
            fileName: selectedFile.name,
            contentType: selectedFile.type,
            fileUrl: tempFileUrl,
            caption,
          }));

          setMessages((prev) => {
            if (prev.some(m => m.id === clientMessageId)) return prev;
            return [...prev, {
              ...messageData,
              delivered: false,
              read: false,
              timestamp,
              sender: messageData.sender,
              chat: messageData.chat,
            }];
          });

          const response = await fetch('https://unlimitedpossibilities12.org/api/messages', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              ...messageData,
              id: undefined,
              content: undefined,
              fileName: undefined,
              fileUrl: undefined,
              file_url: undefined,
            }),
          });
          if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Failed to send file message: ${response.status} - ${errorText}`);
          }
          savedMessage = await response.json();

          // [ADD] Сохраняем временный blob-URL в decryptedImageBlobs для превью (только для изображений)
          if (selectedFile.type.startsWith('image/')) {
            setDecryptedImageBlobs(prev => ({
              ...prev,
              [clientMessageId]: tempFileUrl,  // На временный ID (на всякий случай)
              [savedMessage.id]: tempFileUrl,   // На постоянный серверный ID (основное)
            }));
          }

          serverFileUrl = await uploadFile(encryptedFile, fileNonce || nonce, savedMessage.id);
          if (!serverFileUrl) throw new Error('File upload failed');

          // ────────────────────────────────────────────────
          // ЭТОТ БЛОК ДОБАВИТЬ (3 строки) — и всё
          setDecryptedImageBlobs(prev => ({
            ...prev,
            [savedMessage.id]: tempFileUrl   // сохраняем временный blob как превью
          }));
          // ────────────────────────────────────────────────

          // ← ДОБАВЬ: обнови messageData
          messageData.fileUrl = serverFileUrl;
          messageData.file_url = serverFileUrl;
          messageData.fileName = selectedFile.name;
          messageData.contentType = selectedFile.type;

          localStorage.setItem(fileContentKey, JSON.stringify({
            fileName: selectedFile.name,
            contentType: selectedFile.type,
            fileUrl: serverFileUrl,
            caption,
          }));
          if (caption) saveOwnMessageContent(chatId, userId, savedMessage.id, caption);

          saveFileToCache(chatId, userId, savedMessage.id, {
            fileName: selectedFile.name,
            contentType: selectedFile.type,
            fileUrl: serverFileUrl,
            caption,
          });
          saveFileToCache(chatId, userId, clientMessageId, {
            fileName: selectedFile.name,
            contentType: selectedFile.type,
            fileUrl: tempFileUrl,
          });

          const arrayBuffer = await selectedFile.arrayBuffer();
          const base64File = sodium.to_base64(new Uint8Array(arrayBuffer));
          saveFileToCache(chatId, userId, clientMessageId, {
            fileName: selectedFile.name,
            contentType: selectedFile.type,
            fileData: base64File,
          });

          // ← ФИКС: ЗАМЕНА TEMP В UI (сразу после upload)
          setMessages((prev) => prev.map((msg) =>
            msg.id === clientMessageId
              ? {
                  ...msg,
                  id: savedMessage.id,
                  fileUrl: serverFileUrl,
                  file_url: serverFileUrl,
                  clientMessageId: undefined,
                  delivered: savedMessage.delivered_status ?? false,
                  read: savedMessage.read_status ?? false,
                  timestamp: savedMessage.timestamp || timestamp,
                }
              : msg
          ));

          // ← ФИКС: WS PUBLISH ДЛЯ FILE (с реальными статусами)
          if (client && client.connected) {
            const wsPayload = {
              ...messageData,
              id: savedMessage.id,
              content: undefined,
              clientMessageId: undefined,
              fileUrl: serverFileUrl,
              file_url: serverFileUrl,
              fileName: selectedFile.name,
              contentType: selectedFile.type,
              encryptedContent,
              nonce,
              delivered: savedMessage.delivered_status ?? false,  // Из сервера
              read: savedMessage.read_status ?? false,
              timestamp: savedMessage.timestamp || timestamp,
            };

            console.log('SENDING FILE VIA WS:', wsPayload);
            client.publish({
              destination: `/app/sendMessage/chat/${chatId}`,
              body: JSON.stringify(wsPayload),
            });
            notifyPartner();
          }

          // ← ОЧИСТКА TEMP BLOB
          //URL.revokeObjectURL(tempFileUrl);

        // === АУДИО СООБЩЕНИЕ ===
        } else if (audioBlob) {
          const audioFile = new File([audioBlob], `audio_${Date.now()}.mp3`, { type: 'audio/mpeg' });

          // === ШИФРУЕМ АУДИО КАК ОБЫЧНЫЙ ФАЙЛ ===
          const { encryptedFile, nonce, fileNonce, encryptedContent } = await encryptFile(
            audioFile,
            recipientId,
            userId,
            keyPair,
            publicKeys
          );

          // === СОЗДАЁМ СООБЩЕНИЕ ===
          messageData = {
            ...messageData,
            messageType: 'audio',
            contentType: 'audio/mpeg',
            id: clientMessageId,
            clientMessageId,
            encryptedContent,
            nonce,
            fileName: audioFile.name,
          };

          // === СОХРАНЯЕМ В БД ===
          const response = await fetch('https://unlimitedpossibilities12.org/api/messages', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              ...messageData,
              id: undefined,
              content: undefined,
              fileName: undefined,
              fileUrl: undefined,
              file_url: undefined,
            }),
          });

          if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Failed to send audio message: ${response.status} - ${errorText}`);
          }

          const savedMessage = await response.json();

          // === ЗАГРУЖАЕМ ЗАШИФРОВАННЫЙ ФАЙЛ ===
          serverFileUrl = await uploadFile(encryptedFile, fileNonce || nonce, savedMessage.id);
          if (!serverFileUrl) throw new Error('Audio upload failed');

          // === ОБНОВЛЯЕМ messageData ===
          messageData.fileUrl = serverFileUrl;
          messageData.file_url = serverFileUrl;
          messageData.fileName = audioFile.name;

          // === КЭШИРУЕМ (для своих сообщений) ===
          const fileContentKey = `fileContent_${chatId}_${userId}_${clientMessageId}`;
          localStorage.setItem(fileContentKey, JSON.stringify({
            fileName: audioFile.name,
            contentType: 'audio/mpeg',
            fileUrl: serverFileUrl,
          }));

          // === ДОБАВЛЯЕМ В UI ===
          setMessages((prev) => {
            if (prev.some(m => m.id === clientMessageId)) return prev;
            return [...prev, { ...messageData, delivered: false, read: false }];
          });
        }

        // === ОТПРАВКА ЧЕРЕЗ WebSocket (ТОЛЬКО ПОСЛЕ СОХРАНЕНИЯ) ===
        if (client && client.connected && savedMessage) {
          const websocketMessage = {
            ...messageData,
            id: savedMessage.id,
            content: undefined,
            clientMessageId: messageData.clientMessageId || undefined,
            fileUrl: messageData.fileUrl,
            file_url: messageData.file_url,
            fileName: messageData.fileName,
            contentType: messageData.contentType,
          };

          console.log('Sending via WebSocket:', websocketMessage);
          client.publish({
            destination: `/app/sendMessage/chat/${chatId}`,
            body: JSON.stringify(websocketMessage),
          });
          notifyPartner();
        }

        sendTypingEvent(false);

        // === Очистка формы ===
        resetForm();

      } catch (error) {
        console.error('Error sending message:', error);
        toast.error(`Failed to send message: ${error.message}`);
      }
    } else {
      toast.warn('Enter a message or select a file');
    }
  };

  const sendAudioMessage = async (audioBlob) => {
    if (!audioBlob || !userId || !keyPair) {
      console.warn('Audio, userId, or keyPair is missing');
      toast.error('Failed to send audio message');
      setMicIconState('mic');
      return;
    }

    const recipientName = partnerNameRef.current || partnerNameFromChat(chatName, username);

    // ← ОБЪЯВЛЯЕМ clientMessageId ДО try
    let clientMessageId = `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    let tempFileUrl = null;

    try {
      const recipientId = await resolveRecipientId();

      const audioFile = new File([audioBlob], `audio_${Date.now()}.mp3`, { type: 'audio/mpeg' });
      const { encryptedFile, nonce, fileNonce, encryptedContent } = await encryptFile(
        audioFile,
        recipientId,
        userId,
        keyPair,
        publicKeys
      );

      tempFileUrl = URL.createObjectURL(audioBlob);

      // ← КЭШИРУЕМ blob + metadata
      const fileContentKey = `fileContent_${chatId}_${userId}_${clientMessageId}`;
      localStorage.setItem(fileContentKey, JSON.stringify({
        fileName: audioFile.name,
        contentType: 'audio/mpeg',
        fileUrl: tempFileUrl,
        nonce,
        encryptedContent,
      }));

      // ← ДОБАВЛЯЕМ В UI С blob URL
      setMessages((prev) => {
        if (prev.some(m => m.id === clientMessageId)) return prev;
        return [...prev, {
          id: clientMessageId,
          clientMessageId,
          sender: { id: userId, username },
          chat: { id: chatId, name: chatName, isSecret: true },
          messageType: 'audio',
          fileUrl: tempFileUrl,
          fileName: audioFile.name,
          contentType: 'audio/mpeg',
          delivered: false,
          read: false,
          timestamp: new Date().toISOString(),
          nonce,
          encryptedContent,
        }];
      });

      // ← 1. СОХРАНЯЕМ СООБЩЕНИЕ
      const postPayload = {
        chat: { id: chatId, name: chatName, isSecret: true },
        sender: { id: userId, username },
        messageType: 'audio',
        encryptedContent,
        nonce,
        clientMessageId,
        contentType: 'audio/mpeg',
        fileUrl: null,
        delivered: false,
        read: false,
        timestamp: new Date().toISOString(),
      };

      console.log('POST /api/messages payload:', postPayload);

      const response = await fetch('https://unlimitedpossibilities12.org/api/messages', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(postPayload),
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Failed to save audio message: ${response.status} - ${errorText}`);
      }

      const savedMessage = await response.json();
      console.log('Saved message from server:', savedMessage);

      // ← 2. ЗАГРУЖАЕМ ФАЙЛ
      const serverFileUrl = await uploadFile(encryptedFile, fileNonce || nonce, savedMessage.id);
      console.log('Encrypted file uploaded, serverFileUrl:', serverFileUrl);

      // ← 3. ЗАМЕНЯЕМ В UI
      setMessages((prev) => prev.map((msg) =>
        msg.id === clientMessageId
          ? {
              ...msg,
              id: savedMessage.id,
              fileUrl: serverFileUrl,
              file_url: serverFileUrl,
              clientMessageId: undefined,
              delivered: savedMessage.delivered_status ?? false,
              read: savedMessage.read_status ?? false,
              timestamp: savedMessage.timestamp,
            }
          : msg
      ));

      // ← ОБНОВЛЯЕМ КЭШ
      localStorage.setItem(fileContentKey, JSON.stringify({
        fileName: audioFile.name,
        contentType: 'audio/mpeg',
        fileUrl: serverFileUrl,
        nonce,
        encryptedContent,
      }));

      // ← 4. ОТПРАВЛЯЕМ ЧЕРЕЗ WS
      if (client && client.connected) {
        const wsPayload = {
          id: savedMessage.id,
          messageType: 'audio',
          fileUrl: serverFileUrl,
          file_url: serverFileUrl,
          fileName: audioFile.name,
          contentType: 'audio/mpeg',
          encryptedContent,
          nonce,
          sender: { id: userId, username },
          chat: { id: chatId, name: chatName, isSecret: true },
          delivered: savedMessage.delivered_status ?? false,
          read: savedMessage.read_status ?? false,
          timestamp: savedMessage.timestamp,
        };

        console.log('SENDING AUDIO VIA WS (FULL):', wsPayload);

        client.publish({
          destination: `/app/sendMessage/chat/${chatId}`,
          body: JSON.stringify(wsPayload),
        });
        notifyPartner();
      }

      // ← ОЧИЩАЕМ
      if (tempFileUrl?.startsWith('blob:')) {
        URL.revokeObjectURL(tempFileUrl);
      }
      localStorage.removeItem(fileContentKey);

      setAudioBlob(null);
      setMicIconState('mic');
      toast.success('Audio message sent!');

    } catch (error) {
      console.error('Error in sendAudioMessage:', error);
      toast.error(`Failed to send audio: ${error.message}`);
      setMicIconState('mic');

      // ← УДАЛЯЕМ temp сообщение — clientMessageId ДОСТУПЕН!
      setMessages((prev) => prev.filter(m => m.id !== clientMessageId));
      localStorage.removeItem(`fileContent_${chatId}_${userId}_${clientMessageId}`);

      // ← ОЧИЩАЕМ blob, если создан
      if (tempFileUrl?.startsWith('blob:')) {
        URL.revokeObjectURL(tempFileUrl);
      }
    }
  };

  // Уведомление собеседнику, если он сейчас не в чате (на Android его показывает
  // CallService по /topic/notifications/{userId}). Раньше здесь была функция
  // sendNotification, которую никто не вызывал, поэтому уведомления из
  // секретного чата с браузера не уходили вообще. Текст не передаём: сервер
  // для секретных чатов сам подставляет «New secret message».
  const notifyPartner = async () => {
    try {
      const recipientId = await resolveRecipientId();

      await fetch(`https://unlimitedpossibilities12.org/api/messages/notify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId,
          recipientId,
          content: 'New secret message',
          chatId,
        }),
      });
      console.log(`Notification sent to user ${recipientId} for secret chat ${chatId}`);
    } catch (error) {
      console.error('Error sending secret chat notification:', error);
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey && (content.trim() || selectedFile)) {
      e.preventDefault();
      sendMessage();
    }
  };

  const resetForm = () => {
    setContent('');
    setSelectedFile(null);
    setEditingMessageId(null);
    setRows(1);
    setAudioBlob(null);
    setMicIconState('mic');
    if (fileInputRef.current) {
      fileInputRef.current.value = null;
    }
    if (textAreaRef.current) {
      textAreaRef.current.style.height = '44px';
      textAreaRef.current.classList.remove('scroll-enabled');
    }
  };

  const handleRightClick = (e, message) => {
    e.preventDefault();

    const messageEl = e.currentTarget;
    const rect = messageEl.getBoundingClientRect();

    const { x, y } = местоМеню(e);

    setContextMenu({ visible: true, x, y, message });
  };

  /** Своё ли это сообщение: чужое можно убрать, но не править. */
  /** У карточки звонка нет ни текста, ни отправителя — править нечего. */
  const моёСообщение = (м) => !!(м && м.sender && !м.звонок) && м.sender.id === userId;

  const handleMenuAction = (action) => {
    const msg = contextMenu.message;
    if (!msg) return;
    if (action === 'edit' && !моёСообщение(msg)) return; // править можно только своё

    if (action === 'edit' && msg.messageType === 'text') {
      setContent(msg.content);
      setEditingMessageId(msg.id);
      textAreaRef.current?.focus();
    } else if (action === 'select') {
      setВыбранные(new Set([ключСтроки(msg)]));
    } else if (action === 'delete') {
      // Спрашиваем, как в телеграме: у себя или и у собеседника тоже
      setУбратьУОбоих(false);
      setУбрать(msg.звонок ? { вид: 'звонок', id: msg.звонок.id } : { вид: 'сообщение', id: msg.id });
    }

    setContextMenu({ visible: false });
  };

  const deleteMessage = async (id) => {
    if (client && client.connected) {
      client.publish({
        destination: `/app/deleteMessage/${chatId}`,
        body: JSON.stringify(id),
      });
      setMessages((prevMessages) => prevMessages.filter((msg) => msg.id !== id));
      toast.success('Message deleted!');
    } else {
      toast.error('Failed to delete message. Try again.');
    }
  };


  // Меню в шапке закрывается щелчком мимо него
  useEffect(() => {
    if (!chatMenuOpen) return undefined;
    const closeOnOutside = (event) => {
      if (chatMenuRef.current && !chatMenuRef.current.contains(event.target)) setChatMenuOpen(false);
    };
    document.addEventListener('mousedown', closeOnOutside);
    return () => document.removeEventListener('mousedown', closeOnOutside);
  }, [chatMenuOpen]);

  const formatTimestamp = (timestamp) => {
    if (!timestamp) return '';
    try {
      const date = parseISO(timestamp);
      const kievDate = new Date(date.toLocaleString('en-US', { timeZone: 'Europe/Kiev' }));
      // В пузыре только время: день обозначает разделитель в ленте
      return format(kievDate, 'HH:mm');
    } catch (error) {
      console.error('Error formatting timestamp:', error);
      return '';
    }
  };

  /**
   * Предыдущая строка ленты, которая и впрямь сообщение.
   *
   * Карточки звонков пропускаем: у них нет отправителя, а решаем мы по
   * нему — ставить ли аватарку и начинать ли череду своих сообщений.
   */
  const предыдущееСообщение = (index) => {
    for (let i = index - 1; i >= 0; i -= 1) {
      const строка = лента[i];
      if (строка && строка.sender) return строка;
    }
    return null;
  };

  /**
   * Аватарки рядом с сообщением в переписке вдвоём нет.
   *
   * Собеседник один и он же в шапке — кружок у каждой его реплики
   * ничего не сообщает, а ширину отнимает. В группах он остаётся.
   */
  const shouldShowAvatar = () => false;

  const isFirstMessage = (msg, index) => {
    if (!msg.sender || msg.sender.id !== userId) return false;
    const предыдущее = предыдущееСообщение(index);
    return !предыдущее || предыдущее.sender.id !== msg.sender.id;
  };

  const startRecording = async () => {
    try {
      await loadFFmpeg();
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      let mimeType = 'audio/webm;codecs=opus';
      if (!MediaRecorder.isTypeSupported(mimeType)) {
        mimeType = 'audio/webm';
        if (!MediaRecorder.isTypeSupported(mimeType)) {
          toast.error('Audio recording not supported in this browser');
          return;
        }
      }
      const mediaRecorder = new MediaRecorder(stream, {
        mimeType,
        audioBitsPerSecond: 128000
      });
      mediaRecorderRef.current = mediaRecorder;
      const chunks = [];

      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) {
          chunks.push(e.data);
        }
      };

      mediaRecorder.onstop = async () => {
        const webmBlob = new Blob(chunks, { type: mimeType });
        console.log(`Recorded audio: size=${webmBlob.size}, type=${webmBlob.type}`);  // ← Добавь лог для отладки
        try {
          const inputName = 'input.webm';
          const outputName = 'output.mp3';
          const arrayBuffer = await webmBlob.arrayBuffer();
          await ffmpeg.current.writeFile(inputName, new Uint8Array(arrayBuffer));
          await ffmpeg.current.exec(['-i', inputName, '-c:a', 'libmp3lame', '-b:a', '128k', outputName]);
          const mp3Data = await ffmpeg.current.readFile(outputName);
          const mp3Blob = new Blob([mp3Data.buffer], { type: 'audio/mpeg' });
          console.log(`Converted audio: size=${mp3Blob.size}, type=${mp3Blob.type}`);  // ← Добавь лог
          stream.getTracks().forEach(track => track.stop());
          setMicIconState('sending');
          
          // ← ИЗМЕНИ: Не sendMessage(), а sendAudioMessage(mp3Blob)
          await sendAudioMessage(mp3Blob);
        } catch (error) {
          console.error('Error converting to mp3:', error);  // ← Этот лог должен появиться, если ошибка
          toast.error('Failed to convert audio');
          stream.getTracks().forEach(track => track.stop());
          setMicIconState('mic');
        }
      };

      setMicIconState('recording');
      mediaRecorder.start(1000);
      setIsRecording(true);
      toast.info('Recording started');
    } catch (error) {
      console.error('Error starting recording:', error);
      toast.error('Failed to start recording');
      setMicIconState('mic');
    }
  };

  const stopRecording = () => {
    if (mediaRecorderRef.current && isRecording) {
      setMicIconState('sending');
      mediaRecorderRef.current.stop();
      setIsRecording(false);
      toast.info('Recording stopped');
    }
  };

  const loadFFmpeg = async () => {
    if (!ffmpegLoaded) {
      try {
        await ffmpeg.current.load();
        setFfmpegLoaded(true);
        console.log('FFmpeg loaded successfully');
      } catch (error) {
        console.error('Failed to load FFmpeg:', error);
        toast.error('Failed to initialize audio conversion');
        throw error;
      }
    }
  };

  const calculateCharPerLine = () => {
    if (textAreaRef.current) {
      const canvas = document.createElement('canvas');
      const context = canvas.getContext('2d');
      context.font = window.innerWidth <= 800 ? '14px Roboto' : '16px Roboto';
      const avgCharWidth = context.measureText('m').width;
      const textareaWidth = textAreaRef.current.clientWidth - (window.innerWidth <= 800 ? 36 : 40);
      const chars = Math.floor(textareaWidth / avgCharWidth);
      setCharPerLine(chars);
    }
  };

  const calculateRows = (text) => {
    if (!text) return 1;
    const lines = text.split('\n');
    let totalRows = lines.length;
    const canvas = document.createElement('canvas');
    const context = canvas.getContext('2d');
    context.font = window.innerWidth <= 800 ? '14px Roboto' : '16px Roboto';
    lines.forEach((line) => {
      if (line.length > 0) {
        const textWidth = context.measureText(line).width;
        const textareaWidth = textAreaRef.current.clientWidth - (window.innerWidth <= 800 ? 36 : 40);
        if (textWidth > textareaWidth) {
          totalRows += Math.floor(textWidth / textareaWidth);
        }
      }
    });
    return Math.max(1, totalRows);
  };

  const handleInputChange = (e) => {
    const newValue = e.target.value;
    setContent(newValue);
    const newRows = Math.min(5, calculateRows(newValue));
    setRows(newRows);
    const lineHeight = window.innerWidth <= 800 ? 20 : 24;
    textAreaRef.current.style.height = `${newRows * lineHeight + 20}px`;
    if (newRows >= 5) {
      textAreaRef.current.classList.add('scroll-enabled');
    } else {
      textAreaRef.current.classList.remove('scroll-enabled');
    }

    // ← Для логики печатания
    if (newValue.trim() !== '') {
      sendTypingEvent(true);
    } else {
      sendTypingEvent(false); // если стёрли весь текст — тоже убираем индикатор
    }
  };

  const formatAudioTime = (seconds) => {
    if (isNaN(seconds) || seconds === 0 || seconds === Infinity) return '0:00';
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.floor(seconds % 60);
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  };

  const handleTimeUpdate = (msgId, audioElement) => {
    setAudioProgress((prev) => {
      const prevData = prev[msgId] || {};
      if (
        prevData.currentTime === audioElement.currentTime &&
        prevData.isPlaying === !audioElement.paused &&
        prevData.duration === audioElement.duration
      ) {
        return prev;
      }
      return {
        ...prev,
        [msgId]: {
          ...prevData,
          currentTime: audioElement.currentTime,
          duration: audioElement.duration || prevData.duration || 0,
          isPlaying: !audioElement.paused,
          isReady: prevData.isReady || false,
          error: prevData.error || null,
        },
      };
    });
  };

  const handleLoadedMetadata = (msgId, audioElement) => {
    const duration = isFinite(audioElement.duration) && audioElement.duration > 0 ? audioElement.duration : 0;
    setAudioProgress((prev) => {
      const prevData = prev[msgId] || {};
      if (prevData.isReady && prevData.duration === duration) {
        return prev;
      }
      return {
        ...prev,
        [msgId]: {
          ...prevData,
          currentTime: prevData.currentTime || 0,
          duration,
          isPlaying: prevData.isPlaying || false,
          isReady: audioElement.readyState >= 2,
          error: duration === 0 ? 'Duration unavailable: server issue' : null,
          isPreloading: false,
        },
      };
    });
  };

  const handleCanPlay = (msgId) => {
    const audio = audioRefs.current[msgId];
    if (audio && !audioProgress[msgId]?.isReady) {
      setAudioProgress((prev) => ({
        ...prev,
        [msgId]: {
          ...prev[msgId] || {},
          isReady: true,
          error: null,
        },
      }));
    }
  };

  const handleSeek = (msgId, e) => {
    const audio = audioRefs.current[msgId];
    if (!audio || !audioProgress[msgId]?.isReady) {
      console.log(`Seek blocked for msg ${msgId}: audio not ready`);
      return;
    }
    const progressBar = e.currentTarget;
    const rect = progressBar.getBoundingClientRect();
    const clickX = e.clientX - rect.left;
    const width = rect.width;
    const seekTime = (clickX / width) * audio.duration;
    audio.currentTime = seekTime;
    setAudioProgress((prev) => ({
      ...prev,
      [msgId]: {
        ...prev[msgId],
        currentTime: seekTime,
      },
    }));
  };

  const handleAudioError = (msgId, error) => {
    const audio = audioRefs.current[msgId];
    const errorMsg = error.target.error ? `Error code: ${error.target.error.code}, message: ${error.target.error.message}` : 'Unknown error';
    setAudioProgress((prev) => {
      const prevData = prev[msgId] || {};
      if (!prevData.retryCount || prevData.retryCount < 2) {
        setTimeout(() => {
          audio.src = audio.src.split('?')[0] + `?t=${Date.now()}`;
          audio.load();
        }, 2000);
        return {
          ...prev,
          [msgId]: {
            ...prevData,
            isReady: false,
            isPlaying: false,
            error: 'Retrying to load audio...',
            retryCount: (prevData.retryCount || 0) + 1,
          },
        };
      }
      console.warn('Audio unavailable:', msgId);
      return {
        ...prev,
        [msgId]: {
          ...prevData,
          isReady: false,
          isPlaying: false,
          error: 'Voice message unavailable',
        },
      };
    });
  };

  const handleAudioEnded = (msgId) => {
    console.log(`Audio ended for msg ${msgId}`);
    const audio = audioRefs.current[msgId];

    setAudioProgress((prev) => ({
      ...prev,
      [msgId]: {
        ...prev[msgId],
        currentTime: 0,
        isPlaying: false,
        isReady: audio?.readyState >= 2,
        error: null,
      },
    }));

    if (audio) {
      audio.currentTime = 0;
      console.log(`Audio reset to start for msg ${msgId}`);
    }
  };

  const preloadAudio = async (msgId, url, msg) => {  // Добавлен msg param
    const audio = audioRefs.current[msgId];
    // Сам проигрыватель появляется в разметке только тогда, когда запись
    // уже расшифрована, а расшифровать её прежде брался этот же обработчик
    // — и отказывался, не найдя проигрывателя. Замкнутый круг: у свежей
    // записи кнопка оставалась серой до перезагрузки страницы, где разбор
    // переписки расшифровывает всё сам. Поэтому отсутствие проигрывателя
    // работе больше не мешает: расшифруем, он появится и доложит о
    // готовности сам.
    if (audioProgress[msgId]?.isReady || audioProgress[msgId]?.decrypted) {
      console.log(`Preload skipped for ${msgId}: already ready/decrypted`);
      return;
    }
    if (audioProgress[msgId]?.isPreloading) return;

    setAudioProgress(prev => ({ ...prev, [msgId]: { ...prev[msgId], isPreloading: true } }));

    // ← ЗАЩИТА ОТ NULL
    if (!url || typeof url !== 'string') {
      console.warn(`Invalid URL for preload: ${url}`);
      setAudioProgress(prev => ({ ...prev, [msgId]: { ...prev[msgId], isPreloading: false, error: 'No file URL' } }));
      return;
    }

    let playableUrl = url;

    if (!msg.sender) msg.sender = { id: userId };

    // Если зашифровано — расшифровываем
    if (msg.nonce && !url.startsWith('blob:')) {
      try {
        playableUrl = await decryptAudioForPlayback(msgId, url, msg.nonce, msg.publicKeyId || null, msg.sender.id);
      } catch (e) {
        setAudioProgress(prev => ({ ...prev, [msgId]: { ...prev[msgId], isPreloading: false, error: 'Decrypt failed' } }));
        return;
      }
    }

    // Для своих temp blob — используем как есть
    if (url.startsWith('blob:') && msg.sender.id === userId) {
      console.log(`Using temp blob for own audio ${msgId}`);
    }

    // Только что отправленная своя запись уже лежит у нас готовой: кладём
    // её туда же, куда ложатся расшифрованные, иначе проигрыватель для
    // неё не нарисуется вовсе.
    if (playableUrl.startsWith('blob:') && !decryptedAudioBlobs[msgId]?.blobUrl) {
      setDecryptedAudioBlobs(prev => ({ ...prev, [msgId]: { ...prev[msgId], blobUrl: playableUrl } }));
    }

    if (!audio) {
      // Проигрыватель появится следующим шагом отрисовки и сам скажет,
      // что готов, — через onCanPlay.
      setAudioProgress(prev => ({ ...prev, [msgId]: { ...prev[msgId], isPreloading: false } }));
      return;
    }

    if (audio.src === playableUrl && audio.readyState >= 2) {
      setAudioProgress(prev => ({
        ...prev,
        [msgId]: {
          ...prev[msgId],
          isPreloading: false,
          isReady: true,
          decrypted: true,
          error: audio.duration === Infinity ? 'Duration unavailable' : null,
        },
      }));
      return;
    }

    const cacheBusterUrl = playableUrl.startsWith('http') ? `${playableUrl}?t=${Date.now()}` : playableUrl;
    audio.src = cacheBusterUrl;
    audio.type = playableUrl.endsWith('.mp3') ? 'audio/mpeg' : 'audio/webm';
    audio.load();

    audio.addEventListener('loadedmetadata', () => {
      console.log(`Loaded metadata for msg ${msgId}: duration=${audio.duration}, readyState=${audio.readyState}`);
      const duration = isFinite(audio.duration) && audio.duration > 0 ? audio.duration : 0;
      setAudioProgress(prev => ({
        ...prev,
        [msgId]: {
          ...prev[msgId],
          isPreloading: false,
          isReady: audio.readyState >= 2,
          decrypted: true,
          duration,
          error: duration === 0 ? 'Duration unavailable: server issue' : null,
        },
      }));
    }, { once: true });

    audio.addEventListener('canplay', () => {
      console.log(`Can play msg ${msgId}: readyState=${audio.readyState}`);
      setAudioProgress(prev => ({
        ...prev,
        [msgId]: {
          ...prev[msgId],
          isPreloading: false,
          isReady: true,
          decrypted: true,
        },
      }));
    }, { once: true });

    audio.addEventListener('error', (e) => {
      const errorMsg = e.target.error ? `Error code: ${e.target.error.code}, message: ${e.target.error.message}` : 'Unknown error';
      console.error(`Preload error for msg ${msgId}: ${errorMsg}, URL: ${playableUrl}, readyState: ${audio.readyState}`);
      setAudioProgress(prev => {
        const prevData = prev[msgId] || {};
        if (!prevData.retryCount || prevData.retryCount < 2) {
          setTimeout(async () => {
            console.log(`Retrying preload for msg ${msgId}, attempt ${prevData.retryCount + 1}`);
            let retryUrl = playableUrl;
            if (msg.nonce && !decryptedAudioBlobs[msgId]) {
              // Retry decrypt on error
              try {
                retryUrl = await decryptAudioForPlayback(msgId, url, msg.nonce, msg.publicKeyId || null, msg.sender.id);
              } catch (retryErr) {
                console.error(`Decrypt retry failed:`, retryErr);
                return;  // Don't load
              }
            }
            audio.src = retryUrl.startsWith('http') ? `${retryUrl}?t=${Date.now()}` : retryUrl;
            audio.load();
          }, 2000);
          return {
            ...prev,
            [msgId]: {
              ...prevData,
              isPreloading: false,
              isReady: false,
              error: 'Retrying to preload audio...',
              retryCount: (prevData.retryCount || 0) + 1,
            },
          };
        }
        // Файла на сервере нет (старые записи пропали при перезапуске) —
        // ошибку показываем в самом сообщении, а не тостом: иначе в чате
        // со старыми голосовыми тосты идут сплошным потоком.
        console.warn('Audio preload failed for msg', msgId);
        return {
          ...prev,
          [msgId]: {
            ...prevData,
            isPreloading: false,
            isReady: false,
            decrypted: false,
            error: `Failed to preload audio: ${errorMsg}`,
          },
        };
      });
    }, { once: true });
  };

  const handleDragStart = (msgId, e) => {
    e.preventDefault();
    const audio = audioRefs.current[msgId];
    if (!audio || !audioProgress[msgId]?.isReady) {
      return;
    }
    setIsDragging(msgId);
    handleSeek(msgId, e);
  };

  const handleDragMove = (e) => {
    if (isDragging) {
      handleSeek(isDragging, e);
    }
  };

  const handleDragEnd = () => {
    setIsDragging(null);
  };

  const saveFileToCache = (chatId, userId, messageId, fileData) => {
    try {
      const key = `fileCache_${chatId}_${userId}_${messageId}`;
      const cacheData = {
        fileName: fileData.fileName,
        contentType: fileData.contentType,
        fileUrl: fileData.fileUrl || null, // Сохраняем серверный fileUrl
      };
      localStorage.setItem(key, JSON.stringify(cacheData));
      console.log(`Saved file metadata to cache: messageId=${messageId}, fileName=${fileData.fileName}`);
    } catch (error) {
      console.error(`Failed to save file metadata to cache for messageId=${messageId}:`, error);
    }
  };

  const getFileFromCache = (chatId, userId, messageId) => {
    try {
      const key = `fileCache_${chatId}_${userId}_${messageId}`;
      const stored = localStorage.getItem(key);
      if (stored) {
        const fileData = JSON.parse(stored);
        console.log(`Retrieved file metadata from cache: messageId=${messageId}, fileName=${fileData.fileName}`);
        return fileData;
      }
      return null;
    } catch (error) {
      console.error(`Failed to get file metadata from cache for messageId=${messageId}:`, error);
      return null;
    }
  };

  // [ADD] Функция для скачивания собственного файла из кэша
  const downloadOwnFile = async (msg) => {
    try {
      const fileContentKey = `fileContent_${chatId}_${userId}_${msg.clientMessageId}`;
      const storedFileData = localStorage.getItem(fileContentKey);
      let fileData = {};
      if (storedFileData) {
        fileData = JSON.parse(storedFileData);
      }

      // [OPTIMIZE] Используем кэшированные расшифрованные данные
      if (fileData.fileData) {
        const decryptedFileBytes = sodium.from_base64(fileData.fileData);
        const blob = new Blob([decryptedFileBytes], { type: fileData.contentType || msg.contentType });
        const blobUrl = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = blobUrl;
        link.download = msg.fileName || 'file';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(blobUrl);
        return;
      }

      const fileUrl = fileData.fileUrl || msg.fileUrl;
      if (!fileUrl) throw new Error('File URL not available');

      const fileMetadata = await fetchFileMetadata(msg.id);
      const response = await fetch(fileUrl);
      if (!response.ok) throw new Error(`Failed to fetch file: ${response.status}`);
      const encryptedFileBytes = new Uint8Array(await response.arrayBuffer());

      const recipientId = await resolveRecipientId();

      const decryptedFileBytes = await decryptFileContent(
        encryptedFileBytes,
        fileMetadata.nonce,
        userId,
        fileMetadata.publicKeyId,
        publicKeys,
        keyPair,
        recipientId
      );

      const blob = new Blob([decryptedFileBytes], { type: fileData.contentType || msg.contentType });
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = msg.fileName || 'file';
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(blobUrl);
    } catch (error) {
      console.error('Error downloading own file:', error);
      toast.error(`Failed to download file: ${error.message}`);
    }
  };

  const downloadFile = async (msg) => {
    try {
      const metadata = await fetchFileMetadata(msg.id);
      const { fileUrl, nonce, publicKeyId } = metadata;

      let fileName = 'file';
      let contentType = 'application/octet-stream';

      // === Расшифровка метаданных ===
      if (msg.encryptedContent && msg.nonce) {
        const senderPublicKey = await keyToOpen(msg);

        const decryptedMeta = await decryptMessage(
          msg.encryptedContent,
          msg.nonce,
          msg.sender.id,
          msg.timestamp,
          senderPublicKey
        );
        const meta = JSON.parse(decryptedMeta);
        fileName = meta.fileName || 'file';
        contentType = meta.contentType || meta.mimeType || 'application/octet-stream';
      }

      // === Скачивание и расшифровка файла ===
      const res = await fetch(fileUrl);
      if (!res.ok) throw new Error(`Failed to download file: ${res.status}`);
      const encrypted = new Uint8Array(await res.arrayBuffer());

      // === ГАРАНТИРОВАННАЯ ЗАГРУЗКА КЛЮЧА ПЕРЕД РАСШИФРОВКОЙ ===
      const senderPublicKey = await keyToOpen(msg);

      const decrypted = await decryptFileContent(
        encrypted,
        nonce,
        msg.sender.id,
        publicKeyId,
        publicKeys,
        keyPair,
        userId
      );

      const blob = new Blob([decrypted], { type: contentType });
      const blobUrl = URL.createObjectURL(blob);

      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(blobUrl);
    } catch (error) {
      console.error('Error downloading incoming file:', error);
      toast.error(`Failed to download file: ${error.message}`);
    }
  };

  const decryptAudioForPlayback = async (msgId, fileUrl, nonceFromMsg, publicKeyId, senderId) => {
    try {
      console.log(`Decrypting audio for playback: msgId=${msgId}, fileUrl=${fileUrl}`);
      // nonce файла хранится в file_metadata на сервере; nonce сообщения — это
      // nonce метаданных, и с новых версий клиентов они разные.
      let nonce = nonceFromMsg;
      try {
        const fm = await fetchFileMetadata(msgId);
        if (fm && fm.nonce) nonce = fm.nonce;
      } catch (e) {
        console.warn(`No file metadata for audio ${msgId}, falling back to message nonce`);
      }
      const res = await fetch(fileUrl);
      if (!res.ok) throw new Error(`Fetch failed: ${res.status}`);
      const encryptedBytes = new Uint8Array(await res.arrayBuffer());

      let publicKey;
      let decryptRecipientId;

      if (senderId === userId) {
        // СВОЁ СООБЩЕНИЕ → шифровали на ключ ПОЛУЧАТЕЛЯ
        decryptRecipientId = await resolveRecipientId();
        // publicKeyId здесь наш, отправителя: своё сообщение открывается
        // ключом собеседника
        publicKey = await keyForMessage(decryptRecipientId, null);
      } else {
        // ЧУЖОЕ СООБЩЕНИЕ → шифровали на наш ключ, используем ключ ОТПРАВИТЕЛЯ
        publicKey = await keyForMessage(senderId, publicKeyId);
        decryptRecipientId = userId;
      }


      const decryptedBytes = await decryptFileContent(
        encryptedBytes,
        nonce,
        senderId,
        publicKeyId,
        publicKeys,
        keyPair,
        decryptRecipientId  // ← ВАЖНО: для своих — recipientId
      );

      const mp3Blob = new Blob([decryptedBytes], { type: 'audio/mpeg' });
      const blobUrl = URL.createObjectURL(mp3Blob);

      setDecryptedAudioBlobs(prev => ({ ...prev, [msgId]: { blobUrl, mp3Blob } }));
      console.log(`Decrypted audio blob created: size=${mp3Blob.size}, url=${blobUrl}`);
      return blobUrl;
    } catch (error) {
      console.error(`Audio decrypt failed for ${msgId}:`, error);
      setAudioProgress(prev => ({
        ...prev,
        [msgId]: { ...prev[msgId], error: `Decrypt failed: ${error.message}` }
      }));
      throw error;
    }
  };

  const sendTypingEvent = (isTyping) => {
    if (!client || !client.connected) {
      console.warn('Cannot send typing in SecretChat: STOMP not connected');
      return;
    }

    const payload = {
      messageType: isTyping ? 'TYPING' : 'STOP_TYPING',
      sender: {
        id: userId,
        username: username,
      },
      chat: {
        id: chatId,
      },
    };

    client.publish({
      destination: `/app/typing/${chatId}`,
      body: JSON.stringify(payload),
    });

    console.log(`Typing event sent in SecretChat: ${isTyping ? 'TYPING' : 'STOP_TYPING'}`);
  };

  const openFullScreenImage = (fileUrl, fileName = 'photo.jpg') => {
    setFullScreenImage({ url: fileUrl, fileName });
  };

  const decryptImageForPreview = async (msgId, fileUrl, nonceFromMsg, publicKeyId, senderId) => {
    try {
      console.log(`Decrypting image preview for msg ${msgId}: ${fileUrl}`);

      // 1. Берём актуальные метаданные (правильный nonce и fileUrl)
      const metadata = await fetchFileMetadata(msgId);
      const correctFileUrl = metadata.fileUrl;
      const correctNonce   = metadata.nonce;

      console.log(`Using metadata nonce for file decryption: ${correctNonce}`);

      // 2. Загружаем зашифрованный файл
      const res = await fetch(correctFileUrl);
      if (!res.ok) throw new Error(`Fetch failed: ${res.status}`);
      const encryptedBytes = new Uint8Array(await res.arrayBuffer());

      // 3. Определяем, на чей публичный ключ шифровалось (recipientId для decryption)
      let decryptRecipientId;
      let publicKey;

      if (senderId === userId) {
        // Это своё сообщение → шифровали на ключ собеседника (partnerId)
        decryptRecipientId = await resolveRecipientId();

        // Берём публичный ключ собеседника
        // publicKeyId здесь наш, отправителя: своё сообщение открывается
        // ключом собеседника
        publicKey = await keyForMessage(decryptRecipientId, null);
        console.log(`Own image: using partnerId=${decryptRecipientId} as recipient for decryption (publicKeyId=${publicKeyId || 'current'})`);
      } else {
        // Входящее сообщение → шифровали на наш ключ
        decryptRecipientId = userId;

        // Берём публичный ключ отправителя
        publicKey = await keyForMessage(senderId, publicKeyId);
        console.log(`Incoming image: using userId=${userId} as recipient for decryption (senderId=${senderId}, publicKeyId=${publicKeyId || 'current'})`);
      }

      // Обновляем кэш публичных ключей

      // 4. Расшифровываем содержимое файла
      const decryptedBytes = await decryptFileContent(
        encryptedBytes,
        correctNonce,
        senderId,
        publicKeyId,
        publicKeys,
        keyPair,
        decryptRecipientId   // ← Вот здесь главное изменение!
      );

      // 5. Создаём blob и URL
      const blob = new Blob([decryptedBytes], { type: 'image/jpeg' }); // можно сделать динамическим по contentType, если есть
      const blobUrl = URL.createObjectURL(blob);

      // 6. Сохраняем в state
      setDecryptedImageBlobs(prev => ({ ...prev, [msgId]: blobUrl }));
      console.log(`Image preview decrypted: msg ${msgId} → ${blobUrl}`);

      // 7. Обновляем fileUrl в сообщении (чтобы отображался blob вместо зашифрованного URL)
      setMessages(prev => prev.map(m => 
        m.id === msgId ? { ...m, fileUrl: blobUrl, file_url: blobUrl } : m
      ));

      return blobUrl;

    } catch (error) {
      console.error(`Method decryptImageForPreview. Image decrypt failed for ${msgId}:`, error);
      return null;
    }
  };

  useEffect(() => {
    const imageMessages = messages.filter(msg => 
      msg.messageType === 'file' &&
      msg.fileUrl &&
      !decryptedImageBlobs[msg.id] &&
      !msg.fileUrl.startsWith('blob:') && // ещё не расшифровано
      (msg.contentType?.startsWith('image/') ||
      (msg.fileName && /\.(jpg|jpeg|png|gif|webp)$/i.test(msg.fileName)))
    );

    imageMessages.forEach(msg => {
      if (msg.nonce && msg.fileUrl) {
        console.log(`Auto-decrypting image preview: msg ${msg.id}`);
        decryptImageForPreview(
          msg.id,
          msg.fileUrl,
          msg.nonce,
          msg.publicKeyId || null,
          msg.sender.id
        );
      }
    });
  }, [messages, decryptedImageBlobs]);

  /**
   * Защищённый звонок собеседнику.
   *
   * Перед соединением стороны подписывают SDP ключами Ed25519 и сверяют
   * подписи, поэтому сервер не может незаметно встать между собеседниками.
   * Запускался он только из меню «…» на странице человека — место, где его
   * никто не искал; в секретном чате он и нужен. Звонок с переводом сюда,
   * наоборот, не ставим: перевод идёт через сервер.
   */
  // Звонок из секретного чата — обычный: подпись теперь в каждом звонке,
  // отдельного «защищённого» больше нет
  const startCallFromHeader = async (type) => {
    if (!partnerId) {
      toast.error('Cannot find who to call');
      return;
    }
    const roomId = `room-${userId}-${partnerId}`;
    const callerName = localStorage.getItem('username') || 'Unknown';
    const callRequest = {
      callerId: Number(userId),
      recipientId: Number(partnerId),
      roomId,
      callerName,
      type,
    };
    const partner = partnerNameRef.current || 'the other side';
    try {
      const response = await fetch('https://unlimitedpossibilities12.org/api/webrtc/call', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(callRequest),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      navigate(`/${type === 'video' ? 'video-call' : 'voice-call'}/${roomId}`, {
        state: {
          incomingCall: callRequest,
          isCaller: true,
          currentUserId: Number(userId),
          callerName,
          recipientName: partner,
        },
      });
      toast.success(`Calling ${partner}…`);
    } catch (error) {
      console.error('Не удалось начать звонок:', error);
      toast.error('Failed to start the call');
    }
  };

  return (
    <div className="secret-chat">
      {выборИдёт && (
        <div className="полоса-выбора">
          <button
            type="button"
            className="полоса-выбора-убрать"
            onClick={() => { setУбратьУОбоих(false); setУбрать({ вид: 'набор', ключи: [...выбранные] }); }}
          >
            Delete {выбранные.size}
          </button>
          <button type="button" className="полоса-выбора-отмена" onClick={закончитьВыбор}>Cancel</button>
        </div>
      )}
      {/* Большая фиксированная иконка ассистента — только на ПК */}
      <AssistantIcon size="large" className="fixed-app-bottom-right" />
      <div className="secret-chat-name">
        <div className="secret-header-content">
          {/* Маленькая иконка ассистента — ТОЛЬКО на мобильных, слева от замка */}
          <AssistantIcon size="small" className="mobile-assistant-icon secret-mobile-assistant-left" />
          {/* Аватар + имя + typing — центрировано */}
          <div className="secret-partner-container">
            {/* Круглый аватар с кликабельностью */}
            <div 
              className="secret-partner-avatar-wrapper"
              onClick={() => partnerId && navigate(`/user/${partnerId}`)}
              style={{ cursor: partnerId ? 'pointer' : 'default' }}
            >
              {partnerAvatar && !brokenAvatars.partner ? (
                <img
                  src={`https://unlimitedpossibilities12.org${partnerAvatar}`}
                  alt="Contact avatar"
                  className="secret-partner-avatar"
                  onError={() => setBrokenAvatars((broken) => ({ ...broken, partner: true }))}
                />
              ) : (
                <div className="secret-partner-avatar secret-partner-avatar-placeholder">
                  {displayChatName
                    .replace('Secret Chat with ', '')
                    .charAt(0)
                    .toUpperCase() || '?'}
                </div>
              )}

              {/* Зелёная точка онлайн-статуса */}
              {partnerId && usersStatus[partnerId]?.online && (
                <span className="secret-status-indicator-online"></span>
              )}
            </div>

            {/* Имя и состояние: метки включённых режимов записаны так же,
                как в остальных чатах */}
            <div className="secret-partner-text">
              <div className="partner-name">
                {displayChatName.replace('Secret Chat with ', '')}
              </div>
              <div className="chat-status-line">
                {isTyping ? (
                  <span className="chat-status-typing">{typingName} is typing…</span>
                ) : (
                  <span className="chat-status-text">
                    {partnerId && usersStatus[partnerId]?.online ? 'online' : 'offline'}
                  </span>
                )}
                <span className="chat-mode-chip" title="End-to-end encrypted">SECRET</span>
                {isLocalTranslationEnabled(chatId) && localTargetLanguage(chatId) && (
                  <span className="chat-mode-chip" title="Messages are translated on your device">
                    {`→${String(localTargetLanguage(chatId)).toUpperCase()}`}
                  </span>
                )}
              </div>
            </div>
          </div>

          {/* Справа: отпечаток ключей и меню. Замок важен именно здесь —
              это единственный способ сверить шифрование. */}
          <div className="chat-header-actions">
            {/* Трубка и камера — те же, что в обычной переписке: звонок из
                секретного чата ничем не отличается, подпись есть в каждом */}
            <button
              type="button"
              className="chat-header-button"
              title="Voice call"
              onClick={() => startCallFromHeader('voice')}
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M5 4.5h3.5l1.5 4-2 1.5a11 11 0 006 6l1.5-2 4 1.5V19a1.5 1.5 0 01-1.7 1.5C10.8 19.8 4.2 13.2 3.5 6.2A1.5 1.5 0 015 4.5z" />
              </svg>
            </button>
            <button
              type="button"
              className="chat-header-button"
              title="Video call"
              onClick={() => startCallFromHeader('video')}
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M15 10.5V7a1 1 0 00-1-1H4a1 1 0 00-1 1v10a1 1 0 001 1h10a1 1 0 001-1v-3.5l5 4v-11z" />
              </svg>
            </button>
            {/* Замок остаётся на виду только тогда, когда есть о чём
                предупредить: ключ собеседника сменился. В остальное время
                отпечаток живёт в меню, как и перевод. */}
            {partnerKeyChanged && (
              <button
                type="button"
                className="chat-header-button chat-header-button-warn"
                title="The key has changed — compare the fingerprint"
                onClick={() => setShowSafetyNumber((v) => !v)}
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
                  <rect x="4.5" y="10.5" width="15" height="10" rx="2.5" />
                  <path d="M8.5 10.5V7.5a3.5 3.5 0 017 0v3" />
                </svg>
              </button>
            )}
            <div className="chat-header-menu-wrap" ref={chatMenuRef}>
              <button
                type="button"
                className="chat-header-button"
                title="More"
                onClick={() => setChatMenuOpen((open) => !open)}
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <circle cx="12" cy="5" r="1.8" />
                  <circle cx="12" cy="12" r="1.8" />
                  <circle cx="12" cy="19" r="1.8" />
                </svg>
              </button>
              {chatMenuOpen && (
                <div className="chat-header-menu">
                  <button type="button" onClick={() => { setChatMenuOpen(false); setShowTranslationDialog(true); }}>
                    On-device translation…
                  </button>
                  <button type="button" onClick={() => { setChatMenuOpen(false); setShowSafetyNumber(true); }}>
                    Encryption fingerprint
                  </button>
                  <button
                    type="button"
                    onClick={() => { setChatMenuOpen(false); if (partnerId) navigate(`/user/${partnerId}`); }}
                  >
                    View profile
                  </button>
                </div>
              )}
            </div>
          </div>
          {showTranslationDialog && (
            <SecretTranslationDialog
              chatId={chatId}
              onClose={() => setShowTranslationDialog(false)}
              onSaved={() => { clearLocalTranslationCache(); setTranslationRefresh((n) => n + 1); }}
            />
          )}
          {(showSafetyNumber || partnerKeyChanged) && (
            <div className="secret-safety-panel" onClick={() => setShowSafetyNumber(false)}>
              {partnerKeyChanged && (
                <div className="secret-safety-warning">
                  ⚠️ Your contact's key has changed. If they did not reinstall the app or
                  sign in again, compare the fingerprint over another channel.
                  <button
                    className="secret-safety-dismiss"
                    onClick={(e) => { e.stopPropagation(); setPartnerKeyChanged(false); }}
                  >
                    Got it
                  </button>
                </div>
              )}
              <div className="secret-safety-title">Encryption fingerprint</div>
              <div className="secret-safety-number">{safetyNumber || 'keys are not loaded yet'}</div>
              <div className="secret-safety-hint">
                It must match the fingerprint on your contact's device.
              </div>
            </div>
          )}
        </div>
      </div>

      {далекоОтНиза && (
        <button
          type="button"
          className="chat-scroll-down"
          onClick={кПоследнему}
          aria-label="To the latest message"
          title="To the latest message"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M6 9l6 6 6-6" />
          </svg>
          {новых > 0 && <span className="chat-scroll-down-count">{новых > 99 ? '99+' : новых}</span>}
        </button>
      )}
      <div
        className={`secret-message-list${выборИдёт ? ' идёт-выбор' : ''}`}
        ref={messageListRef}
        style={скрытаЛента ? { visibility: 'hidden' } : undefined}
      >
        {лента.map((msg, index) => (
          <React.Fragment key={msg.id}>
          {startsNewDay(лента, index) && (
            <div className="private-day-separator">
              <span>{dayLabel(msg.timestamp)}</span>
            </div>
          )}
          {msg.звонок ? (
            (() => {
              const з = msg.звонок;
              const мой = String(з.caller) === String(userId);
              const нетОтвета = з.status === 'missed' || з.status === 'rejected';
              const длина = длительность(з.durationSeconds);
              return (
                <div className={`строка-звонка${мой ? ' своя' : ''}${выбрана(msg) ? ' выбрана' : ''}${
                  тотЖеПодряд(лента, index, userId) ? ' подряд' : ''}`} onClickCapture={(e) => {
            /* Пока идёт выбор, нажатие в любом месте строки — и по
               пустому полю рядом с пузырём, и по кружку — выделяет её. */
            if (!выборИдёт) return;
            e.preventDefault();
            e.stopPropagation();
            переключитьВыбор(msg);
          }}>
                <div
                  data-row={msg.id}
                  className={`private-call-card${мой ? ' is-mine' : ''}${нетОтвета ? ' is-missed' : ''}`}
                  onContextMenu={(e) => handleRightClick(e, msg)}
                  onClickCapture={(e) => {
                    if (!выборИдёт) return;
                    e.preventDefault();
                    e.stopPropagation();
                    переключитьВыбор(msg);
                  }}
                  title="Right-click to delete"
                >
                  <div className="private-call-body">
                    <div className="private-call-title">
                      {(з.callType === 'video' ? 'Video ' : '')}
                      {нетОтвета
                        ? (мой ? (з.callType === 'video' ? 'cancelled call' : 'Cancelled call')
                               : (з.callType === 'video' ? 'missed call' : 'Missed call'))
                        : (мой ? (з.callType === 'video' ? 'outgoing call' : 'Outgoing call')
                               : (з.callType === 'video' ? 'incoming call' : 'Incoming call'))}
                    </div>
                    <div className="private-call-sub">
                      <span className="private-call-arrow">{мой ? '↗' : '↙'}</span>
                      {new Date(з.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                      {длина && !нетОтвета ? `, ${длина}` : ''}
                    </div>
                  </div>
                  <button
                    type="button"
                    className="private-call-again"
                    title={з.callType === 'video' ? 'Video call' : 'Call back'}
                    onClick={(e) => {
                      /* Трубка на карточке звонит снова — тем же видом связи.
                         Пока идёт выбор, нажатие ловит обёртка строки. */
                      e.stopPropagation();
                      startCallFromHeader(з.callType === 'video' ? 'video' : 'voice');
                    }}
                  >
                  <svg className="private-call-phone" viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M6.6 10.8a15 15 0 0 0 6.6 6.6l2.2-2.2c.3-.3.7-.4 1-.2 1.2.4 2.4.6 3.6.6.6 0 1 .4 1 1V20c0 .6-.4 1-1 1A17 17 0 0 1 3 4c0-.6.4-1 1-1h3.5c.6 0 1 .4 1 1 0 1.3.2 2.5.6 3.6.1.4 0 .8-.2 1l-2.3 2.2Z" />
                  </svg>
                  </button>
                </div>
                {выборИдёт && (
                  <span className={`кружок-выбора${выбрана(msg) ? ' выбран' : ''}`} aria-hidden="true">
                    <svg viewBox="0 0 24 24"><path d="M7 12.5l3.2 3.2L17 9" /></svg>
                  </span>
                )}
                </div>
              );
            })()
          ) : (
          <div className={`строка-ленты${msg.sender.id === userId ? ' своя' : ''}${выбрана(msg) ? ' выбрана' : ''}`} onClickCapture={(e) => {
            /* Пока идёт выбор, нажатие в любом месте строки — и по
               пустому полю рядом с пузырём, и по кружку — выделяет её. */
            if (!выборИдёт) return;
            e.preventDefault();
            e.stopPropagation();
            переключитьВыбор(msg);
          }}>
          <div
            data-row={msg.id}
            className={`secret-message ${
              msg.sender.id === userId ? 'secret-current-user' : 'secret-other-user'
            } ${isFirstMessage(msg, index) ? 'first-message' : ''} ${
              msg.messageType === 'audio' ? 'secret-audio-message' : ''
            } ${тотЖеПодряд(лента, index, userId) ? 'подряд' : ''}`}
            onContextMenu={(e) => handleRightClick(e, msg)}
            onClickCapture={(e) => {
              /* Пока идёт выбор, нажатие внутри строки её выделяет. */
              if (!выборИдёт) return;
              e.preventDefault();
              e.stopPropagation();
              переключитьВыбор(msg);
            }}
          >
            <div className="secret-message-wrapper">
              {shouldShowAvatar(msg, index) && (
                <div className="secret-avatar-container">
                  {msg.sender.avatar && !brokenAvatars[msg.sender.id] ? (
                    <div className="secret-avatar-wrapper">
                      <img
                        src={`https://unlimitedpossibilities12.org${msg.sender.avatar}`}
                        alt={`${msg.sender.username} avatar`}
                        className="secret-message-avatar"
                        onClick={() => handleAvatarClick(msg.sender.id)}
                        style={{ cursor: 'pointer' }}
                        onError={() =>
                          setBrokenAvatars((broken) => ({ ...broken, [msg.sender.id]: true }))
                        }
                      />
                      {usersStatus[msg.sender.id]?.online && (
                        <span className="status-indicator-online-secretchat"></span>
                      )}
                    </div>
                  ) : (
                    <div className="secret-avatar-wrapper">
                      <div
                        className="secret-message-avatar"
                        onClick={() => handleAvatarClick(msg.sender.id)}
                        style={{ cursor: 'pointer' }}
                      >
                        {msg.sender.username.charAt(0).toUpperCase()}
                      </div>
                      {usersStatus[msg.sender.id]?.online && (
                        <span className="status-indicator-online-secretchat"></span>
                      )}
                    </div>
                  )}
                </div>
              )}
              <div
                className={`secret-message-content-container ${
                  shouldShowAvatar(msg, index) ? '' : 'no-avatar'
                } ${
                  // Время в углу последней строки — только у простых сообщений
                  msg.messageType !== 'audio' && msg.messageType !== 'file' && !msg.fileUrl ? 'inline-time' : ''
                } ${
                  // Снимок занимает пузырь целиком, без полей-рамки
                  msg.messageType === 'file' && msg.fileUrl
                    && (decryptedImageBlobs[msg.id] || msg.fileUrl.startsWith('blob:')) ? 'with-photo' : ''
                }`}
              >
                <div className="secret-message-body">
                  <div className="secret-message-content-wrapper">
                    {msg.messageType === 'audio' ? (
                      <div className="secret-audio-message-container">
                        <div className="secret-audio-player-wrapper">
                          {/* Играется только расшифрованная запись: по
                              исходному адресу лежит шифртекст */}
                          {decryptedAudioBlobs[msg.id]?.blobUrl && (
                          <audio
                            ref={(el) => {
                              if (el && audioRefs.current[msg.id] !== el) {
                                audioRefs.current[msg.id] = el;
                                console.log(`Audio ref set for msg ${msg.id}, readyState: ${el?.readyState}`);
                              }
                            }}
                            src={decryptedAudioBlobs[msg.id].blobUrl}
                            onTimeUpdate={() => handleTimeUpdate(msg.id, audioRefs.current[msg.id])}
                            onLoadedMetadata={() => handleLoadedMetadata(msg.id, audioRefs.current[msg.id])}
                            onCanPlay={() => handleCanPlay(msg.id)}
                            onError={async (e) => {
                              const audio = audioRefs.current[msg.id];
                              if (!decryptedAudioBlobs[msg.id] && msg.nonce && msg.fileUrl) { // Retry decrypt on error, if encrypted
                                console.log(`Audio error ${msg.id}: retrying decrypt`);
                                try {
                                  const newUrl = await decryptAudioForPlayback(msg.id, msg.fileUrl, msg.nonce, msg.publicKeyId || null, msg.sender.id);
                                  audio.src = newUrl;
                                  audio.load();
                                  setAudioProgress(prev => ({ ...prev, [msg.id]: { ...prev[msg.id], error: null } }));
                                } catch (retryErr) {
                                  console.error(`Decrypt retry on error failed:`, retryErr);
                                  handleAudioError(msg.id, e);
                                }
                              } else {
                                handleAudioError(msg.id, e);
                              }
                            }}
                            onEnded={() => handleAudioEnded(msg.id)}
                            className="secret-audio-player"
                            preload="auto"
                          >
                            Your browser does not support the audio element.
                          </audio>
                          )}
                          {audioProgress[msg.id]?.error ? (
                            <span className="secret-audio-error">{audioProgress[msg.id].error}</span>
                          ) : (
                            <div className="secret-audio-controls">
                              <button
                                className="secret-audio-play-pause"
                                onClick={() => {
                                  const audio = audioRefs.current[msg.id];
                                  if (!audio || !audioProgress[msg.id]?.isReady) {
                                    return;
                                  }
                                  if (audio.paused) {
                                    audio.play().catch((e) => handleAudioError(msg.id, e));
                                  } else {
                                    audio.pause();
                                  }
                                  setAudioProgress((prev) => ({
                                    ...prev,
                                    [msg.id]: {
                                      ...prev[msg.id],
                                      isPlaying: audio.paused ? false : true,
                                    },
                                  }));
                                }}
                                disabled={!decryptedAudioBlobs[msg.id]?.blobUrl || !audioProgress[msg.id]?.isReady}
                              >
                                {audioProgress[msg.id]?.isPlaying ? (
                                  <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
                                    <rect x="6" y="5" width="4" height="14" rx="1.2" fill="currentColor" />
                                    <rect x="14" y="5" width="4" height="14" rx="1.2" fill="currentColor" />
                                  </svg>
                                ) : (
                                  <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
                                    <path d="M8 5.5v13l11-6.5z" fill="currentColor" />
                                  </svg>
                                )}
                              </button>
                              <AudioWaveform
                                url={decryptedAudioBlobs[msg.id]?.blobUrl || msg.fileUrl}
                                progress={
                                  audioProgress[msg.id]?.duration && audioProgress[msg.id]?.duration !== Infinity
                                    ? (audioProgress[msg.id].currentTime || 0) / audioProgress[msg.id].duration
                                    : 0
                                }
                                onClick={(e) => handleSeek(msg.id, e)}
                                onMouseDown={(e) => handleDragStart(msg.id, e)}
                                style={{ cursor: audioProgress[msg.id]?.duration === 0 ? 'not-allowed' : 'pointer' }}
                              />
                              <span className="secret-audio-time">
                                {formatAudioTime(
                                  audioProgress[msg.id]?.isPlaying || audioProgress[msg.id]?.currentTime
                                    ? audioProgress[msg.id].currentTime
                                    : audioProgress[msg.id]?.duration || 0
                                )}
                              </span>
                            </div>
                          )}
                        </div>
                      </div>
                    ) : (
                      <>
                        {/* Снимок идёт перед подписью: прежде подпись стояла над
                            фотографией, а принято наоборот — сперва снимок. */}
                        {/* Превью изображения — используем расшифрованный blob или локальный */}
                        {msg.messageType === 'file' && msg.fileUrl && (
                          (decryptedImageBlobs[msg.id] || msg.fileUrl.startsWith('blob:')) && (
                            <div className="image-preview-container">
                              <img
                                key={decryptedImageBlobs[msg.id] || msg.fileUrl}   // ← ЭТО САМОЕ ГЛАВНОЕ
                                src={decryptedImageBlobs[msg.id] || msg.fileUrl}
                                alt="Image in the message"
                                className="message-image-preview"
                                onClick={() => openFullScreenImage(
                                  decryptedImageBlobs[msg.id] || msg.fileUrl,
                                  msg.fileName || 'photo.jpg'   // ← оригинальное имя с расширением!
                                )}
                                onError={(e) => {
                                  // Картинка не открылась — обычно файл потерян при
                                  // переразвёртывании сервера. Прятать её молча нельзя:
                                  // в пузыре остаётся пустая полоса без объяснения.
                                  e.target.style.display = 'none';
                                  const box = e.target.parentElement;
                                  if (box && !box.querySelector('.attachment-unavailable')) {
                                    const note = document.createElement('span');
                                    note.className = 'attachment-unavailable';
                                    note.textContent = 'Image unavailable';
                                    box.appendChild(note);
                                  }
                                  console.warn('Ошибка загрузки превью:', msg.fileUrl);
                                }}
                              />
                            </div>
                          )
                        )}

                        {(() => {
                          const st = parseSecretText(msg.content);
                          return (
                            <>
                              <span className="secret-message-content">
                                {st.text}
                                {/* Время — в конце последней строки, если перевода нет */}
                                {inlineTime(msg) && !hasLocalTranslation() && (
                                  <span className="private-message-footer inline">
                                    <span className="secret-message-timestamp">{formatTimestamp(msg.timestamp)}</span>
                                    {msg.sender.id === userId && (
                                      <span className="secret-message-status">
                                        <MessageChecks delivered={isDeliveredFlag(msg)} read={isReadFlag(msg)} />
                                      </span>
                                    )}
                                  </span>
                                )}
                              </span>
                              <LocalTranslatedLine
                                chatId={chatId}
                                msgId={msg.id}
                                text={st.text}
                                translated={st.translated}
                                lang={st.lang}
                                refreshKey={translationRefresh}
                              />
                            </>
                          );
                        })()}

                        {/* Ссылка на скачивание — только у непохожего на снимок.
                            У фотографии под пузырём висела строка вида
                            «Download 1776010722494_image__11___1_.jpg» — имя с
                            сервера, никому ничего не говорящее. Снимок и так
                            открывается нажатием, а сохранить его можно из
                            просмотрщика. */}
                        {msg.messageType === 'file' && msg.fileUrl
                          && !(decryptedImageBlobs[msg.id] || msg.fileUrl.startsWith('blob:')) && (
                          <a
                            href="#"
                            className="secret-file-link"
                            onClick={async (e) => {
                              e.preventDefault();
                              if (msg.sender.id === userId) {
                                await downloadOwnFile(msg);
                              } else {
                                await downloadFile(msg);
                              }
                            }}
                          >
                            Download {msg.fileName || 'Download file'}
                          </a>
                        )}
                      </>
                    )}
                    {/* Время и галочки отдельной строкой — у вложений и там, где есть перевод */}
                    {(!inlineTime(msg) || hasLocalTranslation()) && (
                    <div className="private-message-footer">
                      <div className="secret-message-timestamp">{formatTimestamp(msg.timestamp)}</div>
                      {msg.sender.id === userId && (
                        <div className="secret-message-status">
                          <MessageChecks delivered={isDeliveredFlag(msg)} read={isReadFlag(msg)} />
                        </div>
                      )}
                    </div>
                    )}
                  </div>
                </div>
              </div>
            </div>
          </div>
          {выборИдёт && (
            <span className={`кружок-выбора${выбрана(msg) ? ' выбран' : ''}`} aria-hidden="true">
              <svg viewBox="0 0 24 24"><path d="M7 12.5l3.2 3.2L17 9" /></svg>
            </span>
          )}
          </div>
          )}
          </React.Fragment>
        ))}
        <div ref={messagesEndRef} />

      {убрать && (
        <div className="private-call-ask-backdrop" onClick={() => setУбрать(null)} role="presentation">
          <div className="private-call-ask" onClick={(e) => e.stopPropagation()}>
            <h3>Delete message</h3>
            <p>Are you sure you want to delete this message?</p>
            <label>
              <input
                type="checkbox"
                checked={убратьУОбоих}
                onChange={(e) => setУбратьУОбоих(e.target.checked)}
              />
              Also delete for the other side
            </label>
            <div className="private-call-ask-buttons">
              <button type="button" onClick={() => setУбрать(null)}>Cancel</button>
              <button type="button" className="is-danger" onClick={убратьВыбранное}>Delete</button>
            </div>
          </div>
        </div>
      )}

      </div>
      <div className="secret-input-container">
        <div className="secret-textarea-wrapper">
          <div className="secret-textarea-inner">
            <span
              onClick={() => fileInputRef.current.click()}
              className="secret-attach-icon"
              role="button"
              aria-label="Attach File"
            >
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M16.5 6v11.5c0 2.21-1.79 4-4 4s-4-1.79-4-4V5a2.5 2.5 0 015 0v10.5c0 .55-.45 1-1 1s-1-.45-1-1V6H10v9.5a2.5 2.5 0 005 0V5c0-2.21-1.79-4-4-4S7 2.79 7 5v12.5c0 3.04 2.46 5.5 5.5 5.5s5.5-2.46 5.5-5.5V6h-1.5z" fill="#757575"/>
              </svg>
            </span>
            <textarea
              ref={textAreaRef}
              value={content}
              onChange={handleInputChange}
              onKeyDown={handleKeyDown}
              placeholder="Enter your message..."
              className="secret-input-message"
              rows={rows}
            />
            <input
              type="file"
              onChange={(e) => setSelectedFile(e.target.files[0])}
              className="secret-file-input"
              ref={fileInputRef}
              id="secret-file-input"
            />
            {content.length === 0 && micIconState !== 'sending' && !selectedFile && (
              <div className="secret-mic-icon-container">
                <span
                  onClick={isRecording ? stopRecording : startRecording}
                  className={`secret-mic-icon ${micIconState} ${!userId ? 'disabled' : ''}`}
                  role="button"
                  aria-label={micIconState === 'mic' ? 'Start Recording' : micIconState === 'recording' ? 'Stop Recording' : 'Sending Audio'}
                >
                  {micIconState === 'mic' && (
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z" fill="#757575"/>
                      <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z" fill="#757575"/>
                    </svg>
                  )}
                  {micIconState === 'recording' && (
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <circle cx="12" cy="12" r="8" fill="#FF0000"/>
                    </svg>
                  )}
                  {micIconState === 'sending' && (
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" fill="#0288D1"/>
                    </svg>
                  )}
                </span>
              </div>
            )}
            {(content.length > 0 || selectedFile) && (
              <div className="secret-send-icon-container">
                <span
                  onClick={sendMessage}
                  className="secret-send-icon"
                  role="button"
                  aria-label="Send Message"
                >
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" fill="#0288D1"/>
                  </svg>
                </span>
              </div>
            )}
          </div>
          {selectedFile && (
            <div className="secret-attached-file">
              <span className="secret-attached-file-name">{selectedFile.name}</span>
              <button
                className="secret-remove-file-button"
                onClick={() => {
                  setSelectedFile(null);
                  if (fileInputRef.current) {
                    fileInputRef.current.value = null;
                  }
                }}
                aria-label="Remove attached file"
              >
                ✕
              </button>
            </div>
          )}
        </div>
      </div>
      {contextMenu.visible && contextMenu.message && (
        <div
          ref={contextMenuRef}
          className="secret-context-menu"
          style={{
            position: 'fixed',
            top: contextMenu.y + 'px',
            left: contextMenu.x + 'px',
            zIndex: 10000,
          }}
        >
          {contextMenu.message.messageType === 'text' && моёСообщение(contextMenu.message) && (
            <button onClick={() => handleMenuAction('edit')}><ЗначокПравки /><span>Edit</span></button>
          )}
          <button onClick={() => handleMenuAction('delete')}><ЗначокКорзины /><span>Delete</span></button>
          {/* «Выделить» — как в телеграме, последним пунктом. Само
              выделение сделаем следующим шагом. */}
          <button onClick={() => handleMenuAction('select')}><ЗначокВыбора /><span>Select</span></button>
        </div>
      )}
      {/* Полноэкранное фото */}
      {fullScreenImage && (
        <div 
          className="full-screen-image-overlay"
          onClick={() => setFullScreenImage(null)}
        >
          <div 
            className="full-screen-image-content"
            onClick={(e) => e.stopPropagation()}
          >
            <button 
              className="full-screen-close-btn"
              onClick={() => setFullScreenImage(null)}
            >
              ×
            </button>

            <img 
              src={fullScreenImage.url} 
              alt="Full screen image" 
              className="full-screen-image"
            />

            <button
              className="full-screen-download-btn"
              title="Download the photo"
              onClick={async () => {
                try {
                  const response = await fetch(fullScreenImage.url);
                  if (!response.ok) throw new Error('Could not download the photo');
                  const blob = await response.blob();

                  const url = URL.createObjectURL(blob);
                  const link = document.createElement('a');
                  link.href = url;
                  link.download = fullScreenImage.fileName;  // ← оригинальное имя с расширением!
                  document.body.appendChild(link);
                  link.click();
                  document.body.removeChild(link);
                  URL.revokeObjectURL(url);
                } catch (err) {
                  console.error('Ошибка скачивания из полноэкранного:', err);
                  toast.error('Could not download the photo');
                }
              }}
            >
              ↓
            </button>
          </div>
        </div>
      )}
      {incomingCall && (
        <IncomingCallNotification
          callerId={incomingCall.callerId}
          callerName={incomingCall.callerName || 'Unknown'}
          avatar={incomingCall.avatar}
          callType={incomingCall.type}
          onAccept={() => acceptCall(navigate)}
          onReject={rejectCall}
        />
      )}
    </div>
  );
};

export default SecretPrivateChat;