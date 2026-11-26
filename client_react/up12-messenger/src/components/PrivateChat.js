import MessageChecks from './MessageChecks';
import { isReadFlag, isDeliveredFlag } from '../api/messageFlags';
import { nameOf } from '../api/names';
import { findPartner, partnerNameFromChat } from '../api/participants';
import { stompAuthHeaders } from '../authToken';
import React, { useEffect, useState, useRef, useMemo } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages } from '../api/messages';
import '../styles/PrivateChat.css';
import { toast } from 'react-toastify';
import { useParams, useNavigate } from 'react-router-dom';
import { getChatById } from '../api/chats';
import IncomingCallNotification from './IncomingCallNotification';
import { format, isToday, parseISO } from 'date-fns';
import { useUserInteraction } from '../context/UserInteractionContext';
import { useUserStatusContext } from '../context/UserStatusContext';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import { usePrivateMessageNotification } from '../context/PrivateMessageNotificationContext';
import TranslationSettingsModal from './TranslationSettingsModal';
import AssistantIcon from './AssistantIcon';
import { translateMessage, saveTranslation } from '../api/messages';
import AudioWaveform from './AudioWaveform';
import usePrivateChatTranslationAndSpellCheck from '../hooks/usePrivateChatTranslationAndSpellCheck';
import { FFmpeg } from '@ffmpeg/ffmpeg';
import TranscriptionSettingsModal from './TranscriptionSettingsModal';
import AiMessageGeneratorDialog from './AiMessageGeneratorDialog';
import { getCachedPreview, loadPreview, shortLinkLabel } from '../utils/linkPreviewCache';
import { isMissingAttachment, rememberMissingAttachment } from '../utils/missingAttachments';
import { loadCachedMessages, cacheMessages } from '../utils/messageCache';


/**
 * Разделители дат в ленте. Без них сообщения разных дней идут подряд, и
 * непонятно, где кончился вчерашний разговор, — так во всех мессенджерах.
 */

/** Сообщение, состоящее из одной ссылки: переводить в нём нечего. */
/**
 * Можно ли подвёрстать время в конец последней строки текста.
 *
 * У голосовых, файлов и превью ссылок своя раскладка — там время остаётся
 * отдельной строкой, иначе оно наложилось бы на плеер или картинку.
 */
const canFitTimeInline = (msg, previews) =>
  msg.messageType !== 'audio' && !msg.fileUrl && !previews[msg.id];

const isLinkOnly = (text) => {
  const trimmed = String(text || '').trim();
  return /^https?:\/\/\S+$/i.test(trimmed);
};

/**
 * Есть ли у сообщения перевод, который действительно покажут.
 *
 * Поле перевода бывает заполнено пробелами — от прежних попыток перевести или
 * от выключения перевода. Такой «перевод» рисовался пустой строкой: в пузыре
 * появлялась полоса пустоты, а время съезжало под неё. У соседних сообщений
 * без этого следа время оставалось в строке — отсюда разнобой в ленте.
 */
const hasTranslation = (msg) =>
  Boolean(msg.translatedContent && String(msg.translatedContent).trim()) && !isLinkOnly(msg.content);


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

const PrivateChat = () => {
  const [messages, setMessages] = useState([]);
  const [content, setContent] = useState('');
  const [selectedFile, setSelectedFile] = useState(null);
  const [userId, setUserId] = useState(Number(localStorage.getItem('userId')));
  const [username, setUsername] = useState(localStorage.getItem('username'));
  const [client, setClient] = useState(null);
  const [chatId, setChatId] = useState(null);
  const [chatName, setChatName] = useState('');
  const [displayChatName, setDisplayChatName] = useState('');
  const [editingMessageId, setEditingMessageId] = useState(null);
  // Файлы на сервере пропадают при переразвёртывании: если картинка не
  // открылась, показываем кружок с буквой, а не битый значок
  const [brokenAvatars, setBrokenAvatars] = useState({});
  // Картинки, которых больше нет на сервере: вместо пустоты показываем подпись
  const [brokenImages, setBrokenImages] = useState({});
  const [chatMenuOpen, setChatMenuOpen] = useState(false);
  const chatMenuRef = useRef(null);
  const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, message: null });
  const messagesEndRef = useRef(null);
  const messageListRef = useRef(null);
  const fileInputRef = useRef(null);
  const mediaRecorderRef = useRef(null); // ДОБАВЛЕНО: Референция для MediaRecorder
  const contextMenuRef = useRef(null);
  const { chatId: chatIdParam } = useParams();
  const navigate = useNavigate();
  const { userInteracted, handleInteraction } = useUserInteraction();
  const { usersStatus } = useUserStatusContext();
  const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
  const { setCurrentUserId: setNotificationUserId, setCurrentChatId } = usePrivateMessageNotification();
  const [currentUserId, setCurrentUserId] = useState(null);

  // Настройки читаем сразу: от них зависит, что показать в первом же кадре
  const [isTranslationEnabled, setIsTranslationEnabled] = useState(
    () => localStorage.getItem(`translation_enabled_${chatIdParam}`) === 'true');
  const [translationLanguage, setTranslationLanguage] = useState(
    () => localStorage.getItem(`translation_language_${chatIdParam}`) || 'auto');
  const [translationMode, setTranslationMode] = useState(
    () => localStorage.getItem(`translation_mode_${chatIdParam}`) || 'selective');
  const [translationQueue, setTranslationQueue] = useState([]);
  const [isTranslating, setIsTranslating] = useState(false);
  const [showTranslationModal, setShowTranslationModal] = useState(false);

  const [isSpellCheckEnabled, setIsSpellCheckEnabled] = useState(false);

  const [isTTSEnabled, setIsTTSEnabled] = useState(
    () => localStorage.getItem(`tts_enabled_${chatIdParam}`) === 'true');
  
  // Состояния для аудиозаписи
  const [isRecording, setIsRecording] = useState(false);
  const [audioBlob, setAudioBlob] = useState(null);
  const [micIconState, setMicIconState] = useState('mic'); // 'mic', 'recording', 'sending'

  // Состояния для аудиоплеера
  const [audioProgress, setAudioProgress] = useState({}); // { [msgId]: { currentTime: 0, duration: 0, isPlaying: false, isReady: false, error: null } }
  const audioRefs = useRef({}); // Референции для аудиоэлементов
  const ttsAudioRef = useRef(new Audio()); // Референция для TTS-аудио

  // Состояния для динамического размера текстового поля
  const [rows, setRows] = useState(1);
  const [charPerLine, setCharPerLine] = useState(50);
  const textAreaRef = useRef(null);

  // Состояние для FFmpeg
  const ffmpeg = useRef(new FFmpeg());
  const [ffmpegLoaded, setFfmpegLoaded] = useState(false);

  // ДОБАВЛЕНО: Состояние для отслеживания перетаскивания
  const [isDragging, setIsDragging] = useState(null); // msgId, если перетаскивание активно

  const [isTranscriptionEnabled, setIsTranscriptionEnabled] = useState(
    () => localStorage.getItem(`transcription_enabled_${chatIdParam}`) === 'true');
  const [transcriptionMode, setTranscriptionMode] = useState(
    () => localStorage.getItem(`transcription_mode_${chatIdParam}`) || 'selective');
  const [showTranscriptionModal, setShowTranscriptionModal] = useState(false);

  // ДОБАВЛЕНО: Состояние для транскрибированного контента
  const [transcribedContentMap, setTranscribedContentMap] = useState({});

  // Состояние для очереди транскрипции
  const [transcriptionQueue, setTranscriptionQueue] = useState([]);
  const [isTranscribing, setIsTranscribing] = useState(false);

  // Для индикатора "печатает…"
  const [isTyping, setIsTyping] = useState(false);
  const [typingName, setTypingName] = useState('');
  const typingTimerRef = useRef(null); // для таймера скрытия

  const [partnerAvatar, setPartnerAvatar] = useState(null);
  const [partnerId, setPartnerId] = useState(null);

  /**
   * Звонки с этим собеседником — карточками в ленте.
   *
   * Сервер хранит их давно: кто кому, когда, сколько длился, чем
   * кончился. Видны они были только в разделе Calls, хотя место им —
   * в самой переписке.
   */
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
          // Обычная переписка: звонки из тайной сюда не кладём
          return !String(з.roomId || '').startsWith('secure');
        });
        if (живо) {
          /* Ленте хватит недавних: за годы звонков накопятся сотни, и
             все разом в переписке ни к чему. */
          const свежие = наши.slice(-30).map((з) => ({
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
   * Выбор нескольких строк, как в телеграме.
   *
   * Начинается пунктом «Select» в меню, дальше обычное нажатие добавляет
   * и снимает. В наборе лежат ключи: у сообщения «м-номер», у звонка
   * «з-номер» — номера у них свои, и спутать их нельзя.
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
    /* Набором — по одному тем же путём; порядок не важен, а отказ по
       одной строке не должен останавливать остальные. */
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

  /** Сообщения и звонки вперемешку, по времени. */
  const лента = useMemo(() => {
    /* Пока переписка не пришла, карточки придерживаем: иначе в тайной
       сперва мелькают одни звонки, а сообщения выходят следом. */
    const всё = messages.length ? [...messages, ...звонки] : [...messages];
    всё.sort((а, б) => new Date(а.timestamp || 0) - new Date(б.timestamp || 0));
    return всё;
  }, [messages, звонки]);

  /** Короткая подпись под карточкой: «6 секунд», «1:05». */
  const длительность = (секунд) => {
    if (!секунд && секунд !== 0) return null;
    if (секунд < 60) return `${секунд} s`;
    const м = Math.floor(секунд / 60);
    const с = секунд % 60;
    return `${м}:${String(с).padStart(2, '0')}`;
  };

  const [previewData, setPreviewData] = useState({}); // ДОБАВЛЕНО: кэш превью { msgId: { title, description, imageUrl, url } }
  const [fullScreenImage, setFullScreenImage] = useState(null);

  const [openAiDialog, setOpenAiDialog] = useState(false);

  // Мемоизация аудиосообщений
  const memoizedAudioMessages = useMemo(() => {
    return messages.filter((msg) => msg.messageType === 'audio' && msg.fileUrl);
  }, [messages]);
  
  // ДОБАВЛЕНО: использование хука
  const {
    isMessageNew,
    translateExistingMessages,
    translateNextMessage,
    translateMessageFunc,
    translateSingleMessage,
    clearTranslations,
    handleTranslationSettingsSave,
    handleSpellCheckToggle,
    spellCheckMessage,
    handleTranscriptionSettingsSave, // ДОБАВЛЕНО
    transcribeSingleMessage, // ДОБАВЛЕНО
    transcribeExistingMessages, // ДОБАВЛЕНО
    transcribeNextMessage, // ДОБАВЛЕНО
  } = usePrivateChatTranslationAndSpellCheck({
    chatId,
    chatIdParam,
    messages,
    setMessages,
    isTranslationEnabled,
    setIsTranslationEnabled,
    translationLanguage,
    setTranslationLanguage,
    translationMode,
    setTranslationMode,
    translationQueue,
    setTranslationQueue,
    isTranslating,
    setIsTranslating,
    setIsSpellCheckEnabled,
    transcribedContentMap,
    setTranscribedContentMap,
    isTranscriptionEnabled,
    setIsTranscriptionEnabled, // ДОБАВЛЕНО
    transcriptionMode,
    setTranscriptionMode, // ДОБАВЛЕНО
    transcriptionQueue, // ДОБАВЛЕНО
    setTranscriptionQueue, // ДОБАВЛЕНО
    isTranscribing, // ДОБАВЛЕНО
    setIsTranscribing, // ДОБАВЛЕНО
  });

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

  useEffect(() => {
    const fetchChatData = async () => {
      console.log('fetchChatData is working...');
      if (!chatIdParam || !username) {
        toast.error('Invalid chat ID or user is not authorized');
        navigate('/');
        return;
      }

      // Сохранённый хвост переписки — на экран сразу, иначе первую секунду
      // смотреть не на что. Ответ сервера придёт следом и заменит список.
      const cached = loadCachedMessages(chatIdParam);
      if (cached.length > 0) {
        setMessages(cached);
      }

      // Расшифровки речи лежат отдельно от сообщений — достаём их тем же
      // заходом, иначе строки под плеером появляются секундой позже и
      // сдвигают ленту
      const savedSpeech = {};
      cached.forEach((msg) => {
        if (msg.messageType !== 'audio') return;
        const saved = localStorage.getItem(`transcription_${chatIdParam}_${msg.id}`);
        if (!saved) return;
        try {
          const data = JSON.parse(saved);
          savedSpeech[msg.id] = {
            content: data.content,
            translatedContent: data.translatedContent || null,
            translationLanguage: data.translationLanguage || null,
          };
        } catch (e) {
          // битая запись — покажем, когда ответит сервер
        }
      });
      if (Object.keys(savedSpeech).length) {
        setTranscribedContentMap((prev) => ({ ...savedSpeech, ...prev }));
      }


      try {
        const chatData = await getChatById(chatIdParam);
        setChatName(chatData.name);
        setChatId(chatIdParam);
        setCurrentChatId(chatIdParam);

        /*
          Собеседника спрашиваем у сервера, а не вычисляем из имени чата.
          Имя складывается из имён обоих через «_», а оно само может
          содержать «_»: тогда разбор давал несуществующее имя, запрос
          такого человека возвращал 404 и переписка не открывалась.
          Разбор имени остаётся запасным путём для прежних данных.
        */
        let interlocutorName = '';
        try {
          const partner = await findPartner(chatIdParam, localStorage.getItem('userId'));
          if (partner) {
            interlocutorName = nameOf(partner);
            setPartnerId(partner.id);
            setPartnerAvatar(partner.avatar);
          } else {
            interlocutorName = partnerNameFromChat(chatData.name, username);
            const partnerResponse = await fetch(
              `https://unlimitedpossibilities12.org/api/users/by-username/${interlocutorName}`
            );
            if (partnerResponse.ok) {
              const partnerData = await partnerResponse.json();
              setPartnerId(partnerData.id);
              setPartnerAvatar(partnerData.avatar);
            } else {
              console.warn(
                `Не удалось загрузить данные собеседника "${interlocutorName}", статус: ${partnerResponse.status}`
              );
            }
          }
        } catch (fetchError) {
          console.error('Ошибка при запросе данных собеседника:', fetchError);
          interlocutorName = interlocutorName || partnerNameFromChat(chatData.name, username);
        }
        setDisplayChatName(`Chat with ${interlocutorName}`);

        const wantedLanguage = localStorage.getItem(`translation_enabled_${chatIdParam}`) === 'true'
          ? (localStorage.getItem(`translation_language_${chatIdParam}`) || null)
          : null;
        const initialMessages = await getMessages(chatIdParam, {}, wantedLanguage, true);
        const updatedMessages = initialMessages.map((msg) => {
          const translationKey = `translation_${chatIdParam}_${msg.id}`;
          const transcriptionKey = `transcription_${chatIdParam}_${msg.id}`;
          const savedTranslation = localStorage.getItem(translationKey);
          const savedTranscription = localStorage.getItem(transcriptionKey);
          const result = {
            ...msg,
            delivered: msg.delivered_status,
            read: msg.read_status,
            showOriginal: msg.messageType === 'audio' && savedTranscription && JSON.parse(savedTranscription).translatedContent ? false : true, // Оригинал для текстовых с переводом
          };
          // Перевод с сервера на наш язык — он общий для всех устройств
          // и участников; местная копия только про запас
          const serverHasOurs = !!msg.translatedContent && !!wantedLanguage
            && (msg.translationLanguage || '').toLowerCase() === wantedLanguage.toLowerCase();
          if (serverHasOurs) {
            result.translatedContent = msg.translatedContent;
            result.translationLanguage = msg.translationLanguage;
          } else if (savedTranslation) {
            const { translatedContent, translationLanguage } = JSON.parse(savedTranslation);
            result.translatedContent = translatedContent;
            result.translationLanguage = translationLanguage;
            // Старый перевод лежал только в браузере — отдаём серверу,
            // чтобы он стал общим для всех устройств и участников
            if (translatedContent && translationLanguage) {
              saveTranslation(msg.id, translatedContent, translationLanguage).catch(() => {});
            }
          }
          if (savedTranscription && msg.messageType === 'audio') {
            const transcriptionData = JSON.parse(savedTranscription);
            setTranscribedContentMap((prev) => ({
              ...prev,
              [msg.id]: {
                content: transcriptionData.content,
                translatedContent: transcriptionData.translatedContent || null,
                translationLanguage: transcriptionData.translationLanguage || null,
              },
            }));
          }
          return result;
        });

        setMessages(updatedMessages);
        cacheMessages(chatIdParam, updatedMessages);
        if (isTranslationEnabled && translationMode === 'all') {
          translateExistingMessages();
        }
        if (isTranscriptionEnabled && transcriptionMode === 'all') {
          transcribeExistingMessages(updatedMessages);
        }

        // Загрузка настройки орфографии
        setIsSpellCheckEnabled(localStorage.getItem(`spellcheck_enabled_${chatIdParam}`) === 'true');

        // ДОБАВЛЕНО: Загрузка настройки озвучивания
        setIsTTSEnabled(localStorage.getItem(`tts_enabled_${chatIdParam}`) === 'true');

        // ДОБАВЛЕНО: Загрузка настройки транскрипции
        setIsTranscriptionEnabled(localStorage.getItem(`transcription_enabled_${chatIdParam}`) === 'true');
        setTranscriptionMode(localStorage.getItem(`transcription_mode_${chatIdParam}`) || 'selective');

        setTimeout(() => {
          scrollToBottom(false);
          didInitialScroll.current = true;
        }, 0); // Прокрутка после загрузки
      } catch (error) {
        console.error('Ошибка при загрузке данных о чате:', error);
        toast.error('Failed to load the conversation');
        navigate('/');
      }
    };

    fetchChatData();

    const storedUserId = localStorage.getItem('userId');
    if (storedUserId) {
      const userIdNum = parseInt(storedUserId, 10);
      setCurrentUserId(userIdNum);
      setContextUserId(userIdNum);
      setNotificationUserId(userIdNum);
      // Добавлено: загрузка настроек перевода
      setIsTranslationEnabled(localStorage.getItem(`translation_enabled_${chatIdParam}`) === 'true');
      setTranslationLanguage(localStorage.getItem(`translation_language_${chatIdParam}`) || 'auto');
      setTranslationMode(localStorage.getItem(`translation_mode_${chatIdParam}`) || 'selective');
      // Загрузка настройки орфографии
      //setIsSpellCheckEnabled(localStorage.getItem(`spellcheck_enabled_${chatIdParam}`) === 'true');
    }

    return () => {
      setCurrentChatId(null);
    };
  }, [chatIdParam, username, setContextUserId, setNotificationUserId, setCurrentChatId, navigate]);

  useEffect(() => {
    if (!isTranslationEnabled && messages.some((msg) => msg.translatedContent || msg.translationLanguage)) {
      console.log('Cleaning translations in messages due to disabled translation');
      clearTranslations(); // ИЗМЕНЕНО: Вызываем clearTranslations
    }
  }, [isTranslationEnabled, messages, clearTranslations]);

  /*
    Настройки перевода и расшифровки держим в ссылке, а не в зависимостях
    подписки. Прежде соединение пересобиралось ещё дважды сразу после входа в
    чат — настройки читаются из памяти браузера уже после первого рисования.
    Каждое лишнее подключение занимает соединение к серверу, а их у браузера
    на один сайт всего шесть.
  */
  /** Короткая обёртка: превью ссылок живут в состоянии компонента. */
  const inlineTime = (msg) => canFitTimeInline(msg, previewData);

  const liveRef = useRef({});
  liveRef.current = {
    isTranslationEnabled,
    translationMode,
    isTranscriptionEnabled,
    transcriptionMode,
    isMessageNew,
    userId,
  };

  useEffect(() => {
    if (!chatId) return;

    const stompClientInstance = new Client({
      connectHeaders: stompAuthHeaders(),
      webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
      connectTimeout: 30000,
      reconnectDelay: 5000,
      onConnect: () => {
        console.log('Connected to WebSocket for PrivateChat');
        stompClientInstance.subscribe(`/topic/chat/${chatId}/messages`, (message) => {
          const newMessage = JSON.parse(message.body);
          console.log('NewMessage:', newMessage);
          updateMessages(newMessage);
          // Перевод нового сообщения
          if (liveRef.current.isTranslationEnabled && liveRef.current.translationMode === 'new' && liveRef.current.isMessageNew(newMessage) && newMessage.messageType !== 'audio') {
            setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
          }
          // Транскрипция нового аудиосообщения
          if (
            liveRef.current.isTranscriptionEnabled &&
            (liveRef.current.transcriptionMode === 'all' || liveRef.current.transcriptionMode === 'new') &&
            newMessage.messageType === 'audio' &&
            liveRef.current.isMessageNew(newMessage)
          ) {
            setTranscriptionQueue((prevQueue) => [...prevQueue, newMessage]);
          }
        });


        /* Убранный звонок: сервер говорит каждой стороне о её записи, и
           карточка уходит из переписки сразу, без перезахода. */
        stompClientInstance.subscribe(`/topic/calls/${userId}`, (весть) => {
          try {
            const данные = JSON.parse(весть.body);
            const номер = данные && данные.removedCallId;
            if (!номер) return;
            setЗвонки((прежние) => прежние.filter((к) => к.звонок.id !== номер));
          } catch (ошибка) {
            console.warn('Весть об убранном звонке не разобрана:', ошибка);
          }
        });

        // ← НОВОЕ: Подписка на отдельную тему typing (добавляем сразу после messages)
        stompClientInstance.subscribe(`/topic/chat/${chatId}/typing`, (message) => {
          try {
            const data = JSON.parse(message.body);
            console.log('Typing event received:', data); // ← для отладки

            const type = data.messageType;
            const sender = data.sender;

            if (sender && sender.id !== liveRef.current.userId) { // не показываем себе
              if (type === 'TYPING') {
                setTypingName(nameOf(sender) || 'Кто-то');
                setIsTyping(true);

                // Сбрасываем старый таймер и запускаем новый (6.5 сек)
                if (typingTimerRef.current) {
                  clearTimeout(typingTimerRef.current);
                }
                typingTimerRef.current = setTimeout(() => {
                  setIsTyping(false);
                  console.log('Typing indicator hidden by timeout');
                }, 2500);
              } else if (type === 'STOP_TYPING') {
                setIsTyping(false);
                if (typingTimerRef.current) {
                  clearTimeout(typingTimerRef.current);
                }
                console.log('STOP_TYPING received');
              }
            }
          } catch (err) {
            console.error('Error parsing typing event:', err);
          }
        });
        // ← КОНЕЦ НОВОГО блока
      },
      onWebSocketError: (error) => {
        console.error('WebSocket error in PrivateChat:', error);
      },
      onStompError: (frame) => {
        console.error('STOMP error in PrivateChat:', frame);
        toast.error('Connection error');
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected in PrivateChat');
      },
    });

    setClient(stompClientInstance);
    stompClientInstance.activate();

    return () => {
      console.log('Deactivating WebSocket in PrivateChat');
      stompClientInstance.deactivate();
    };
  }, [chatId]);

  useEffect(() => {
    const interval = setInterval(() => {
      if (client && client.connected && messages.length > 0) {
        const unreadMessages = messages.some((msg) => !isReadFlag(msg));
        if (unreadMessages) {
          markAllMessagesAsRead(messages);
          clearInterval(interval);
        }
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [messages, client]);

  useEffect(() => {
    const handleClick = () => {
      handleInteraction();
    };

    document.addEventListener('click', handleClick);
    return () => {
      document.removeEventListener('click', handleClick);
    };
  }, [handleInteraction]);

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
      }, 0); // setTimeout для выполнения после рендера
    }
  }, [messages.length]);

  // ИЗМЕНЕНО: Обновляем запуск перевода
  useEffect(() => {
    if (isTranslationEnabled && translationMode === 'all') {
      const needsTranslation = messages.some(
        (msg) =>
          msg.messageType !== 'audio' &&
          msg.content !== 'Message deleted' &&
          (!msg.translatedContent || msg.translationLanguage !== translationLanguage)
      );
      if (needsTranslation) {
        console.log('Calling translateExistingMessages due to messages needing translation');
        translateExistingMessages();
      } else {
        console.log('No translation needed: all messages are translated or translation disabled');
      }
    } else if (!isTranslationEnabled) {
      console.log('Translation disabled, clearing translations');
      clearTranslations();
    }
  }, [isTranslationEnabled, translationLanguage, translationMode, translateExistingMessages, clearTranslations]);

  // Добавлено: обработка очереди переводов
  useEffect(() => {
    translateNextMessage();
  }, [translationQueue, isTranslating]);

  useEffect(() => {
    transcribeNextMessage();
  }, [transcriptionQueue, isTranscribing]);

  // ДОБАВЛЕНО: Пересчет символов на строку при изменении размера окна
  useEffect(() => {
    calculateCharPerLine();
    window.addEventListener('resize', calculateCharPerLine);
    return () => window.removeEventListener('resize', calculateCharPerLine);
  }, []);

  // ДОБАВЛЕНО: useEffect для добавления/удаления слушателей событий перетаскивания
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

  // ДОБАВЛЕНО: Предзагрузка аудио для всех сообщений
  useEffect(() => {
    const visibleAudioMessages = memoizedAudioMessages.slice(-10); // Ограничиваем последними 10 аудиосообщениями
    visibleAudioMessages.forEach((msg) => {
      if (
        audioRefs.current[msg.id] &&
        !audioProgress[msg.id]?.isReady &&
        !audioProgress[msg.id]?.isPreloading
      ) {
        const audio = audioRefs.current[msg.id];
        if (audio.src === msg.fileUrl && audio.readyState >= 2) {
          setAudioProgress((prev) => ({
            ...prev,
            [msg.id]: {
              ...prev[msg.id] || {},
              isPreloading: false,
              isReady: true,
              error: audio.duration === Infinity ? 'Duration unavailable: file issue' : null,
            },
          }));
          return;
        }
        setAudioProgress((prev) => ({
          ...prev,
          [msg.id]: {
            ...prev[msg.id] || {},
            isPreloading: true,
          },
        }));
        preloadAudio(msg.id, msg.fileUrl);
      }
    });
  }, [memoizedAudioMessages]);

  useEffect(() => {
    const handleClickOutside = (event) => {
        if (contextMenuRef.current && !contextMenuRef.current.contains(event.target)) {
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

  const markAllMessagesAsRead = (updatedMessages) => {
    console.log('markAllMessagesAsRead is working...');
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
    } else {
      console.error('updatedMessages is undefined or not an array');
    }
  };

  const updateMessages = (newMessage) => {
    console.log('Updating message:', newMessage);
    setMessages((prevMessages) => {
      if (newMessage.content === 'Message deleted') {
        console.log('Deleting message:', { id: newMessage.id, content: newMessage.content });
        return prevMessages.filter((msg) => msg.id !== newMessage.id);
      }

      const isAudio = newMessage.messageType === 'audio';
      const existingMessageIndex = prevMessages.findIndex((msg) => msg.id === newMessage.id);
      if (existingMessageIndex !== -1) {
        const existingMessage = prevMessages[existingMessageIndex];
        const needsTranslation =
          isTranslationEnabled &&
          (translationMode === 'new' || translationMode === 'all') &&
          !isAudio &&
          isMessageNew(newMessage, 'translation') && // Указываем контекст
          (!existingMessage.translatedContent || existingMessage.translationLanguage !== translationLanguage) &&
          !translationQueue.some((msg) => msg.id === newMessage.id);
        const needsTranscription =
          isTranscriptionEnabled &&
          (transcriptionMode === 'new' || transcriptionMode === 'all') &&
          isAudio &&
          isMessageNew(newMessage, 'transcription') && // Указываем контекст
          !transcribedContentMap[newMessage.id]?.content &&
          !transcriptionQueue.some((msg) => msg.id === newMessage.id);

        const updatedMessages = [...prevMessages];
        updatedMessages[existingMessageIndex] = {
          ...existingMessage,
          ...newMessage,
          delivered: newMessage.delivered_status ?? existingMessage.delivered,
          read: newMessage.read_status ?? existingMessage.read,
          messageType: newMessage.messageType ?? existingMessage.messageType,
          translatedContent: isAudio ? null : existingMessage.translatedContent,
          translationLanguage: isAudio ? null : existingMessage.translationLanguage,
          showOriginal: isAudio && transcribedContentMap[newMessage.id]?.translatedContent ? false : existingMessage.showOriginal,
        };
        console.log('Updated existing message:', updatedMessages[existingMessageIndex]);
        if (needsTranslation) {
          console.log('Adding updated message to translation queue:', { id: newMessage.id, content: newMessage.content });
          setTranslationQueue((prevQueue) => {
            if (prevQueue.some((msg) => msg.id === newMessage.id)) {
              console.log('Message already in translation queue:', { id: newMessage.id, content: newMessage.content });
              return prevQueue;
            }
            return [...prevQueue, updatedMessages[existingMessageIndex]];
          });
        }
        if (needsTranscription) {
          console.log('Adding updated message to transcription queue:', { id: newMessage.id, fileUrl: newMessage.fileUrl });
          setTranscriptionQueue((prevQueue) => {
            if (prevQueue.some((msg) => msg.id === newMessage.id)) {
              console.log('Message already in transcription queue:', { id: newMessage.id, fileUrl: newMessage.fileUrl });
              return prevQueue;
            }
            return [...prevQueue, updatedMessages[existingMessageIndex]];
          });
        }
        return updatedMessages;
      } else {
        const newMsg = {
          ...newMessage,
          delivered: newMessage.delivered_status,
          read: newMessage.read_status,
          showOriginal: !isAudio && newMessage.translatedContent ? false : false,
          messageType: newMessage.messageType,
          translatedContent: isAudio ? null : newMessage.translatedContent,
          translationLanguage: isAudio ? null : newMessage.translationLanguage,
        };
        console.log('New message added:', newMsg);
        const alreadyTranslatedOrInProgress =
          prevMessages.some(
            (msg) =>
              msg.id === newMsg.id &&
              msg.translatedContent &&
              msg.translationLanguage === translationLanguage
          ) || translationQueue.some((msg) => msg.id === newMsg.id);
        const alreadyTranscribedOrInProgress =
          transcribedContentMap[newMsg.id]?.content || transcriptionQueue.some((msg) => msg.id === newMsg.id);
        if (
          isTranslationEnabled &&
          (translationMode === 'new' || translationMode === 'all') &&
          !isAudio &&
          isMessageNew(newMsg, 'translation') && // Указываем контекст
          !alreadyTranslatedOrInProgress
        ) {
          console.log('Adding new message to translation queue:', { id: newMsg.id, content: newMsg.content });
          setTranslationQueue((prevQueue) => {
            if (prevQueue.some((msg) => msg.id === newMsg.id)) {
              console.log('Message already in translation queue:', { id: newMsg.id, content: newMsg.content });
              return prevQueue;
            }
            return [...prevQueue, newMsg];
          });
        }
        if (
          isTranscriptionEnabled &&
          (transcriptionMode === 'new' || transcriptionMode === 'all') &&
          isAudio &&
          isMessageNew(newMsg, 'transcription') && // Указываем контекст
          !alreadyTranscribedOrInProgress
        ) {
          console.log('Adding new message to transcription queue:', { id: newMsg.id, fileUrl: newMsg.fileUrl });
          setTranscriptionQueue((prevQueue) => {
            if (prevQueue.some((msg) => msg.id === newMsg.id)) {
              console.log('Message already in transcription queue:', { id: newMsg.id, fileUrl: newMsg.fileUrl });
              return prevQueue;
            }
            return [...prevQueue, newMsg];
          });
        }
        return [...prevMessages, newMsg];
      }
    });
  };

  const uploadFile = async (file) => {
    const formData = new FormData();
    formData.append('file', file);

    const response = await fetch('https://unlimitedpossibilities12.org/api/files/upload', {
      method: 'POST',
      body: formData,
    });

    if (!response.ok) {
      throw new Error('File upload failed');
    }

    const data = await response.json();
    return data.fileUrl;
  };

  const sendMessage = async () => {
    своёОтправлено.current = true;   // своё сообщение уводит в конец
    if ((content || selectedFile || audioBlob) && userId) {
        let finalContent = content;
        if (isSpellCheckEnabled) {
            const spellCheckTimestamp = localStorage.getItem(`spellcheck_enabled_timestamp_${chatIdParam}`);
            if (spellCheckTimestamp) {
                finalContent = await spellCheckMessage(content);
            }
        }

        const message = {
            id: editingMessageId || Date.now(),
            content: finalContent,
            sender: { id: userId, username },
            chat: { id: chatId, name: chatName },
            fileUrl: selectedFile ? await uploadFile(selectedFile) : null,
            delivered: false,
            read: false,
            timestamp: new Date().toISOString(),
            messageType: selectedFile ? 'file' : 'text',
            contentType: selectedFile ? selectedFile.type : null
        };

        if (client && client.connected) {
            client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
            sendNotification(message, finalContent);
            resetForm();
        } else {
            console.error('STOMP client is not connected');
            toast.error('Failed to send message: no connection');
        }
    } else {
        toast.warn('Enter a message or select a file');
    }
  };

  // Новая функция для отправки уведомлений
  const sendNotification = async (message, contentForNotification) => {
    setTimeout(async () => {
        const response = await fetch(
            `https://unlimitedpossibilities12.org/api/messages/search?content=${encodeURIComponent(contentForNotification)}&senderId=${userId}&chatId=${chatId}`
        );

        if (response.ok) {
            const updatedMessage = await response.json();
            console.log('updatedMessage:', updatedMessage);

            const messageId = updatedMessage.id;
            const statusResponse = await fetch(
                `https://unlimitedpossibilities12.org/api/messages/${messageId}`
            );

            if (statusResponse.ok) {
                const messageStatus = await statusResponse.json();
                // Собеседник уже известен из участников чата. Разбор имени
                // («я_он») оставлен запасным: имя человека может содержать
                // «_», и тогда уведомление уходило в никуда.
                let recipientId = partnerId;
                if (!recipientId) {
                    const recipientName = partnerNameFromChat(chatName, username);
                    const userResponse = await fetch(
                        `https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`
                    );
                    if (userResponse.ok) recipientId = (await userResponse.json()).id;
                }

                if (recipientId) {
                    console.log('recipientId:', recipientId);
                    console.log('messageStatus.read_status:', messageStatus.read_status);

                    if (messageStatus.read_status === false || messageStatus.read_status === null) {
                        console.log('Notification must be sent...');
                        await fetch(`https://unlimitedpossibilities12.org/api/messages/notify`, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({
                                userId,
                                recipientId,
                                content: contentForNotification,
                                chatId,
                            }),
                        });
                    }
                } else {
                    console.error('Error getting recipient ID');
                }
            } else {
                console.error('Error getting message status');
            }
        } else {
            console.error('Error while searching for message:', response.status, response.statusText);
        }
    }, 3000);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey && (content.trim() || selectedFile)) { // ИЗМЕНЕНО: Добавлено условие content.trim() || selectedFile
        e.preventDefault();
        sendMessage();
    }
  };

  const resetForm = () => {
    setContent('');
    setSelectedFile(null);
    setEditingMessageId(null);
    setRows(1);
    if (fileInputRef.current) {
        fileInputRef.current.value = null;
    }
    if (textAreaRef.current) {
        textAreaRef.current.style.height = '44px';
        // ДОБАВЛЕНО: Удаляем класс scroll-enabled при сбросе
        textAreaRef.current.classList.remove('scroll-enabled');
    }

    // ← Отправка события при отправке сообщения, о прекращении печатания
    sendTypingEvent(false);
  };

  const handleRightClick = (e, message) => {
    e.preventDefault();

    const messageElement = e.currentTarget; // .private-message
    const rect = messageElement.getBoundingClientRect();

    const { x, y } = местоМеню(e);

    setContextMenu({
        visible: true,
        x,
        y,
        message,
    });
  };

  /** Своё ли это сообщение: чужое можно убрать, но не править. */
  /** У карточки звонка нет ни текста, ни отправителя — править нечего. */
  const моёСообщение = (м) => !!(м && м.sender && !м.звонок) && м.sender.id === userId;

  const handleMenuAction = (action) => {
    if (action === 'edit' && !моёСообщение(contextMenu.message)) {
      toast.error('You can only edit your own messages.');
      return;
    }

    if (action === 'edit') {
      setContent(contextMenu.message.content);
      setEditingMessageId(contextMenu.message.id);
    } else if (action === 'select') {
      setВыбранные(new Set([ключСтроки(contextMenu.message)]));
    } else if (action === 'delete') {
      // Спрашиваем, как в телеграме: у себя или и у собеседника тоже
      setУбратьУОбоих(false);
      const строка = contextMenu.message;
      setУбрать(строка && строка.звонок
          ? { вид: 'звонок', id: строка.звонок.id }
          : { вид: 'сообщение', id: строка.id });
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


  /**
   * Звонок собеседнику прямо из переписки. Комната строится из пары номеров.
   *
   * Видов три: голос, видео и голос с переводом. Последний раньше запускался
   * только со страницы человека, хотя звонят из переписки.
   */
  const CALL_ROUTES = { voice: 'voice-call', video: 'video-call', translate: 'voice-call-translate' };

  const startCall = async (type) => {
    if (!partnerId) {
      toast.error('Cannot find who to call');
      return;
    }
    const kind = CALL_ROUTES[type] ? type : 'voice';
    const roomId = `room-${userId}-${partnerId}`;
    const callRequest = {
      callerId: userId,
      recipientId: Number(partnerId),
      roomId,
      callerName: localStorage.getItem('username') || 'Unknown',
      type: kind,
    };
    try {
      const response = await fetch('https://unlimitedpossibilities12.org/api/webrtc/call', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(callRequest),
      });
      if (!response.ok) {
        toast.error('Failed to start the call');
        return;
      }
      const partnerName = displayChatName.replace('Chat with ', '');
      toast.success(kind === 'translate'
        ? `Calling ${partnerName} with translation...`
        : `Calling ${partnerName}...`);
      navigate(`/${CALL_ROUTES[kind]}/${roomId}`, {
        state: {
          incomingCall: callRequest,
          isCaller: true,
          currentUserId: userId,
          callerName: localStorage.getItem('username') || 'Unknown',
          recipientName: partnerName,
        },
      });
    } catch (error) {
      console.error('Call error:', error);
      toast.error('Failed to start the call');
    }
  };

  const startVoiceCall = () => startCall('voice');
  const startVideoCall = () => startCall('video');
  const startTranslateCall = () => startCall('translate');

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
      // В пузыре нужно только время: день обозначает разделитель в ленте,
      // а дата в каждом сообщении раньше дублировала его и растила пузырь
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

  // ИЗМЕНЕНО: Метод для начала записи аудио с конвертацией в .mp3
  const startRecording = async () => {
    try {
        // ДОБАВЛЕНО: Загружаем FFmpeg перед началом записи
        await loadFFmpeg();

        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        let mimeType = 'audio/webm;codecs=opus';
        if (!MediaRecorder.isTypeSupported(mimeType)) {
            console.warn('audio/webm;codecs=opus not supported, falling back to audio/webm');
            mimeType = 'audio/webm';
            if (!MediaRecorder.isTypeSupported(mimeType)) {
                console.error('audio/webm not supported');
                toast.error('Audio recording not supported in this browser');
                return;
            }
        }
        const mediaRecorder = new MediaRecorder(stream, { 
            mimeType,
            audioBitsPerSecond: 128000 // Устанавливаем битрейт
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
            console.log(`Recorded audio: size=${webmBlob.size}, type=${webmBlob.type}`);

            // ДОБАВЛЕНО: Конвертация в .mp3
            try {
                const inputName = 'input.webm';
                const outputName = 'output.mp3';
                // ИЗМЕНЕНО: Преобразуем Blob в ArrayBuffer
                const arrayBuffer = await webmBlob.arrayBuffer();
                await ffmpeg.current.writeFile(inputName, new Uint8Array(arrayBuffer));
                await ffmpeg.current.exec(['-i', inputName, '-c:a', 'libmp3lame', '-b:a', '128k', outputName]);
                const mp3Data = await ffmpeg.current.readFile(outputName);
                const mp3Blob = new Blob([mp3Data.buffer], { type: 'audio/mpeg' });
                console.log(`Converted audio: size=${mp3Blob.size}, type=${mp3Blob.type}`);
                setAudioBlob(mp3Blob);
                stream.getTracks().forEach(track => track.stop());
                setMicIconState('sending');
                sendAudioMessage(mp3Blob);
            } catch (error) {
                console.error('Error converting to mp3:', error);
                toast.error('Failed to convert audio');
                stream.getTracks().forEach(track => track.stop());
                setMicIconState('mic');
            }
        };

        setMicIconState('recording');
        mediaRecorder.start(1000); // Разбиваем на 1-секундные чанки
        setIsRecording(true);
        toast.info('Recording started');
    } catch (error) {
        console.error('Error starting recording:', error);
        toast.error('Failed to start recording');
        setMicIconState('mic');
    }
  };

  // ДОБАВЛЕНО: Метод для остановки записи аудио
  const stopRecording = () => {
    if (mediaRecorderRef.current && isRecording) {
      setMicIconState('sending');
      mediaRecorderRef.current.stop();
      setIsRecording(false);
      toast.info('Recording stopped');
    }
  };

  // ДОБАВЛЕНО: Загрузка FFmpeg
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

  // ИЗМЕНЕНО: Метод для отправки аудиосообщения
  const sendAudioMessage = async (audioBlob) => {
    if (!audioBlob || !userId) {
        console.warn('Audio or userId is missing');
        toast.error('Failed to send audio message');
        setMicIconState('mic');
        return;
    }

    const audioFile = new File([audioBlob], `audio_${Date.now()}.mp3`, { type: 'audio/mpeg' });

    const message = {
        id: Date.now(),
        content: '',
        sender: { id: userId, username },
        chat: { id: chatId, name: chatName },
        fileUrl: await uploadFile(audioFile),
        delivered: false,
        read: false,
        timestamp: new Date().toISOString(),
        messageType: 'audio',
        contentType: 'audio/mpeg'
    };

    console.log('Sending audio message:', message);

    if (client && client.connected) {
        client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
        sendNotification(message, ''); // Пустой content для аудиосообщений
        setAudioBlob(null);
        console.log('Audio message sent');
        setMicIconState('mic');
    } else {
        console.error('STOMP client is not connected');
        toast.error('WebSocket not connected');
        setMicIconState('mic');
    }
  };

  // ДОБАВЛЕНО: Вычисление количества символов на строку
  const calculateCharPerLine = () => {
    if (textAreaRef.current) {
      const canvas = document.createElement('canvas');
      const context = canvas.getContext('2d');
      context.font = window.innerWidth <= 800 ? '14px Roboto' : '16px Roboto';
      const avgCharWidth = context.measureText('m').width; // Средняя ширина символа
      const textareaWidth = textAreaRef.current.clientWidth - (window.innerWidth <= 800 ? 36 : 40); // Учитываем padding
      const chars = Math.floor(textareaWidth / avgCharWidth);
      setCharPerLine(chars);
    }
  };

  // ДОБАВЛЕНО: Вычисление количества строк
  const calculateRows = (text) => {
    if (!text) return 1; // Если текст пустой, возвращаем 1 строку
    const lines = text.split('\n'); // Разбиваем текст на строки по переносам
    let totalRows = lines.length; // Количество строк по переносам
    const canvas = document.createElement('canvas');
    const context = canvas.getContext('2d');
    context.font = window.innerWidth <= 800 ? '14px Roboto' : '16px Roboto';
    lines.forEach((line) => {
      if (line.length > 0) {
        const textWidth = context.measureText(line).width;
        const textareaWidth = textAreaRef.current.clientWidth - (window.innerWidth <= 800 ? 36 : 40); // Учитываем padding
        if (textWidth > textareaWidth) {
          totalRows += Math.floor(textWidth / textareaWidth); // Дополнительные строки при превышении ширины
        }
      }
    });
    return Math.max(1, totalRows); // Минимально 1 строка
  };

  // ДОБАВЛЕНО: Обработчик изменения текста
  const handleInputChange = (e) => {
    const newValue = e.target.value;
    setContent(newValue);
    const newRows = Math.min(5, calculateRows(newValue));
    setRows(newRows);
    const lineHeight = window.innerWidth <= 800 ? 20 : 24;
    textAreaRef.current.style.height = `${newRows * lineHeight + 20}px`;
    // ДОБАВЛЕНО: Управление классом scroll-enabled для скроллбара
    if (newRows >= 5) {
        textAreaRef.current.classList.add('scroll-enabled');
    } else {
        textAreaRef.current.classList.remove('scroll-enabled');
    }
    // Отправляем typing, если текст не пустой
    if (newValue.trim() !== '') {
      sendTypingEvent(true);
    }
  };

  // Форматирование времени в формате MM:SS
  const formatAudioTime = (seconds) => {
    if (isNaN(seconds) || seconds === 0 || seconds === Infinity) return '0:00';
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.floor(seconds % 60);
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  };

  // Обработчик изменения времени воспроизведения
  const handleTimeUpdate = (msgId, audioElement) => {
    setAudioProgress((prev) => {
      const prevData = prev[msgId] || {};
      // Проверяем, изменились ли ключевые поля
      if (
        prevData.currentTime === audioElement.currentTime &&
        prevData.isPlaying === !audioElement.paused &&
        prevData.duration === audioElement.duration
      ) {
        return prev; // Ничего не изменилось, возвращаем старое состояние
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

  // Обработчик загрузки метаданных
  const handleLoadedMetadata = (msgId, audioElement) => {
    console.log(`Metadata loaded for msg ${msgId}: duration=${audioElement.duration}, readyState=${audioElement.readyState}`);
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

  // Обработчик события canplay
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

  // Обработчик клика по дорожке
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

  // Обработчик ошибок аудио
  const handleAudioError = (msgId, error) => {
    const audio = audioRefs.current[msgId];
    const errorMsg = error.target.error ? `Error code: ${error.target.error.code}, message: ${error.target.error.message}` : 'Unknown error';
    console.error(`Audio error for msg ${msgId}: ${errorMsg}, URL: ${audio?.src}, readyState: ${audio?.readyState}`);
    setAudioProgress((prev) => {
        const prevData = prev[msgId] || {};
        if (prevData.isReady && (prevData.currentTime > 0 || audio?.readyState >= 2)) {
            return prev;
        }
        if (!prevData.retryCount || prevData.retryCount < 2) {
            setTimeout(() => {
                console.log(`Retrying audio load for msg ${msgId}`);
                audio.src = audio.src.split('?')[0] + `?t=${Date.now()}`; // Обход кэша
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

  // Обработчик окончания воспроизведения
  const handleAudioEnded = (msgId) => {
    console.log(`Audio ended for msg ${msgId}`);
    const audio = audioRefs.current[msgId];
    setAudioProgress((prev) => ({
      ...prev,
      [msgId]: {
        ...prev[msgId],
        currentTime: 0,
        isPlaying: false,
        isReady: audio?.readyState >= 2, // Сохраняем isReady
        error: null, // Очищаем ошибку
      },
    }));
    if (audio) {
      audio.currentTime = 0;
      audio.load(); // Перезагружаем данные
      console.log(`Audio reloaded for msg ${msgId} to maintain playability`);
    }
  };

  // ИЗМЕНЕНО: Предзагрузка аудио
  const preloadAudio = (msgId, url) => {
    const audio = audioRefs.current[msgId];
    if (audio && !audioProgress[msgId]?.isReady && !audioProgress[msgId]?.isPreloading) {
        setAudioProgress((prev) => ({
            ...prev,
            [msgId]: { ...prev[msgId], isPreloading: true },
        }));
        if (audio.src === url && audio.readyState >= 2) {
            setAudioProgress((prev) => ({
                ...prev,
                [msgId]: {
                    ...prev[msgId],
                    isPreloading: false,
                    isReady: audio.readyState >= 2,
                    error: audio.duration === Infinity ? 'Duration unavailable: file issue' : null,
                },
            }));
            return;
        }
        const cacheBusterUrl = `${url}?t=${Date.now()}`; // Обход кэша
        audio.src = cacheBusterUrl;
        // ДОБАВЛЕНО: Устанавливаем тип в зависимости от расширения файла
        audio.type = url.endsWith('.mp3') ? 'audio/mpeg' : 'audio/webm';
        audio.load();
        audio.addEventListener('loadedmetadata', () => {
            console.log(`Loaded metadata for msg ${msgId}: duration=${audio.duration}, readyState=${audio.readyState}`);
            setAudioProgress((prev) => ({
                ...prev,
                [msgId]: {
                    ...prev[msgId],
                    isPreloading: false,
                    isReady: audio.readyState >= 2,
                    error: audio.duration === Infinity ? 'Duration unavailable: file issue' : null,
                },
            }));
        }, { once: true });
        audio.addEventListener('canplay', () => {
            console.log(`Can play msg ${msgId}: readyState=${audio.readyState}`);
            setAudioProgress((prev) => ({
                ...prev,
                [msgId]: {
                    ...prev[msgId],
                    isPreloading: false,
                    isReady: true,
                },
            }));
        }, { once: true });
        audio.addEventListener('error', (e) => {
            const errorMsg = e.target.error ? `Error code: ${e.target.error.code}, message: ${e.target.error.message}` : 'Unknown error';
            console.error(`Preload error for msg ${msgId}: ${errorMsg}, URL: ${url}, readyState: ${audio.readyState}`);
            setAudioProgress((prev) => {
                const prevData = prev[msgId] || {};
                if (!prevData.retryCount || prevData.retryCount < 2) {
                    setTimeout(() => {
                        console.log(`Retrying preload for msg ${msgId}, attempt ${prevData.retryCount + 1}`);
                        audio.src = `${url}?t=${Date.now()}`;
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
                        error: `Failed to preload audio: ${errorMsg}`,
                    },
                };
            });
        }, { once: true });
    }
  };

  // ДОБАВЛЕНО: Обработчик начала перетаскивания
  const handleDragStart = (msgId, e) => {
      e.preventDefault();
      const audio = audioRefs.current[msgId];
      if (!audio || !audioProgress[msgId]?.isReady) {
          console.log(`Drag blocked for msg ${msgId}: audio not ready`);
          return;
      }
      setIsDragging(msgId);
      handleSeek(msgId, e); // Выполняем начальную перемотку
  };

  // ДОБАВЛЕНО: Обработчик движения мыши при перетаскивании
  const handleDragMove = (e) => {
      if (isDragging) {
          handleSeek(isDragging, e);
      }
  };

  // ДОБАВЛЕНО: Обработчик окончания перетаскивания
  const handleDragEnd = () => {
      setIsDragging(null);
  };

  // ДОБАВЛЕНО: Обработчик переключения озвучивания
  const handleTTSToggle = () => {
    const newTTSEnabled = !isTTSEnabled;
    setIsTTSEnabled(newTTSEnabled);
    localStorage.setItem(`tts_enabled_${chatIdParam}`, newTTSEnabled);
  };

  const handleTTSOriginal = async (msg) => {
    if (msg.messageType === 'audio' && !transcribedContentMap[msg.id]?.content) {
      toast.error('Transcription is not available for this audio message');
      return;
    }

    const contentToUse = msg.messageType === 'audio' && isTranscriptionEnabled
      ? transcribedContentMap[msg.id]?.content
      : msg.content;

    if (!contentToUse || contentToUse === 'Message deleted') {
      console.warn('No valid content for TTS:', { msgId: msg.id, content: contentToUse });
      return;
    }

    try {
      const response = await fetch('https://unlimitedpossibilities12.org/api/tts', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          text: contentToUse,
          language: 'auto', // Используем 'auto' для оригинального текста
        }),
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const json = await response.json();
      if (json.audio) {
        const audioData = atob(json.audio);
        const arrayBuffer = new ArrayBuffer(audioData.length);
        const view = new Uint8Array(arrayBuffer);
        for (let i = 0; i < audioData.length; i++) {
          view[i] = audioData.charCodeAt(i);
        }
        const blob = new Blob([arrayBuffer], { type: 'audio/mp3' });
        const url = URL.createObjectURL(blob);
        ttsAudioRef.current.src = url;
        ttsAudioRef.current.play().catch((error) => {
          console.error('Error playing TTS audio:', error);
          toast.error('Failed to play TTS audio');
        });
        ttsAudioRef.current.onended = () => {
          URL.revokeObjectURL(url);
          console.log('TTS audio playback ended for original content:', { msgId: msg.id });
        };
      } else {
        console.error('No audio data in TTS response:', json);
        toast.error('Failed to generate TTS audio');
      }
    } catch (error) {
      console.error('TTS request error for original content:', error);
      toast.error('Failed to generate TTS audio');
    }
  };

  const handleTTSTranslated = async (msg) => {
    if (msg.messageType === 'audio' && !transcribedContentMap[msg.id]?.content) {
      toast.error('Transcription is not available for this audio message');
      return;
    }

    const contentToUse = msg.messageType === 'audio' && isTranscriptionEnabled
      ? transcribedContentMap[msg.id]?.translatedContent
      : msg.translatedContent;

    const languageToUse = msg.messageType === 'audio' && isTranscriptionEnabled
      ? transcribedContentMap[msg.id]?.translationLanguage
      : msg.translationLanguage;

    if (!contentToUse) {
      console.warn('No translated content for TTS:', { msgId: msg.id, translatedContent: contentToUse });
      return;
    }

    try {
      const response = await fetch('https://unlimitedpossibilities12.org/api/tts', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          text: contentToUse,
          language: languageToUse || 'auto', // Используем язык перевода
        }),
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const json = await response.json();
      if (json.audio) {
        const audioData = atob(json.audio);
        const arrayBuffer = new ArrayBuffer(audioData.length);
        const view = new Uint8Array(arrayBuffer);
        for (let i = 0; i < audioData.length; i++) {
          view[i] = audioData.charCodeAt(i);
        }
        const blob = new Blob([arrayBuffer], { type: 'audio/mp3' });
        const url = URL.createObjectURL(blob);
        ttsAudioRef.current.src = url;
        ttsAudioRef.current.play().catch((error) => {
          console.error('Error playing TTS audio for translated content:', error);
          toast.error('Failed to play TTS audio');
        });
        ttsAudioRef.current.onended = () => {
          URL.revokeObjectURL(url);
          console.log('TTS audio playback ended for translated content:', { msgId: msg.id });
        };
      } else {
        console.error('No audio data in TTS response:', json);
        toast.error('Failed to generate TTS audio');
      }
    } catch (error) {
      console.error('TTS request error for translated content:', error);
      toast.error('Failed to generate TTS audio');
    }
  };

  useEffect(() => {
    if (isTranscriptionEnabled && transcriptionMode === 'all') {
      transcribeExistingMessages(messages);
    }
  }, [isTranscriptionEnabled, transcriptionMode, messages, transcribeExistingMessages]);

  const showTooltip = (event, tooltipText) => {
    // Удаляем существующие подсказки, если есть
    const existingTooltip = document.querySelector('.tooltip-dynamic');
    const existingArrow = document.querySelector('.tooltip-dynamic-arrow');
    if (existingTooltip) existingTooltip.remove();
    if (existingArrow) existingArrow.remove();

    // Создаем элемент подсказки
    const tooltip = document.createElement('div');
    tooltip.className = 'tooltip-dynamic';
    tooltip.textContent = tooltipText;

    // Создаем элемент стрелки
    const arrow = document.createElement('div');
    arrow.className = 'tooltip-dynamic-arrow';

    // Добавляем элементы в body
    document.body.appendChild(tooltip);
    document.body.appendChild(arrow);

    // Получаем координаты кнопки
    const button = event.currentTarget;
    const rect = button.getBoundingClientRect();
    const isMobile = window.innerWidth <= 800;

    // Позиционируем подсказку
    const tooltipHeight = isMobile ? 20 : 24; // Примерная высота (font-size + padding)
    tooltip.style.top = `${rect.top - tooltipHeight - (isMobile ? 4 : 8)}px`; // top: -28px/-30px + стрелка
    tooltip.style.left = `${rect.left + rect.width / 2}px`; // Центрируем по кнопке
    tooltip.style.transform = 'translateX(-50%)'; // Смещаем на 50% влево

    // Позиционируем стрелку
    arrow.style.top = `${rect.top - (isMobile ? 4 : 8)}px`; // top: -6px/-8px
    arrow.style.left = `${rect.left + rect.width / 2}px`;
    arrow.style.transform = 'translateX(-50%)';
  };

  const hideTooltip = () => {
    // Удаляем подсказку и стрелку
    const tooltip = document.querySelector('.tooltip-dynamic');
    const arrow = document.querySelector('.tooltip-dynamic-arrow');
    if (tooltip) tooltip.remove();
    if (arrow) arrow.remove();
  };

  const sendTypingEvent = (isTyping) => {
    if (!client || !client.connected) {
      console.warn('Cannot send typing: STOMP not connected');
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

    console.log(`Typing event sent: ${isTyping ? 'TYPING' : 'STOP_TYPING'}`);
  };

  // ДОБАВЛЕНО: Находит первую ссылку в тексте сообщения
  const extractFirstUrl = (text) => {
    if (!text) return null;
    const regex = /(https?:\/\/[^\s]+)|(www\.[^\s]+)/gi;
    const match = text.match(regex);
    if (match) {
      let url = match[0];
      if (!url.startsWith('http')) url = 'https://' + url;
      try {
        new URL(url); // Проверяем валидность
        return url;
      } catch {
        return null;
      }
    }
    return null;
  };

  const renderTextWithLinks = (text) => {
    if (!text) return text;

    const urlRegex = /(https?:\/\/[^\s]+)|(www\.[^\s]+)/gi;
    const parts = [];
    let lastIndex = 0;
    let match;

    while ((match = urlRegex.exec(text)) !== null) {
      // Текст до ссылки
      if (match.index > lastIndex) {
        parts.push(text.slice(lastIndex, match.index));
      }

      const rawUrl = match[0];
      const fullUrl = rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`;

      parts.push(
        <a
          key={match.index}
          href={fullUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="message-link"
          // НЕ используем stopPropagation — пусть браузер сам обработает переход
        >
          {shortLinkLabel(rawUrl)}
        </a>
      );

      lastIndex = urlRegex.lastIndex;
    }

    // Остаток текста
    if (lastIndex < text.length) {
      parts.push(text.slice(lastIndex));
    }

    return parts.length > 0 ? parts : text;
  };

  // Превью ссылок: известные берём из кэша, остальные спрашиваем у бэкенда
  useEffect(() => {
    let cancelled = false;

    const loadPreviews = async () => {
      const known = {};
      const missing = [];

      for (const msg of messages) {
        if (previewData[msg.id] || msg.messageType === 'audio' || msg.fileUrl || !msg.content) {
          continue;
        }

        const url = extractFirstUrl(msg.content);
        if (!url) continue;

        const cached = getCachedPreview(url);
        if (cached) known[msg.id] = cached;
        else missing.push({ id: msg.id, url });
      }

      // Что уже знаем — показываем сразу, не дожидаясь сети
      if (Object.keys(known).length > 0 && !cancelled) {
        setPreviewData((prev) => ({ ...known, ...prev }));
      }

      if (missing.length === 0) return;

      // Спрашиваем разом: одна медленная ссылка больше не держит остальные
      const loaded = await Promise.all(
        missing.map(async ({ id, url }) => {
          const data = await loadPreview(url);
          return data ? [id, data] : null;
        })
      );

      if (cancelled) return;

      const fresh = Object.fromEntries(loaded.filter(Boolean));
      if (Object.keys(fresh).length > 0) {
        setPreviewData((prev) => ({ ...prev, ...fresh }));
      }
    };

    loadPreviews();
    return () => {
      cancelled = true;
    };
  }, [messages]); // Зависимость только от messages — пересчитываем при изменении списка

  const openFullScreenImage = (fileUrl) => {
    setFullScreenImage(fileUrl);
  };

  const handleOpenAiDialog = () => {
    setOpenAiDialog(true);
  };

  const sendDirectFromAI = (text) => {
    const trimmed = text.trim();
    if (!trimmed) {
      toast.warn('Cannot send empty message');
      return;
    }

    const message = {
      id: Date.now(),
      content: trimmed,
      sender: { id: userId, username },
      chat: { id: chatId, name: chatName },
      fileUrl: null,
      delivered: false,
      read: false,
      timestamp: new Date().toISOString(),
      messageType: 'text',
      contentType: null,
    };

    if (client && client.connected) {
      client.publish({
        destination: `/app/sendMessage/chat/${chatId}`,
        body: JSON.stringify(message),
      });
      sendNotification(message, trimmed); 
      toast.success('Message sent directly');
    } else {
      toast.error('WebSocket not connected');
    }
  };

  return (
    <div className="private-chat">
      {выборИдёт && (
        /* Полоса выбора закрывает шапку, как в телеграме: слева «убрать
           столько-то», справа «отмена». */
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
      {/* Большая фиксированная иконка ассистента — только в PrivateChat на ПК */}
      <AssistantIcon size="large" className="fixed-app-bottom-right" />
      <div className="private-chat-name">
        {/* Новый блок с аватаром и именем — над всем остальным */}
        <div className="header-content">
          <div className="partner-avatar-container">
            {partnerAvatar && !brokenAvatars.partner ? (
              <img
                src={`https://unlimitedpossibilities12.org${partnerAvatar}`}
                alt="Avatar"
                className="partner-avatar"
                onClick={() => partnerId && navigate(`/user/${partnerId}`)}
                onError={() => setBrokenAvatars((broken) => ({ ...broken, partner: true }))}
              />
            ) : (
              <div className="partner-avatar placeholder">
                {displayChatName.replace(/^Chat with\s*/i, '').trim().charAt(0).toUpperCase() || '?'}
              </div>
            )}
          </div>

          <div className="partner-info">
            <div className="partner-name">{displayChatName.replace('Chat with ', '')}</div>
            {/*
              Строка состояния: кто перед тобой и что включено в этом чате.
              Метки появляются только у включённых режимов и записаны одинаково:
              стрелка там, где одно превращается в другое.
            */}
            <div className="chat-status-line">
              {isTyping ? (
                <span className="chat-status-typing">{typingName} is typing…</span>
              ) : (
                <span className="chat-status-text">
                  {partnerId && usersStatus[partnerId]?.online ? 'online' : 'offline'}
                </span>
              )}
              {isTranslationEnabled && (
                <span className="chat-mode-chip" title="Incoming messages are translated">
                  {translationLanguage && translationLanguage !== 'auto'
                    ? `→${translationLanguage.toUpperCase()}`
                    : 'TRANSLATE'}
                </span>
              )}
              {isTranscriptionEnabled && (
                <span className="chat-mode-chip" title="Voice messages are transcribed">→TEXT</span>
              )}
              {isTTSEnabled && (
                <span className="chat-mode-chip" title="Incoming messages are read aloud">→AUDIO</span>
              )}
              {isSpellCheckEnabled && (
                <span className="chat-mode-chip" title="Your text is corrected before sending">SPELL</span>
              )}
            </div>
          </div>
        </div>

        {/*
          В шапке — действия над собеседником: позвонить, видео, меню.
          Режимы (перевод, транскрипция, озвучка, орфография) переехали в меню:
          их включают редко, а места занимали постоянно.
        */}
        <div className="chat-header-actions">
          <button type="button" className="chat-header-button" title="Voice call" onClick={startVoiceCall}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
              <path d="M5 4.5h3.5l1.5 4-2 1.5a11 11 0 006 6l1.5-2 4 1.5V19a1.5 1.5 0 01-1.7 1.5C10.8 19.8 4.2 13.2 3.5 6.2A1.5 1.5 0 015 4.5z" />
            </svg>
          </button>
          <button type="button" className="chat-header-button" title="Video call" onClick={startVideoCall}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" aria-hidden="true">
              <rect x="3" y="6" width="12" height="12" rx="2.5" />
              <path d="M15 10.5l6-3v9l-6-3z" />
            </svg>
          </button>
          {/* Глобус рядом с трубкой и камерой читается как «позвонить с переводом» */}
          <button
            type="button"
            className="chat-header-button"
            title="Call with translation"
            onClick={startTranslateCall}
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              {/* Трубка как у обычного звонка, только мельче, и глобус сверху:
                  сам по себе глобус читался как «язык», а не как звонок */}
              <g transform="translate(-0.6 2.2) scale(0.82)" strokeWidth="2.4">
                <path d="M5 4.5h3.5l1.5 4-2 1.5a11 11 0 006 6l1.5-2 4 1.5V19a1.5 1.5 0 01-1.7 1.5C10.8 19.8 4.2 13.2 3.5 6.2A1.5 1.5 0 015 4.5z" />
              </g>
              <g strokeWidth="1.6">
                <circle cx="18.4" cy="6" r="3.6" />
                <path d="M14.8 6h7.2" />
                <path d="M18.4 2.4c1.1 1.1 1.7 2.4 1.7 3.6s-.6 2.5-1.7 3.6c-1.1-1.1-1.7-2.4-1.7-3.6s.6-2.5 1.7-3.6z" />
              </g>
            </svg>
          </button>
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
                <button type="button" onClick={() => { setChatMenuOpen(false); setShowTranslationModal(true); }}>
                  Translation…
                </button>
                <button type="button" onClick={() => { setChatMenuOpen(false); setShowTranscriptionModal(true); }}>
                  Transcription…
                </button>
                <button type="button" onClick={() => { setChatMenuOpen(false); handleTTSToggle(); }}>
                  <span>Read aloud</span>
                  <span className={`chat-menu-switch ${isTTSEnabled ? 'on' : ''}`} />
                </button>
                <button type="button" onClick={() => { setChatMenuOpen(false); handleSpellCheckToggle(); }}>
                  <span>Spellcheck</span>
                  <span className={`chat-menu-switch ${isSpellCheckEnabled ? 'on' : ''}`} />
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
        <AssistantIcon size="header" className="mobile-header-icon" />
      </div>
      {showTranscriptionModal && (
        <TranscriptionSettingsModal
          chatId={chatId}
          onSave={handleTranscriptionSettingsSave}
          onClose={() => setShowTranscriptionModal(false)}
        />
      )}
      {showTranslationModal && (
        <TranslationSettingsModal
          chatId={chatId}
          onSave={handleTranslationSettingsSave}
          onClose={() => setShowTranslationModal(false)}
        />
      )}
      {/* Индикатор "печатает…" */}
      {/*{isTyping && (
        <div className="typing-indicator">
          <span>{typingName} печатает…</span>
        </div>
      )}*/}

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
        className={`private-message-list${выборИдёт ? ' идёт-выбор' : ''}`}
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
            /* Карточка звонка: кто кому, чем кончился, сколько длился */
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
                      startCall(з.callType === 'video' ? 'video' : 'voice');
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
            className={`private-message ${
              msg.sender.id === userId ? 'private-current-user' : 'private-other-user'
            } ${isFirstMessage(msg, index) ? 'first-message' : ''} ${
              msg.messageType === 'audio' ? 'private-audio-message' : ''
            } ${тотЖеПодряд(лента, index, userId) ? 'подряд' : ''}`}
            onContextMenu={(e) => handleRightClick(e, msg)}
            onClickCapture={(e) => {
              /* Пока идёт выбор, нажатие нигде внутри строки не делает
                 своего дела, а добавляет её к выбранным и снимает. */
              if (!выборИдёт) return;
              e.preventDefault();
              e.stopPropagation();
              переключитьВыбор(msg);
            }}
          >
            <div className="private-message-wrapper">
              {shouldShowAvatar(msg, index) && (
                <div className="private-avatar-container">
                  {msg.sender.avatar && !brokenAvatars[msg.sender.id] ? (
                    <div className="private-avatar-wrapper">
                      <img
                        src={`https://unlimitedpossibilities12.org${msg.sender.avatar}`}
                        alt={`${msg.sender.username} avatar`}
                        className="private-message-avatar"
                        onClick={() => handleAvatarClick(msg.sender.id)}
                        style={{ cursor: 'pointer' }}
                        onError={() =>
                          setBrokenAvatars((broken) => ({ ...broken, [msg.sender.id]: true }))
                        }
                      />
                      {usersStatus[msg.sender.id]?.online && (
                        <span className="status-indicator-online-privatechat"></span>
                      )}
                    </div>
                  ) : (
                    <div className="private-avatar-wrapper">
                      <div
                        className="private-message-avatar"
                        onClick={() => handleAvatarClick(msg.sender.id)}
                        style={{ cursor: 'pointer' }}
                      >
                        {msg.sender.username.charAt(0).toUpperCase()}
                      </div>
                      {usersStatus[msg.sender.id]?.online && (
                        <span className="status-indicator-online-privatechat"></span>
                      )}
                    </div>
                  )}
                </div>
              )}
              <div
                className={`private-message-content-container ${
                  shouldShowAvatar(msg, index) ? '' : 'no-avatar'
                } ${inlineTime(msg) ? 'inline-time' : ''} ${
                  // Снимок занимает пузырь целиком: поля пузыря обводили
                  // фотографию рамкой, которой в переписке не место.
                  msg.fileUrl && /\.(jpg|jpeg|png|gif|webp)$/i.test(msg.fileUrl) ? 'with-photo' : ''
                }`}
              >
                <div className="private-message-body">
                  <div className="private-message-content-wrapper">
                    {msg.messageType === 'audio' && msg.fileUrl ? (
                      <div className="private-audio-message-container">
                        {/* Перевод над аудиоплеером */}
                        {isTranscriptionEnabled && transcribedContentMap[msg.id]?.content && (
                          <span
                            className="private-transcribed-content" // Крупный шрифт для перевода/оригинала
                            onClick={() => {
                              if (transcribedContentMap[msg.id]?.translatedContent) {
                                setMessages((prevMessages) =>
                                  prevMessages.map((m) =>
                                    m.id === msg.id ? { ...m, showOriginal: !m.showOriginal } : m
                                  )
                                );
                              }
                            }}
                            style={{ cursor: transcribedContentMap[msg.id]?.translatedContent ? 'pointer' : 'default' }}
                          >
                            {isTranslationEnabled && transcribedContentMap[msg.id]?.translatedContent
                              ? msg.showOriginal
                                ? transcribedContentMap[msg.id]?.content
                                : transcribedContentMap[msg.id]?.translatedContent
                              : transcribedContentMap[msg.id]?.content}
                          </span>
                        )}
                        <div className="private-audio-player-wrapper">
                          <audio
                            ref={(el) => {
                              if (el && audioRefs.current[msg.id] !== el) {
                                audioRefs.current[msg.id] = el;
                                if (!audioProgress[msg.id]?.isPreloading) {
                                  console.log(`Audio ref set for msg ${msg.id}, readyState: ${el?.readyState}`);
                                }
                              }
                            }}
                            onTimeUpdate={() => handleTimeUpdate(msg.id, audioRefs.current[msg.id])}
                            onLoadedMetadata={() => handleLoadedMetadata(msg.id, audioRefs.current[msg.id])}
                            onCanPlay={() => handleCanPlay(msg.id)}
                            onError={(e) => handleAudioError(msg.id, e)}
                            onEnded={() => handleAudioEnded(msg.id)}
                            className="private-audio-player"
                            preload="auto"
                          >
                            <source src={msg.fileUrl} type={msg.fileUrl.endsWith('.mp3') ? 'audio/mpeg' : 'audio/webm'} />
                            Your browser does not support the audio element.
                          </audio>
                          {audioProgress[msg.id]?.error ? (
                            <span className="private-audio-error">{audioProgress[msg.id].error}</span>
                          ) : (
                            <div className="private-audio-controls">
                              <button
                                className="private-audio-play-pause"
                                onClick={() => {
                                  const audio = audioRefs.current[msg.id];
                                  if (!audio || !audioProgress[msg.id]?.isReady) {
                                    console.log(`Play blocked for msg ${msg.id}:`, audioProgress[msg.id], 'readyState:', audio?.readyState);
                                    return;
                                  }
                                  if (audio.paused) {
                                    audio.play().catch((e) => {
                                      console.error('Play error:', e);
                                      handleAudioError(msg.id, e);
                                    });
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
                                disabled={!audioProgress[msg.id]?.isReady}
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
                                url={msg.fileUrl}
                                progress={
                                  audioProgress[msg.id]?.duration && audioProgress[msg.id]?.duration !== Infinity
                                    ? (audioProgress[msg.id].currentTime || 0) / audioProgress[msg.id].duration
                                    : 0
                                }
                                onClick={(e) => handleSeek(msg.id, e)}
                                onMouseDown={(e) => handleDragStart(msg.id, e)}
                                style={{ cursor: audioProgress[msg.id]?.duration === 0 ? 'not-allowed' : 'pointer' }}
                              />
                              <span className="private-audio-time">
                                {formatAudioTime(
                                  audioProgress[msg.id]?.isPlaying || audioProgress[msg.id]?.currentTime
                                    ? audioProgress[msg.id].currentTime
                                    : audioProgress[msg.id]?.duration || 0
                                )}
                              </span>
                            </div>
                          )}
                        </div>
                        {/* Транскрипция под аудиоплеером */}
                        {isTranscriptionEnabled && transcribedContentMap[msg.id]?.content && (
                          <span className="private-translated-content"> {/* Меньший шрифт для транскрипции */}
                            {transcribedContentMap[msg.id]?.content}
                          </span>
                        )}
                      </div>
                    ) : (
                      <>
                        {/* Снимок идёт перед подписью: прежде подпись стояла над фотографией,
                            а в переписке принято наоборот — сперва снимок, текст под ним. */}
                        {/* Превью изображения — показываем, если fileUrl заканчивается на картинку */}
                        {msg.fileUrl && /\.(jpg|jpeg|png|gif|webp)$/i.test(msg.fileUrl) && (
                          <div className="image-preview-container">
                        {/*
                          Картинка не открылась — чаще всего файл потерян при
                          переразвёртывании сервера. Прежде её просто прятали, и
                          в пузыре оставалась необъяснимая пустая полоса. Пишем
                          прямо, как у голосовых: вложение недоступно.
                        */}
                            {brokenImages[msg.id] || isMissingAttachment(msg.fileUrl) ? (
                              <span className="attachment-unavailable">Image unavailable</span>
                            ) : (
                              <img
                                src={msg.fileUrl}
                                alt="Image in the message"
                                className="message-image-preview"
                                onClick={() => openFullScreenImage(msg.fileUrl)}
                                loading="lazy"
                                onError={() => {
                                  console.warn('Ошибка загрузки превью в личном чате:', msg.fileUrl);
                                  rememberMissingAttachment(msg.fileUrl);
                                  setBrokenImages((broken) => ({ ...broken, [msg.id]: true }));
                                }}
                              />
                            )}
                          </div>
                        )}

                        <div
                          className={`private-message-content ${isTranslationEnabled && msg.messageType !== 'audio' ? 'translate-enabled' : ''} ${isTTSEnabled && msg.messageType !== 'audio' ? 'tts-enabled' : ''}`}
                          style={{ cursor: msg.translatedContent ? 'pointer' : 'default' }}
                          onClick={(e) => {
                            // Переключаем перевод, если клик НЕ по ссылке и НЕ по изображению
                            if (e.target.tagName !== 'A' && e.target.tagName !== 'a' && e.target.tagName !== 'IMG') {
                              if (msg.translatedContent) {
                                setMessages((prevMessages) =>
                                  prevMessages.map((m) =>
                                    m.id === msg.id ? { ...m, showOriginal: !m.showOriginal } : m
                                  )
                                );
                              }
                            }
                          }}
                        >
                          <span className="private-original-content">
                            {renderTextWithLinks(
                              msg.showOriginal || !msg.translatedContent ? msg.content : msg.translatedContent
                            )}
                            {/* Время — в конце последней строки текста, как в
                                привычных мессенджерах: короткая реплика не
                                занимает отдельную строку под подпись. */}
                            {inlineTime(msg) && !hasTranslation(msg) && (
                              <span className="private-message-footer inline">
                                <span className="private-message-timestamp">{formatTimestamp(msg.timestamp)}</span>
                                {msg.sender.id === userId && (
                                  <span className="private-message-status">
                                    <MessageChecks delivered={isDeliveredFlag(msg)} read={isReadFlag(msg)} />
                                  </span>
                                )}
                              </span>
                            )}
                          </span>

                          {hasTranslation(msg) && (
                            <span className="private-translated-content">
                              {renderTextWithLinks(msg.translatedContent)}
                            </span>
                          )}
                        </div>

                        {/* Ссылка на скачивание — только если НЕ картинка */}
                        {msg.fileUrl && !/\.(jpg|jpeg|png|gif|webp)$/i.test(msg.fileUrl) && (
                          <a
                            href={msg.fileUrl}
                            download
                            className={`private-file-link ${isTranslationEnabled && msg.messageType !== 'audio' ? 'translate-enabled' : ''} ${isTTSEnabled && msg.messageType !== 'audio' ? 'tts-enabled' : ''}`}
                          >
                            📥 Download file
                          </a>
                        )}

                        {/* Превью ссылок (оставляем как было) */}
                        {msg.messageType !== 'audio' && 
                         !msg.fileUrl && 
                         extractFirstUrl(msg.content || '') && 
                         previewData[msg.id] && (
                          <div 
                            className="link-preview-card"
                            onClick={() => window.open(previewData[msg.id].url, '_blank')}
                          >
                            {previewData[msg.id].imageUrl && (
                              <img
                                src={previewData[msg.id].imageUrl}
                                alt="Link preview"
                                style={{
                                  width: '100%',
                                  height: '140px',
                                  objectFit: 'cover',
                                  display: 'block',
                                }}
                                onError={(e) => { e.target.style.display = 'none'; }}
                              />
                            )}
                            <div style={{
                              padding: '10px 12px 4px',
                              fontWeight: 600,
                              fontSize: '15px',
                              lineHeight: '1.4',
                              color: '#E2E8F0',
                            }}>
                              {previewData[msg.id].title}
                            </div>
                            <div style={{
                              padding: '0 12px 8px',
                              fontSize: '13px',
                              color: '#CBD5E1',
                              lineHeight: '1.4',
                              maxHeight: '60px',
                              overflow: 'hidden',
                            }}>
                              {previewData[msg.id].description}
                            </div>
                            <div style={{
                              padding: '0 12px 8px',
                              fontSize: '12px',
                              color: '#94A3B8',
                            }}>
                              {new URL(previewData[msg.id].url).hostname}
                            </div>
                          </div>
                        )}
                      </>
                    )}
                    {/*
                      Время отдельной строкой там, где подвёрстывать его не к чему:
                      у голосовых, файлов, превью — и под переводом. Прижимать время
                      к строке перевода оказалось плохо: у коротких переводов оно
                      сливалось с ними в одну строку, у длинных всё равно съезжало
                      вниз — выходил разнобой.
                    */}
                    {(!inlineTime(msg) || hasTranslation(msg)) && (
                    <div className="private-message-footer">
                      <div className="private-message-timestamp">{formatTimestamp(msg.timestamp)}</div>
                      {msg.sender.id === userId && (
                        <div className="private-message-status">
                          <MessageChecks delivered={isDeliveredFlag(msg)} read={isReadFlag(msg)} />
                        </div>
                      )}
                    </div>
                    )}
                  </div>
                  {/* ДОБАВЛЕНО: Отдельный контейнер для иконки перевода */}
                  {(msg.messageType !== 'audio' || (msg.messageType === 'audio' && isTranscriptionEnabled)) && (
                    <div className="private-message-actions-container">
                      {msg.messageType === 'audio' && isTranscriptionEnabled && (
                        <div
                          className="private-message-transcription-container"
                          onMouseEnter={(e) => showTooltip(e, 'Transcribe audio message')}
                          onMouseLeave={hideTooltip}
                        >
                          <button
                            className="private-message-transcription-button"
                            onClick={() => transcribeSingleMessage(msg)}
                            data-tooltip="Transcribe audio message"
                            disabled={transcribedContentMap[msg.id]?.content}
                          >
                            🎤
                          </button>
                        </div>
                      )}
                      {(msg.messageType !== 'audio' || (msg.messageType === 'audio' && isTranscriptionEnabled)) && isTranslationEnabled && (
                        <div className="private-message-translate-container">
                          <button
                            className="private-message-translate-button"
                            onClick={() => translateSingleMessage(msg)}
                            data-tooltip="Translate message"
                            onMouseEnter={(e) => showTooltip(e, 'Translate message')}
                            onMouseLeave={hideTooltip}
                          >
                            🌐
                          </button>
                        </div>
                      )}
                      {(msg.messageType !== 'audio' || (msg.messageType === 'audio' && isTranscriptionEnabled)) && isTTSEnabled && (
                        <div className="private-message-tts-container">
                          <button
                            className="private-message-tts-button"
                            onClick={() => handleTTSOriginal(msg)}
                            data-tooltip="Listen to message"
                            onMouseEnter={(e) => showTooltip(e, 'Listen to message')}
                            onMouseLeave={hideTooltip}
                          >
                            🔊
                          </button>
                        </div>
                      )}
                      {(msg.messageType !== 'audio' || (msg.messageType === 'audio' && isTranscriptionEnabled)) && isTTSEnabled && isTranslationEnabled && (
                        <div className="private-message-tts-translated-container">
                          <button
                            className="private-message-tts-translated-button"
                            onClick={() => handleTTSTranslated(msg)}
                            data-tooltip="Listen to translated message"
                            onMouseEnter={(e) => showTooltip(e, 'Listen to translated message')}
                            onMouseLeave={hideTooltip}
                          >
                            <svg width="18" height="18" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                              <path d="M3 9v6h4l5 5V4l-5 5H3zm13.5 3A4.5 4.5 0 0014 7.97v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77 0-4.28-2.99-7.86-7-8.77z"/>
                            </svg>
                          </button>
                        </div>
                      )}
                    </div>
                  )}
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
      <div className="private-input-container">
        <div className="private-textarea-wrapper">
          <div className="private-textarea-inner">
              <span
                  onClick={() => fileInputRef.current.click()}
                  className="private-attach-icon"
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
                  className="private-input-message"
                  rows={rows}
              />
              <input
                  type="file"
                  onChange={(e) => setSelectedFile(e.target.files[0])}
                  className="private-file-input"
                  ref={fileInputRef}
                  id="private-file-input"
              />
              {/* Иконка AI-ассистента */}
              <span
                onClick={handleOpenAiDialog}
                className={`private-ai-icon ${content.length > 0 || selectedFile ? 'hidden' : ''}`}
                role="button"
                aria-label="Draft a message with AI"
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M12 2L2 7L12 12L22 7L12 2Z" fill="#6200EA"/>
                  <path d="M2 17L12 22L22 17" fill="#6200EA"/>
                  <path d="M2 12L12 17L22 12" fill="#6200EA"/>
                </svg>
              </span>
              {content.length === 0 && micIconState !== 'sending' && !selectedFile && (
                  <div className="private-mic-icon-container">
                      <span
                          onClick={isRecording ? stopRecording : startRecording}
                          className={`private-mic-icon ${micIconState} ${!userId ? 'disabled' : ''}`}
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
                  <div className="private-send-icon-container">
                      <span
                          onClick={sendMessage}
                          className="private-send-icon"
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
              <div className="private-attached-file">
                  <span className="private-attached-file-name">{selectedFile.name}</span>
                  <button
                      className="private-remove-file-button"
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
      {contextMenu.visible && (
        <div
          ref={contextMenuRef}
          className="private-context-menu"
          style={{
              top: contextMenu.y + 'px',   // ← верх меню = низ сообщения
              left: contextMenu.x + 'px',  // ← левый край = левый край сообщения
          }}
          onMouseLeave={() => setContextMenu({ visible: false })}
        >
          {моёСообщение(contextMenu.message) && (
            <button onClick={() => handleMenuAction('edit')}><ЗначокПравки /><span>Edit</span></button>
          )}
          <button onClick={() => handleMenuAction('delete')}><ЗначокКорзины /><span>Delete</span></button>
          {/* «Выделить» — как в телеграме, последним пунктом. Само
              выделение сделаем следующим шагом. */}
          <button onClick={() => handleMenuAction('select')}><ЗначокВыбора /><span>Select</span></button>
        </div>
      )}
      {/* ─── НАЧАЛО ВСТАВКИ: Полноэкранное фото ─── */}
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
              src={fullScreenImage} 
              alt="Full screen image" 
              className="full-screen-image"
            />

            <a 
              href={fullScreenImage}
              download
              className="full-screen-download-btn"
              title="Download the photo"
            >
              ↓
            </a>
          </div>
        </div>
      )}
      {/* ─── КОНЕЦ ВСТАВКИ ─── */}
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
      <AiMessageGeneratorDialog
        key={openAiDialog ? "ai-dialog-fresh" : undefined}
        open={openAiDialog}
        onClose={() => setOpenAiDialog(false)}
        onInsert={(text) => {
          setContent(text.trim());
          toast.info('Text inserted — edit and send');
        }}
        onSendDirect={sendDirectFromAI}          // ← эта функция теперь независима
        messages={messages}
      />
    </div>
  );
};

export default PrivateChat;