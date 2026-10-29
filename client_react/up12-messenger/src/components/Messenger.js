import React, { useEffect, useState, useRef, useMemo } from 'react';
import { stompAuthHeaders } from '../authToken';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages, sendMessage as apiSendMessage, loginUser } from '../api/messages';
import { createUser } from '../api/users';
import '../styles/Messenger.css';
import { toast } from 'react-toastify';
import { useParams, useNavigate } from 'react-router-dom';
import IncomingCallNotification from './IncomingCallNotification';
import { format, isToday, parseISO } from 'date-fns';
import { useUserInteraction } from '../context/UserInteractionContext';
import { useUserStatusContext } from '../context/UserStatusContext';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import { usePrivateMessageNotification } from '../context/PrivateMessageNotificationContext';
import TranslationSettingsModal from './TranslationSettingsModal';
import TranscriptionSettingsModal from './TranscriptionSettingsModal'; // ДОБАВЛЕНО: Импорт модального окна для транскрипции
import AssistantIcon from './AssistantIcon';
import { translateMessage, saveTranslation } from '../api/messages';
import useGroupChatTranslationAndSpellCheck from '../hooks/useGroupChatTranslationAndSpellCheck';
import sodium from 'libsodium-wrappers';
import { FFmpeg } from '@ffmpeg/ffmpeg';
import AiMessageGeneratorDialog from './AiMessageGeneratorDialog';
import GroupMembersDialog from './GroupMembersDialog';
import { chatAvatarUrl } from '../api/chatMembers';
import { getChatById } from '../api/chats';
import { getCachedPreview, loadPreview } from '../utils/linkPreviewCache';

const getChatName = async (chatId) => {
    const response = await fetch(`https://unlimitedpossibilities12.org/api/chats/${chatId}`);
    if (!response.ok) {
        throw new Error('Failed to fetch chat name');
    }
    const chat = await response.json();
    return chat.name;
};

const KEYSTORE_ALIAS = 'secret_chat_key';

const generateAndSaveKeys = async (userId) => {
  try {
    await sodium.ready; // Инициализация libsodium
    const alias = `${KEYSTORE_ALIAS}_${userId}`;
    if (!localStorage.getItem(alias)) {
      const { publicKey, privateKey } = sodium.crypto_box_keypair(); // Генерация пары ключей
      const privateKeyBase64 = sodium.to_base64(privateKey); // Преобразование в Base64
      localStorage.setItem(alias, privateKeyBase64); // Сохранение приватного ключа
      await sendPublicKeyToServer(userId, sodium.to_base64(publicKey)); // Отправка публичного ключа
      console.log(`Keys generated and saved for userId: ${userId}`);
    }
  } catch (error) {
    console.error('Error generating keys:', error);
    toast.error('Failed to initialize encryption keys');
  }
};

const sendPublicKeyToServer = async (userId, publicKey) => {
  try {
    const response = await fetch(`https://unlimitedpossibilities12.org/api/public-keys?userId=${userId}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(publicKey),
    });
    if (!response.ok) {
      throw new Error(`Failed to upload public key: ${response.status}`);
    }
    console.log(`Public key uploaded successfully for userId: ${userId}`);
  } catch (error) {
    console.error('Error uploading public key:', error);
    toast.error('Failed to upload public key');
  }
};


/**
 * Разделители дат в ленте: без них сообщения разных дней идут подряд и
 * непонятно, где кончился вчерашний разговор.
 */

/** Сообщение, состоящее из одной ссылки: переводить в нём нечего. */
/**
 * Можно ли подвёрстать время в конец последней строки текста.
 * У голосовых, файлов и превью ссылок своя раскладка — там время остаётся
 * отдельной строкой, иначе оно наложится на плеер или картинку.
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
  return date.toLocaleDateString([], sameYear
    ? { day: 'numeric', month: 'long' }
    : { day: 'numeric', month: 'long', year: 'numeric' });
};

const startsNewDay = (messages, index) => {
  const current = dayKey(messages[index]?.timestamp);
  if (!current) return false;
  if (index === 0) return true;
  return current !== dayKey(messages[index - 1]?.timestamp);
};

const Messenger = () => {
    const [messages, setMessages] = useState([]);
    const [content, setContent] = useState('');
    const [selectedFile, setSelectedFile] = useState(null);
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [userId, setUserId] = useState(null);
    const [client, setClient] = useState(null);
    const [isLoggedIn, setIsLoggedIn] = useState(false);
    const [errorMessage, setErrorMessage] = useState('');
    const [rows, setRows] = useState(1);
    const [charPerLine, setCharPerLine] = useState(50);
    const [editingMessageId, setEditingMessageId] = useState(null);
    const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, message: null });
    const [chatName, setChatName] = useState('');
    // Карточка группы целиком: панели участников нужны картинка и признак
    // публичности, а шапке — картинка вместо кружка с буквой.
    const [chatInfo, setChatInfo] = useState(null);
    const [showMembers, setShowMembers] = useState(false);
    const [knownUsers, setKnownUsers] = useState([]);
    const [chatAvatarBroken, setChatAvatarBroken] = useState(false);
    const [currentUserId, setCurrentUserId] = useState(null);

    const textAreaRef = useRef(null);
    const messagesEndRef = useRef(null);
    const messageListRef = useRef(null);
    const fileInputRef = useRef(null);
    const mediaRecorderRef = useRef(null);
    const contextMenuRef = useRef(null);
    const { chatId } = useParams();
    const navigate = useNavigate();
    const { userInteracted, handleInteraction } = useUserInteraction();
    const { usersStatus, initializeWebSocket, disconnectWebSocket, sendOnlineStatus } = useUserStatusContext();
    const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
    const { setCurrentUserId: setNotificationUserId } = usePrivateMessageNotification();

    const [isTranslationEnabled, setIsTranslationEnabled] = useState(false);
    const [translationLanguage, setTranslationLanguage] = useState('auto');
    const [translationMode, setTranslationMode] = useState('selective');
    const [translationQueue, setTranslationQueue] = useState([]);
    const [isTranslating, setIsTranslating] = useState(false);
    const [showTranslationModal, setShowTranslationModal] = useState(false);
    const [chatMenuOpen, setChatMenuOpen] = useState(false);
    // Картинки на сервере пропадают при переразвёртывании: если аватарка не
    // открылась, показываем кружок с буквой, а не битый значок
    const [brokenAvatars, setBrokenAvatars] = useState({});
    const chatMenuRef = useRef(null);
    const [showTranscriptionModal, setShowTranscriptionModal] = useState(false); // ДОБАВЛЕНО: Для модального окна транскрипции

    const [isSpellCheckEnabled, setIsSpellCheckEnabled] = useState(false);

    // Состояния для аудиозаписи
    const [isRecording, setIsRecording] = useState(false);
    const [audioBlob, setAudioBlob] = useState(null);
    const [micIconState, setMicIconState] = useState('mic');

    // Состояния для аудиоплеера
    const [audioProgress, setAudioProgress] = useState({});
    const audioRefs = useRef({});
    const [isDragging, setIsDragging] = useState(null); // ДОБАВЛЕНО: Для перетаскивания ползунка

    // Состояния для TTS
    const [isTTSEnabled, setIsTTSEnabled] = useState(false);
    const ttsAudioRef = useRef(null);

    // Состояние для FFmpeg
    const ffmpeg = useRef(new FFmpeg());
    const [ffmpegLoaded, setFfmpegLoaded] = useState(false);

    // Состояния для транскрипции
    const [isTranscriptionEnabled, setIsTranscriptionEnabled] = useState(false);
    const [transcriptionMode, setTranscriptionMode] = useState('selective');
    const [transcribedContentMap, setTranscribedContentMap] = useState({});
    const [transcriptionQueue, setTranscriptionQueue] = useState([]);
    const [isTranscribing, setIsTranscribing] = useState(false);

    const [previewData, setPreviewData] = useState({}); // ДОБАВЛЕНО: Кэш превью ссылок
    // Картинки, которых больше нет на сервере: вместо пустоты показываем подпись
    const [brokenImages, setBrokenImages] = useState({});
    /** Короткая обёртка: превью ссылок живут в состоянии компонента. */
    const inlineTime = (msg) => canFitTimeInline(msg, previewData);
    const [fullScreenImage, setFullScreenImage] = useState(null);

    const [openAiDialog, setOpenAiDialog] = useState(false);

    // Использование хука
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
        handleTranscriptionSettingsSave,
        transcribeSingleMessage,
        transcribeExistingMessages,
        transcribeNextMessage,
    } = useGroupChatTranslationAndSpellCheck({
        chatId,
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
        isTTSEnabled,
        setIsTTSEnabled,
        isTranscriptionEnabled,
        setIsTranscriptionEnabled,
        transcriptionMode,
        setTranscriptionMode,
        transcribedContentMap,
        setTranscribedContentMap,
        transcriptionQueue,
        setTranscriptionQueue,
        isTranscribing,
        setIsTranscribing,
    });

    // Мемоизация аудиосообщений
    const memoizedAudioMessages = useMemo(() => {
        return messages.filter((msg) => msg.messageType === 'audio' && msg.fileUrl);
    }, [messages]);

    // Форматирование временной метки

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

    // Прокрутка к последнему сообщению
    const scrollToBottom = () => {
        if (messageListRef.current) {
            messageListRef.current.scrollTop = messageListRef.current.scrollHeight;
        }
    };

    // Обработчик клика по аватару
    const handleAvatarClick = (senderId) => {
        navigate(`/user/${senderId}`);
    };

    // Функция для отправки уведомлений
    const sendNotification = (message, contentToUse) => {
        if (client && client.connected) {
            const notification = {
                chatId: message.chat.id,
                senderId: message.sender.id,
                content: contentToUse || message.content,
                timestamp: message.timestamp,
                messageType: message.messageType,
            };
            client.publish({
                destination: `/app/notify/chat/${chatId}`,
                body: JSON.stringify(notification),
            });
            console.log('Notification sent:', notification);
        } else {
            console.error('Cannot send notification: STOMP client is not connected');
        }
    };

    // Динамические всплывающие подсказки
    const showTooltip = (event, tooltipText) => {
        const existingTooltip = document.querySelector('.tooltip-dynamic');
        const existingArrow = document.querySelector('.tooltip-dynamic-arrow');
        if (existingTooltip) existingTooltip.remove();
        if (existingArrow) existingArrow.remove();

        const tooltip = document.createElement('div');
        tooltip.className = 'tooltip-dynamic';
        tooltip.textContent = tooltipText;

        const arrow = document.createElement('div');
        arrow.className = 'tooltip-dynamic-arrow';

        document.body.appendChild(tooltip);
        document.body.appendChild(arrow);

        const button = event.currentTarget;
        const rect = button.getBoundingClientRect();
        const isMobile = window.innerWidth <= 800;

        tooltip.style.top = `${rect.top - (isMobile ? 20 : 24) - (isMobile ? 4 : 8)}px`;
        tooltip.style.left = `${rect.left + rect.width / 2}px`;
        tooltip.style.transform = 'translateX(-50%)';

        arrow.style.top = `${rect.top - (isMobile ? 4 : 8)}px`;
        arrow.style.left = `${rect.left + rect.width / 2}px`;
        arrow.style.transform = 'translateX(-50%)';
    };

    const hideTooltip = () => {
        const tooltip = document.querySelector('.tooltip-dynamic');
        const arrow = document.querySelector('.tooltip-dynamic-arrow');
        if (tooltip) tooltip.remove();
        if (arrow) arrow.remove();
    };

    useEffect(() => {
      const initializeChat = async () => {
          try {
              // Загружаем настройки пользователя и перевода
              const storedUserId = localStorage.getItem('userId');
              const storedUsername = localStorage.getItem('username');
              let translationEnabled = localStorage.getItem(`translation_enabled_${chatId}`) === 'true';
              if (storedUserId && storedUsername) {
                  const userIdNum = Number(storedUserId);
                  setUserId(userIdNum);
                  setCurrentUserId(userIdNum);
                  setContextUserId(userIdNum);
                  setNotificationUserId(userIdNum);
                  setUsername(storedUsername);
                  setIsLoggedIn(true);
                  setIsTranslationEnabled(translationEnabled);
                  setTranslationLanguage(localStorage.getItem(`translation_language_${chatId}`) || 'auto');
                  setTranslationMode(localStorage.getItem(`translation_mode_${chatId}`) || 'selective');
                  setIsSpellCheckEnabled(localStorage.getItem(`spellcheck_enabled_${chatId}`) === 'true');
                  setIsTTSEnabled(localStorage.getItem(`tts_enabled_${chatId}`) === 'true');
                  setIsTranscriptionEnabled(localStorage.getItem(`transcription_enabled_${chatId}`) === 'true');
                  setTranscriptionMode(localStorage.getItem(`transcription_mode_${chatId}`) || 'selective');
              } else {
                  /*toast.error('User is not authorized');
                  navigate('/');
                  return;*/
                  toast.info('Guest mode: reading group chat allowed');
                  console.log('Guest mode: reading group chat allowed');
              }

              // Загружаем сообщения
              const initialMessages = await getMessages(chatId);
              const updatedMessages = initialMessages.map((msg) => {
                  const translationKey = `translation_${chatId}_${msg.id}`;
                  const transcriptionKey = `transcription_${chatId}_${msg.id}`;
                  const savedTranslation = localStorage.getItem(translationKey);
                  const savedTranscription = localStorage.getItem(transcriptionKey);
                  const result = {
                      ...msg,
                      delivered: msg.delivered_status,
                      read: msg.read_status,
                      showOriginal: true, // По умолчанию показываем оригинал
                  };
                  // Загружаем переводы для текстовых сообщений, если перевод включён
                  if (translationEnabled && savedTranslation && msg.messageType !== 'audio') {
                      const { translatedContent, translationLanguage } = JSON.parse(savedTranslation);
                      result.translatedContent = translatedContent;
                      result.translationLanguage = translationLanguage;
                  } else {
                      result.translatedContent = null; // Явно сбрасываем перевод
                      result.translationLanguage = null; // Явно сбрасываем язык
                  }
                  // Загружаем транскрипции для аудиосообщений
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
                      // Если перевод включён и есть переведённая транскрипция, показываем перевод
                      if (translationEnabled && transcriptionData.translatedContent) {
                          result.showOriginal = false;
                      }
                  }
                  return result;
              });
              setMessages(updatedMessages);
              const chat = await getChatById(chatId);
              setChatName(chat.name);
              setChatInfo(chat);
              if (updatedMessages.length > 0) {
                  localStorage.setItem(`last_visit_chat_${chatId}`, updatedMessages[updatedMessages.length - 1].timestamp);
              }
              setTimeout(() => {
                  scrollToBottom();
              }, 0);
          } catch (error) {
              console.error('Ошибка при загрузке данных чата:', error);
              toast.error('Failed to load chat data');
              navigate('/');
          }
      };

      initializeChat();
    }, [chatId, setContextUserId, setNotificationUserId, navigate]);

    // Люди для «добавить в группу» — запрашиваем, только когда панель открыта:
    // на экране переписки этот список не нужен.
    useEffect(() => {
      if (!showMembers || knownUsers.length) return;
      fetch('https://unlimitedpossibilities12.org/api/users')
        .then((response) => (response.ok ? response.json() : []))
        .then((people) => setKnownUsers(Array.isArray(people) ? people : []))
        .catch(() => setKnownUsers([]));
    }, [showMembers, knownUsers.length]);

    // После правок в панели перечитываем карточку: имя и картинка в шапке
    // должны смениться сразу, а не после повторного захода в чат.
    const reloadChatInfo = () => {
      getChatById(chatId)
        .then((chat) => {
          setChatInfo(chat);
          setChatName(chat.name);
          setChatAvatarBroken(false);
        })
        .catch((error) => console.warn('Could not refresh the group card:', error));
    };

    useEffect(() => {
      if (isTranslationEnabled && translationMode === 'all' && messages.length > 0) {
          console.log('Translating existing messages with language:', translationLanguage);
          translateExistingMessages();
      }
      if (isTranscriptionEnabled && transcriptionMode === 'all' && memoizedAudioMessages.length > 0) {
          console.log('Transcribing existing audio messages');
          transcribeExistingMessages();
      }
    }, [isTranslationEnabled, translationLanguage, translationMode, messages, translateExistingMessages, isTranscriptionEnabled, transcriptionMode, memoizedAudioMessages, transcribeExistingMessages]);

    useEffect(() => {
        if (
            (!isTranslationEnabled && messages.some((msg) => msg.translatedContent || msg.translationLanguage)) ||
            (!isTranscriptionEnabled && Object.keys(transcribedContentMap).length > 0)
        ) {
            console.log('Cleaning translations and transcriptions due to disabled features');
            setMessages((prevMessages) =>
                prevMessages.map((msg) => ({
                    ...msg,
                    translatedContent: null,
                    translationLanguage: null,
                    showOriginal: true,
                }))
            );
            setTranscribedContentMap({});
        }
    }, [isTranslationEnabled, isTranscriptionEnabled]);

    /*
    Настройки перевода и расшифровки держим в ссылке, а не в зависимостях
    подписки. Прежде соединение пересобиралось ещё дважды сразу после входа в
    чат — настройки читаются из памяти браузера уже после первого рисования.
    Каждое лишнее подключение занимает соединение к серверу, а их у браузера
    на один сайт всего шесть.
  */
    const liveRef = useRef({});
    liveRef.current = {
        isTranslationEnabled,
        translationMode,
        isTranscriptionEnabled,
        transcriptionMode,
        isMessageNew,
    };

    useEffect(() => {
        const stompClientInstance = new Client({
            connectHeaders: stompAuthHeaders(),
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');
                stompClientInstance.subscribe(`/topic/chat/${chatId}/messages`, (message) => {
                    const newMessage = JSON.parse(message.body);
                    updateMessages(newMessage);
                    if (liveRef.current.isTranslationEnabled && (liveRef.current.translationMode === 'new' || liveRef.current.translationMode === 'all') && liveRef.current.isMessageNew(newMessage)) {
                        setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
                    }
                    if (
                        liveRef.current.isTranscriptionEnabled &&
                        (liveRef.current.transcriptionMode === 'new' || liveRef.current.transcriptionMode === 'all') &&
                        newMessage.messageType === 'audio' &&
                        newMessage.fileUrl &&
                        liveRef.current.isMessageNew(newMessage)
                    ) {
                        setTranscriptionQueue((prevQueue) => [...prevQueue, newMessage]);
                    }
                });
            },
            onWebSocketError: (error) => {
                console.error('WebSocket error:', error);
            },
        });

        setClient(stompClientInstance);
        stompClientInstance.activate();

        return () => {
            stompClientInstance.deactivate();
        };
    }, [chatId]);

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
                scrollToBottom();
            }, 0);
        }
    }, [messages.length]);

    useEffect(() => {
        translateNextMessage();
    }, [translationQueue, isTranslating, translateNextMessage]);

    useEffect(() => {
        transcribeNextMessage();
    }, [transcriptionQueue, isTranscribing, transcribeNextMessage]);

    useEffect(() => {
        const calculateCharPerLine = () => {
            if (textAreaRef.current) {
                const canvas = document.createElement('canvas');
                const context = canvas.getContext('2d');
                context.font = window.innerWidth <= 800 ? '14px Roboto' : '16px Roboto';
                const avgCharWidth = context.measureText('m').width;
                const textareaWidth = textAreaRef.current.clientWidth - 50;
                const chars = Math.floor(textareaWidth / avgCharWidth);
                setCharPerLine(chars);
            }
        };
        calculateCharPerLine();
        window.addEventListener('resize', calculateCharPerLine);
        return () => window.removeEventListener('resize', calculateCharPerLine);
    }, []);

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

    useEffect(() => {
        const visibleAudioMessages = memoizedAudioMessages.slice(-10);
        visibleAudioMessages.forEach((msg) => {
            if (
                audioRefs.current[msg.id] &&
                !audioProgress[msg.id]?.isReady &&
                !audioProgress[msg.id]?.isPreloading
            ) {
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
            document.addEventListener('touchstart', handleClickOutside); // ← для мобильных
        }

        return () => {
            document.removeEventListener('mousedown', handleClickOutside);
            document.removeEventListener('touchstart', handleClickOutside);
        };
    }, [contextMenu.visible]);

    const updateMessages = (newMessage) => {
        setMessages((prevMessages) => {
            if (newMessage.content === 'Message deleted') {
                setTranscribedContentMap((prevMap) => {
                    const newMap = { ...prevMap };
                    delete newMap[newMessage.id];
                    return newMap;
                });
                return prevMessages.filter((msg) => msg.id !== newMessage.id);
            }

            const existingMessageIndex = prevMessages.findIndex((msg) => msg.id === newMessage.id);
            const isAudio = newMessage.messageType === 'audio';
            if (existingMessageIndex !== -1) {
                const updatedMessages = [...prevMessages];
                updatedMessages[existingMessageIndex] = {
                    ...updatedMessages[existingMessageIndex],
                    ...newMessage,
                    delivered: newMessage.delivered_status ?? updatedMessages[existingMessageIndex].delivered,
                    read: newMessage.read_status ?? updatedMessages[existingMessageIndex].read,
                    messageType: newMessage.messageType ?? updatedMessages[existingMessageIndex].messageType,
                    translatedContent: isAudio ? null : updatedMessages[existingMessageIndex].translatedContent,
                    translationLanguage: isAudio ? null : updatedMessages[existingMessageIndex].translationLanguage,
                    showOriginal: updatedMessages[existingMessageIndex].showOriginal,
                };
                if (
                    isTranslationEnabled &&
                    (translationMode === 'new' || translationMode === 'all') &&
                    isMessageNew(newMessage) &&
                    (!updatedMessages[existingMessageIndex].translatedContent ||
                        updatedMessages[existingMessageIndex].translationLanguage !== translationLanguage)
                ) {
                    setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
                }
                if (
                    isTranscriptionEnabled &&
                    (transcriptionMode === 'new' || transcriptionMode === 'all') &&
                    newMessage.messageType === 'audio' &&
                    newMessage.fileUrl &&
                    isMessageNew(newMessage) &&
                    !transcribedContentMap[newMessage.id]?.content
                ) {
                    setTranscriptionQueue((prevQueue) => [...prevQueue, newMessage]);
                }
                return updatedMessages;
            } else {
                const newMsg = {
                    ...newMessage,
                    delivered: newMessage.delivered_status,
                    read: newMessage.read_status,
                    showOriginal: false,
                    messageType: newMessage.messageType,
                    translatedContent: isAudio ? null : newMessage.translatedContent,
                    translationLanguage: isAudio ? null : newMessage.translationLanguage,
                };
                const updatedMessages = [...prevMessages, newMsg];
                localStorage.setItem(`last_visit_chat_${chatId}`, newMessage.timestamp);
                if (isTranslationEnabled && isMessageNew(newMessage)) {
                    setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
                }
                if (
                    isTranscriptionEnabled &&
                    (transcriptionMode === 'new' || transcriptionMode === 'all') &&
                    newMessage.messageType === 'audio' &&
                    newMessage.fileUrl &&
                    isMessageNew(newMessage)
                ) {
                    setTranscriptionQueue((prevQueue) => [...prevQueue, newMessage]);
                }
                return updatedMessages;
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
            const errorText = await response.text();
            console.error('Upload failed:', errorText);
            throw new Error('File upload failed');
        }

        const data = await response.json();
        return data.fileUrl;
    };

    const sendMessage = async () => {
        if ((content.trim() || selectedFile || audioBlob) && userId) {
            const currentUsername = username;
            const chatName = await getChatName(chatId);

            let finalContent = content;
            if (isSpellCheckEnabled) {
                const spellCheckTimestamp = localStorage.getItem(`spellcheck_enabled_timestamp_${chatId}`);
                if (spellCheckTimestamp) {
                    finalContent = await spellCheckMessage(content);
                }
            }

            const message = {
                id: editingMessageId || Date.now(),
                content: finalContent,
                sender: { id: userId, username: currentUsername },
                chat: { id: chatId, name: chatName },
                fileUrl: selectedFile ? await uploadFile(selectedFile) : audioBlob ? await uploadFile(audioBlob) : null,
                timestamp: new Date().toISOString(),
                messageType: audioBlob ? 'audio' : selectedFile ? 'file' : 'text',
                contentType: audioBlob ? 'audio/mpeg' : selectedFile ? selectedFile.type : null,
            };

            console.log('Sending message:', message);

            if (client && client.connected) {
                if (editingMessageId) {
                    const existingMessage = messages.find((msg) => msg.id === editingMessageId);
                    if (existingMessage && existingMessage.sender.id === userId) {
                        client.publish({ destination: `/app/editMessage/${chatId}`, body: JSON.stringify(message) });
                        setMessages((prevMessages) =>
                            prevMessages.map((msg) =>
                                msg.id === editingMessageId ? { ...msg, content: finalContent, timestamp: message.timestamp } : msg
                            )
                        );
                        sendNotification(message, finalContent);
                    } else {
                        toast.error('You can only edit your own messages.');
                        return;
                    }
                } else {
                    client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
                    sendNotification(message, finalContent);
                }

                resetForm();
            } else {
                console.error('STOMP client is not connected');
                toast.error('WebSocket not connected');
            }
        } else {
            console.warn('Content, file, or audio is missing');
            toast.error('Please provide a message, file, or audio');
        }
    };

    const sendAudioMessage = async (audioBlob) => {
        if (!audioBlob || !userId) {
            console.warn('Audio or userId is missing');
            toast.error('Failed to send audio message');
            setMicIconState('mic');
            return;
        }

        const currentUsername = username;
        const chatName = await getChatName(chatId);

        const audioFile = new File([audioBlob], `audio_${Date.now()}.mp3`, { type: 'audio/mpeg' });

        const message = {
            id: Date.now(),
            content: '',
            sender: { id: userId, username: currentUsername },
            chat: { id: chatId, name: chatName },
            fileUrl: await uploadFile(audioFile),
            timestamp: new Date().toISOString(),
            messageType: 'audio',
            contentType: 'audio/mpeg',
        };

        console.log('Sending audio message:', message);

        if (client && client.connected) {
            client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
            sendNotification(message, '');
            setAudioBlob(null);
            console.log('Audio message sent');
            setMicIconState('mic');
        } else {
            console.error('STOMP client is not connected');
            toast.error('WebSocket not connected');
            setMicIconState('mic');
        }
    };

    const handleKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey && (content.trim() || selectedFile)) {
            e.preventDefault();
            sendMessage();
        }
    };

    const handleFileChange = (e) => {
        setSelectedFile(e.target.files[0]);
    };

    const handleRightClick = (e, message) => {
        e.preventDefault();

        const messageElement = e.currentTarget;
        const rect = messageElement.getBoundingClientRect();

        // ЛЕВЫЙ НИЖНИЙ УГОЛ — ТОЧНО
        const x = rect.left + window.scrollX;
        const y = rect.bottom + window.scrollY - 20; // ← ВПЛОТНУЮ К НИЗУ СООБЩЕНИЯ

        setContextMenu({
            visible: true,
            x,
            y,
            message,
        });
    };

    const handleMenuAction = (action) => {
        if (contextMenu.message.sender.id !== userId) {
            toast.error('You can only edit or delete your own messages.');
            return;
        }

        if (action === 'edit') {
            setContent(contextMenu.message.content);
            setEditingMessageId(contextMenu.message.id);
        } else if (action === 'delete') {
            deleteMessage(contextMenu.message.id);
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

    const handleCreateUser = async () => {
        if (username && password) {
            const user = { username, password };
            try {
                const newUser = await createUser(user);
                setUserId(newUser.id);
                setUsername(newUser.username);
                setPassword('');
                setIsLoggedIn(true);
                localStorage.setItem('userId', newUser.id);
                localStorage.setItem('username', newUser.username);
                setCurrentUserId(newUser.id);
                setContextUserId(newUser.id);
                setNotificationUserId(newUser.id);
                await generateAndSaveKeys(newUser.id); // [ДОБАВЛЕНО] Генерация ключей
                toast.success('User successfully created!');
            } catch (error) {
                let errorMessage = 'Failed to create user. Please try again.';
                console.warn(error.message);

                if (error.message.includes('User already exists')) {
                    errorMessage = 'A user with this name already exists. Please choose an other name.';
                } else if (error.message.includes('Password must be at least 9 characters long, contain letters and numbers')) {
                    errorMessage = 'The password must contain at least 9 characters, including letters and numbers.';
                }

                toast.error(errorMessage);
            }
        } else {
            toast.error('Please fill in all fields.');
        }
    };

    const handleLogin = async () => {
        if (username && password) {
            try {
                const loggedUser = await loginUser({ username, password });
                setUserId(loggedUser.id);
                setCurrentUserId(loggedUser.id);
                setContextUserId(loggedUser.id);
                setNotificationUserId(loggedUser.id);
                setIsLoggedIn(true);
                setErrorMessage('');
                localStorage.setItem('userId', loggedUser.id);
                localStorage.setItem('username', loggedUser.username);
                await generateAndSaveKeys(loggedUser.id); // [ДОБАВЛЕНО] Проверка и генерация ключей
                initializeWebSocket(loggedUser.id, loggedUser.username);
                sendOnlineStatus();
                toast.success('You have successfully logged in!');
            } catch (error) {
                let errorMessage = 'Failed to log in. Please try again.';
                console.warn(error.message);

                if (error.message.includes('Invalid username or password')) {
                    errorMessage = 'Incorrect username or password.';
                }

                toast.error(errorMessage);
            }
        } else {
            toast.error('Please fill in all fields.');
        }
    };

    const handleLogout = () => {
        disconnectWebSocket();
        localStorage.removeItem('userId');
        localStorage.removeItem('authToken');
        localStorage.removeItem('username');
        setUserId(null);
        setCurrentUserId(null);
        setContextUserId(null);
        setNotificationUserId(null);
        setUsername('');
        setIsLoggedIn(false);
        toast.success('You are logged out!');
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

    const shouldShowAvatar = (msg) => {
        return msg.sender.id !== userId;
    };

    const startRecording = async () => {
        try {
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
                audioBitsPerSecond: 128000,
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

                try {
                    const inputName = 'input.webm';
                    const outputName = 'output.mp3';
                    const arrayBuffer = await webmBlob.arrayBuffer();
                    await ffmpeg.current.writeFile(inputName, new Uint8Array(arrayBuffer));
                    await ffmpeg.current.exec(['-i', inputName, '-c:a', 'libmp3lame', '-b:a', '128k', outputName]);
                    const mp3Data = await ffmpeg.current.readFile(outputName);
                    const mp3Blob = new Blob([mp3Data.buffer], { type: 'audio/mpeg' });
                    console.log(`Converted audio: size=${mp3Blob.size}, type=${mp3Blob.type}`);
                    setAudioBlob(mp3Blob);
                    stream.getTracks().forEach((track) => track.stop());
                    setMicIconState('sending');
                    sendAudioMessage(mp3Blob);
                } catch (error) {
                    console.error('Error converting to mp3:', error);
                    toast.error('Failed to convert audio');
                    stream.getTracks().forEach((track) => track.stop());
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

    const resetForm = () => {
        setContent('');
        setSelectedFile(null);
        setAudioBlob(null);
        setEditingMessageId(null);
        setRows(1);
        setMicIconState('mic');
        if (fileInputRef.current) {
            fileInputRef.current.value = null;
        }
        if (textAreaRef.current) {
            textAreaRef.current.style.height = '44px';
            textAreaRef.current.classList.remove('scroll-enabled');
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

    const handleCanPlay = (msgId) => {
        console.log(`Audio can play for msg ${msgId}, readyState=${audioRefs.current[msgId]?.readyState}`);
        setAudioProgress((prev) => ({
            ...prev,
            [msgId]: {
                ...prev[msgId] || {},
                isReady: true,
                error: null,
            },
        }));
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

    const handleDragStart = (msgId, e) => {
        e.preventDefault();
        const audio = audioRefs.current[msgId];
        if (!audio || !audioProgress[msgId]?.isReady) {
            console.log(`Drag blocked for msg ${msgId}: audio not ready`);
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

    const handleAudioError = (msgId, error) => {
        const audio = audioRefs.current[msgId];
        console.error(`Audio error for msg ${msgId}:`, error, 'URL:', audio?.src, 'readyState:', audio?.readyState);
        setAudioProgress((prev) => {
            const prevData = prev[msgId] || {};
            if (prevData.isReady && (prevData.currentTime > 0 || audio?.readyState >= 2)) {
                console.log(`Ignoring error for msg ${msgId}: audio playable, readyState: ${audio?.readyState}`);
                return prev;
            }
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
            audio.load();
            console.log(`Audio reloaded for msg ${msgId} to maintain playability`);
        }
    };

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
            const cacheBusterUrl = `${url}?t=${Date.now()}`;
            audio.src = cacheBusterUrl;
            audio.type = url.endsWith('.mp3') ? 'audio/mpeg' : 'audio/webm';
            audio.load();
            audio.addEventListener(
                'loadedmetadata',
                () => {
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
                },
                { once: true }
            );
            audio.addEventListener(
                'canplay',
                () => {
                    console.log(`Can play msg ${msgId}: readyState=${audio.readyState}`);
                    setAudioProgress((prev) => ({
                        ...prev,
                        [msgId]: {
                            ...prev[msgId],
                            isPreloading: false,
                            isReady: true,
                        },
                    }));
                },
                { once: true }
            );
            audio.addEventListener(
                'error',
                (e) => {
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
                },
                { once: true }
            );
        }
    };

    const handleTTSOriginal = async (msg) => {
      if (!isTTSEnabled) {
          toast.error('Text-to-speech is not enabled');
          return;
      }
      if (msg.messageType === 'audio' && !transcribedContentMap[msg.id]?.content) {
          toast.error('Transcription is not available for this audio message');
          return;
      }
      const contentToUse = msg.messageType === 'audio' && isTranscriptionEnabled
          ? transcribedContentMap[msg.id]?.content
          : msg.content;
      if (!contentToUse || contentToUse === 'Message deleted') {
          console.warn('No valid content for TTS:', { msgId: msg.id, content: contentToUse });
          toast.error('No valid content for TTS');
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
                  language: 'auto',
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
              const blob = new Blob([arrayBuffer], { type: 'audio/mpeg' });
              const url = URL.createObjectURL(blob);
              if (ttsAudioRef.current) {
                  ttsAudioRef.current.pause();
                  URL.revokeObjectURL(ttsAudioRef.current.src);
              }
              ttsAudioRef.current = new Audio(url);
              ttsAudioRef.current.play().catch((error) => {
                  console.error('Error playing TTS audio:', error);
                  toast.error('Failed to play TTS audio');
              });
              ttsAudioRef.current.onended = () => {
                  URL.revokeObjectURL(url);
                  ttsAudioRef.current = null;
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
            toast.error('No valid content for TTS');
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
                    language: languageToUse || 'auto',
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
                const blob = new Blob([arrayBuffer], { type: 'audio/mpeg' });
                const url = URL.createObjectURL(blob);

                if (ttsAudioRef.current) {
                    ttsAudioRef.current.pause();
                    URL.revokeObjectURL(ttsAudioRef.current.src);
                }
                ttsAudioRef.current = new Audio(url);
                ttsAudioRef.current.play().catch((error) => {
                    console.error('Error playing TTS audio for translated content:', error);
                    toast.error('Failed to play TTS audio');
                });
                ttsAudioRef.current.onended = () => {
                    URL.revokeObjectURL(url);
                    ttsAudioRef.current = null;
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

    const handleTTSTranscribed = async (msg) => {
        if (!isTTSEnabled || !transcribedContentMap[msg.id]?.content) {
            toast.error('No transcribed content available or TTS is disabled');
            return;
        }
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/tts', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    text: transcribedContentMap[msg.id].content,
                    language: 'auto',
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
                const blob = new Blob([arrayBuffer], { type: 'audio/mpeg' });
                const url = URL.createObjectURL(blob);

                if (ttsAudioRef.current) {
                    ttsAudioRef.current.pause();
                    URL.revokeObjectURL(ttsAudioRef.current.src);
                }
                ttsAudioRef.current = new Audio(url);
                ttsAudioRef.current.play().catch((error) => {
                    console.error('Error playing TTS audio for transcribed content:', error);
                    toast.error('Failed to play TTS audio');
                });
                ttsAudioRef.current.onended = () => {
                    URL.revokeObjectURL(url);
                    ttsAudioRef.current = null;
                    console.log('TTS audio playback ended for transcribed content:', { msgId: msg.id });
                };
            } else {
                console.error('No audio data in TTS response:', json);
                toast.error('Failed to generate TTS audio');
            }
        } catch (error) {
            console.error('TTS request error for transcribed content:', error);
            toast.error('Failed to generate TTS audio');
        }
    };

    // ДОБАВЛЕНО: Находит первую ссылку в тексте
    const extractFirstUrl = (text) => {
        if (!text) return null;
        const regex = /(https?:\/\/[^\s]+)|(www\.[^\s]+)/gi;
        const match = text.match(regex);
        if (match) {
            let url = match[0];
            if (!url.startsWith('http')) url = 'https://' + url;
            try {
            new URL(url);
            return url;
            } catch {
            return null;
            }
        }
        return null;
    };

    // ДОБАВЛЕНО: Рендерит текст с кликабельными синими ссылками
    const renderTextWithLinks = (text) => {
        if (!text) return text;

        const urlRegex = /(https?:\/\/[^\s]+)|(www\.[^\s]+)/gi;
        const parts = [];
        let lastIndex = 0;
        let match;

        while ((match = urlRegex.exec(text)) !== null) {
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
            >
                {rawUrl}
            </a>
            );

            lastIndex = urlRegex.lastIndex;
        }

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
    }, [messages]);

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

            // Уведомление для группы (по аналогии с sendMessage)
            sendNotification(message, trimmed);

            toast.success('Message sent to group');
        } else {
            toast.error('WebSocket not connected');
        }
    };

    return (
    <div className="messenger">
      {/* Большая фиксированная иконка ассистента — только в Messenger на ПК */}
      <AssistantIcon size="large" className="fixed-app-bottom-right" />
      {/*
        Шапка групповой переписки: слева кружок с буквой, имя и состояние,
        справа меню. Четыре эмодзи-кнопки режимов переехали в меню — их
        включают редко, а место занимали постоянно.
      */}
      <div className="chat-header-group">
        {/*
          Шапка — вход в настройки группы: по ней нажимают, чтобы увидеть
          участников, сменить имя и картинку, выдать ссылку-приглашение.
          Раньше всё это открывалось только со страницы контактов, и в самой
          переписке владелец не мог сделать с чатом ничего.
        */}
        <div
          className="header-content header-content-clickable"
          role="button"
          tabIndex={0}
          title="Group info, members and settings"
          onClick={() => setShowMembers(true)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              setShowMembers(true);
            }
          }}
        >
          <div className="partner-avatar-container">
            {chatAvatarUrl(chatInfo) && !chatAvatarBroken ? (
              <img
                src={chatAvatarUrl(chatInfo)}
                alt=""
                className="partner-avatar"
                onError={() => setChatAvatarBroken(true)}
              />
            ) : (
              <div className="partner-avatar placeholder">
                {(chatName || '?').trim().charAt(0).toUpperCase()}
              </div>
            )}
          </div>
          <div className="partner-info">
            <div className="partner-name">{chatName}</div>
            <div className="chat-status-line">
              <span className="chat-status-text">Group chat</span>
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

        <div className="chat-header-actions">
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
                <button type="button" onClick={() => { setChatMenuOpen(false); setShowMembers(true); }}>
                  Group info…
                </button>
                <button type="button" onClick={() => { setChatMenuOpen(false); setShowTranslationModal(true); }}>
                  Translation…
                </button>
                <button type="button" onClick={() => { setChatMenuOpen(false); setShowTranscriptionModal(true); }}>
                  Transcription…
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setChatMenuOpen(false);
                    setIsTTSEnabled((prev) => {
                      const newState = !prev;
                      localStorage.setItem(`tts_enabled_${chatId}`, newState);
                      if (!newState && ttsAudioRef.current) {
                        ttsAudioRef.current.pause();
                        ttsAudioRef.current = null;
                      }
                      return newState;
                    });
                  }}
                >
                  <span>Read aloud</span>
                  <span className={`chat-menu-switch ${isTTSEnabled ? 'on' : ''}`} />
                </button>
                <button type="button" onClick={() => { setChatMenuOpen(false); handleSpellCheckToggle(); }}>
                  <span>Spellcheck</span>
                  <span className={`chat-menu-switch ${isSpellCheckEnabled ? 'on' : ''}`} />
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
      {showTranslationModal && (
        <TranslationSettingsModal
          chatId={chatId}
          onSave={handleTranslationSettingsSave}
          onClose={() => setShowTranslationModal(false)}
        />
      )}
      {showTranscriptionModal && (
        <TranscriptionSettingsModal
          chatId={chatId}
          onSave={handleTranscriptionSettingsSave}
          onClose={() => setShowTranscriptionModal(false)}
        />
      )}
      {/* Вход и выход убраны из окна группового чата: попасть сюда без
          аккаунта нельзя, а выход лежит в меню профиля */}
      {errorMessage && <div className="error-message">{errorMessage}</div>}
      <div className="message-list" ref={messageListRef}>
        {messages.map((msg, index) => (
          msg.sender ? (
            <React.Fragment key={msg.id}>
            {startsNewDay(messages, index) && (
              <div className="private-day-separator">
                <span>{dayLabel(msg.timestamp)}</span>
              </div>
            )}
            <div
              className={`message ${msg.sender.id === userId ? 'current-user' : 'other-user'} ${
                msg.messageType === 'audio' ? 'audio-message' : ''
              }`}
              onContextMenu={(e) => handleRightClick(e, msg)}
            >
              <div className="message-wrapper">
                {shouldShowAvatar(msg) && (
                  <div className="avatar-container">
                    {msg.sender.avatar && !brokenAvatars[msg.sender.id] ? (
                      <div className="avatar-wrapper">
                        <img
                          src={`https://unlimitedpossibilities12.org${msg.sender.avatar}`}
                          alt={`${msg.sender.username} avatar`}
                          className="message-avatar"
                          onClick={() => handleAvatarClick(msg.sender.id)}
                          style={{ cursor: 'pointer' }}
                          onError={() =>
                            setBrokenAvatars((broken) => ({ ...broken, [msg.sender.id]: true }))
                          }
                        />
                        {usersStatus[msg.sender.id]?.online && (
                          <span className="status-indicator-online-groupchat"></span>
                        )}
                      </div>
                    ) : (
                      <div className="avatar-wrapper">
                        <div
                          className="message-avatar"
                          onClick={() => handleAvatarClick(msg.sender.id)}
                          style={{ cursor: 'pointer' }}
                        >
                          {msg.sender.username.charAt(0).toUpperCase()}
                        </div>
                        {usersStatus[msg.sender.id]?.online && (
                          <span className="status-indicator-online-groupchat"></span>
                        )}
                      </div>
                    )}
                  </div>
                )}
                <div className={`message-content-container ${shouldShowAvatar(msg) ? '' : 'no-avatar'} ${inlineTime(msg) ? 'inline-time' : ''}`}>
                  <div className="message-body">
                    {msg.sender.id !== userId && (
                      <span
                        className={`sender-name ${
                          (isTranslationEnabled || isTTSEnabled) && msg.messageType !== 'audio' ? 'translate-enabled' : ''
                        }`}
                      >
                        {msg.sender.username}
                      </span>
                    )}
                    {msg.messageType === 'audio' && msg.fileUrl ? (
                      <div className="audio-message-container">
                        {/* Перевод над аудиоплеером */}
                        {isTranscriptionEnabled && transcribedContentMap[msg.id]?.content && (
                          <span
                            className="transcribed-content"
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
                        <div className="audio-player-wrapper">
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
                            className="audio-player"
                            preload="auto"
                          >
                            <source src={msg.fileUrl} type={msg.fileUrl.endsWith('.mp3') ? 'audio/mpeg' : 'audio/webm'} />
                            Your browser does not support the audio element.
                          </audio>
                          {audioProgress[msg.id]?.error ? (
                            <span className="audio-error">{audioProgress[msg.id].error}</span>
                          ) : (
                            <div className="audio-controls">
                              <button
                                className="audio-play-pause"
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
                                {audioProgress[msg.id]?.isPlaying ? '⏸' : '▶'}
                              </button>
                              <div
                                className="audio-progress-bar"
                                onClick={(e) => handleSeek(msg.id, e)}
                                onMouseDown={(e) => handleDragStart(msg.id, e)}
                                style={{ cursor: audioProgress[msg.id]?.duration === 0 ? 'not-allowed' : 'pointer' }}
                              >
                                <div
                                  className="audio-progress"
                                  style={{
                                    width: `${
                                      audioProgress[msg.id]?.duration && audioProgress[msg.id]?.isReady && audioProgress[msg.id]?.duration !== 0
                                        ? (audioProgress[msg.id].currentTime / audioProgress[msg.id].duration) * 100
                                        : 0
                                    }%`,
                                  }}
                                >
                                  <div className="audio-progress-thumb"></div>
                                </div>
                              </div>
                              <span className="audio-time">
                                {formatAudioTime(audioProgress[msg.id]?.currentTime || 0)} /{' '}
                                {formatAudioTime(audioProgress[msg.id]?.duration || 0)}
                              </span>
                            </div>
                          )}
                        </div>
                        {/* Транскрипция под аудиоплеером */}
                        {isTranscriptionEnabled && transcribedContentMap[msg.id]?.content && (
                          <span className="translated-content">{transcribedContentMap[msg.id]?.content}</span>
                        )}
                      </div>
                    ) : (
                        <div className="message-content-wrapper">

                            {/* Текст сообщения (с кликабельными ссылками и переключением перевода) */}
                            <div
                                className={`message-content ${
                                    isTranslationEnabled && msg.messageType !== 'audio' ? 'translate-enabled' : ''
                                } ${isTTSEnabled && msg.messageType !== 'audio' ? 'tts-enabled' : ''}`}
                                style={{ cursor: msg.translatedContent ? 'pointer' : 'default' }}
                                onClick={(e) => {
                                    // Переключаем перевод ТОЛЬКО если клик НЕ по ссылке и НЕ по изображению
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
                                <span className="original-content">
                                    {renderTextWithLinks(
                                        msg.showOriginal || !msg.translatedContent ? msg.content : msg.translatedContent
                                    )}
                                    {/* Время — в конце последней строки текста */}
                                    {inlineTime(msg) && !hasTranslation(msg) && (
                                        <span className="message-timestamp inline">{formatTimestamp(msg.timestamp)}</span>
                                    )}
                                </span>

                                {hasTranslation(msg) && (
                                    <span className="translated-content">
                                        {renderTextWithLinks(msg.translatedContent)}
                                    </span>
                                )}
                            </div>

                            {/* Превью изображения — показываем только если тип = image и есть fileUrl */}
                            {msg.fileUrl && /\.(jpg|jpeg|png|gif|webp)$/i.test(msg.fileUrl) && (
                                <div className="image-preview-container">
                                    {/*
                                      Картинка не открылась — обычно файл потерян при
                                      переразвёртывании сервера. Раньше её прятали, и в
                                      пузыре оставалась пустая полоса без объяснения.
                                    */}
                                    {brokenImages[msg.id] ? (
                                        <span className="attachment-unavailable">Image unavailable</span>
                                    ) : (
                                        <img
                                            src={msg.fileUrl}
                                            alt="Изображение в сообщении"
                                            className="message-image-preview"
                                            onClick={() => openFullScreenImage(msg.fileUrl)}
                                            loading="lazy"
                                            onError={() => {
                                                console.warn('Ошибка загрузки превью в групповом чате:', msg.fileUrl);
                                                setBrokenImages((broken) => ({ ...broken, [msg.id]: true }));
                                            }}
                                        />
                                    )}
                                </div>
                            )}

                            {/* Ссылка на скачивание — показываем ТОЛЬКО если НЕ изображение */}
                            {msg.fileUrl && !/\.(jpg|jpeg|png|gif|webp)$/i.test(msg.fileUrl) && msg.messageType !== 'audio' && (
                                <a
                                    href={msg.fileUrl}
                                    download
                                    className={`file-link ${
                                        isTranslationEnabled && msg.messageType !== 'audio' ? 'translate-enabled' : ''
                                    } ${isTTSEnabled && msg.messageType !== 'audio' ? 'tts-enabled' : ''}`}
                                >
                                    📥 Download file
                                </a>
                            )}

                            {/*
                              Второй такой же блок ссылки отсюда убран: его условие
                              («тип не image и не audio») пересекалось с условием выше,
                              и у вложений вроде pdf «Download file» показывалось дважды,
                              а у картинок ссылка висела под самим изображением.
                            */}

                            {/* Превью ссылки (оставляем как было) */}
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
                                            alt="Превью"
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
                        </div>
                    )}
                    {(msg.messageType !== 'audio' || (msg.messageType === 'audio' && isTranscriptionEnabled)) && (
                      <div className="message-actions-container">
                        {msg.messageType === 'audio' && isTranscriptionEnabled && (
                            <div className="message-transcription-container">
                                <button
                                    className="message-transcription-button"
                                    onClick={() => transcribeSingleMessage(msg)}
                                    onMouseEnter={(e) => showTooltip(e, 'Transcribe audio')}
                                    onMouseLeave={hideTooltip}
                                    data-tooltip="Transcribe audio"
                                >
                                    🎤
                                </button>
                            </div>
                        )}
                        {(msg.messageType !== 'audio' || (msg.messageType === 'audio' && isTranscriptionEnabled)) &&
                          isTranslationEnabled && (
                            <div className="message-translate-container">
                              <button
                                className="message-translate-button"
                                onClick={() => translateSingleMessage(msg)}
                                onMouseEnter={(e) => showTooltip(e, 'Translate message')}
                                onMouseLeave={hideTooltip}
                                data-tooltip="Translate message"
                              >
                                🌐
                              </button>
                            </div>
                          )}
                        {(msg.messageType !== 'audio' || (msg.messageType === 'audio' && isTranscriptionEnabled)) &&
                          isTTSEnabled && (
                            <div className="message-tts-container">
                              <button
                                className="message-tts-button"
                                onClick={() => handleTTSOriginal(msg)}
                                onMouseEnter={(e) => showTooltip(e, 'Listen to message')}
                                onMouseLeave={hideTooltip}
                                data-tooltip="Listen to message"
                              >
                                🔊
                              </button>
                            </div>
                          )}
                        {(msg.messageType !== 'audio' || (msg.messageType === 'audio' && isTranscriptionEnabled)) &&
                          isTTSEnabled && isTranslationEnabled && (
                            <div className="message-tts-translated-container">
                              <button
                                className="message-tts-translated-button"
                                onClick={() => handleTTSTranslated(msg)}
                                onMouseEnter={(e) => showTooltip(e, 'Listen to translated message')}
                                onMouseLeave={hideTooltip}
                                data-tooltip="Listen to translated message"
                              >
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
                                  <path d="M3 9v6h4l5 5V4l-5 5H3zm13.5 3A4.5 4.5 0 0014 7.97v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77 0-4.28-2.99-7.86-7-8.77z"/>
                                </svg>
                              </button>
                            </div>
                        )}
                      </div>
                    )}
                    {/* Отдельной строкой — у вложений и под переводом */}
                    {(!inlineTime(msg) || hasTranslation(msg)) && (
                      <div className="message-timestamp">{formatTimestamp(msg.timestamp)}</div>
                    )}
                  </div>
                </div>
              </div>
            </div>
            </React.Fragment>
          ) : null
        ))}
        <div ref={messagesEndRef} />
      </div>
      <div className="input-container">
        <div className="textarea-wrapper">
          <div className="textarea-inner">
            <span
              onClick={() => fileInputRef.current.click()}
              className="attach-icon"
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
              className="input-message"
              rows={rows}
            />
            <input
              type="file"
              onChange={handleFileChange}
              className="file-input"
              ref={fileInputRef}
              id="file-input"
            />
            <span
                onClick={handleOpenAiDialog}
                className={`group-ai-icon ${content.length > 0 || selectedFile ? 'hidden' : ''}`}
                role="button"
                aria-label="Generate message with AI"
                >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M12 2L2 7L12 12L22 7L12 2Z" fill="#6200EA"/>
                    <path d="M2 17L12 22L22 17" fill="#6200EA"/>
                    <path d="M2 12L12 17L22 12" fill="#6200EA"/>
                </svg>
            </span>
            {content.length === 0 && micIconState !== 'sending' && !selectedFile && (
              <div className="mic-icon-container">
                <span
                  onClick={isRecording ? stopRecording : startRecording}
                  className={`mic-icon ${micIconState} ${!isLoggedIn ? 'disabled' : ''}`}
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
              <div className="send-icon-container">
                <span
                  onClick={sendMessage}
                  className="send-icon"
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
            <div className="attached-file">
              <span className="attached-file-name">{selectedFile.name}</span>
              <button
                className="remove-file-button"
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
              className="context-menu"
              style={{
                  top: contextMenu.y + 'px',   // ← верх меню = низ сообщения
                  left: contextMenu.x + 'px',  // ← левый край = левый край сообщения
              }}
              onMouseLeave={() => setContextMenu({ visible: false })}
          >
              <button onClick={() => handleMenuAction('edit')}>Edit</button>
              <button onClick={() => handleMenuAction('delete')}>Delete</button>
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
            onClick={(e) => e.stopPropagation()} // чтобы клик по картинке/кнопке не закрывал
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
          callerName={incomingCall.callerName}
          avatar={incomingCall.avatar}
          callType={incomingCall.type}
          onAccept={() => acceptCall(navigate)}
          onReject={rejectCall}
        />
      )}
      {showMembers && (
        <GroupMembersDialog
          chat={chatInfo || { id: chatId, name: chatName }}
          currentUserId={currentUserId}
          allUsers={knownUsers}
          onClose={() => setShowMembers(false)}
          onChanged={reloadChatInfo}
        />
      )}
      <AiMessageGeneratorDialog
        key={openAiDialog ? "ai-dialog-fresh-group" : undefined}
        open={openAiDialog}
        onClose={() => setOpenAiDialog(false)}
        onInsert={(text) => {
            setContent(text.trim());
            toast.info('Text inserted — edit and send');
        }}
        onSendDirect={sendDirectFromAI}
        messages={messages}
      />
    </div>
  );
};

export default Messenger;