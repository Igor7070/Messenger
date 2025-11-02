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
import { translateMessage, saveTranslation } from '../api/messages';
import usePrivateChatTranslationAndSpellCheck from '../hooks/usePrivateChatTranslationAndSpellCheck';
import { FFmpeg } from '@ffmpeg/ffmpeg';
import TranscriptionSettingsModal from './TranscriptionSettingsModal';

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
  const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, message: null });
  const messagesEndRef = useRef(null);
  const messageListRef = useRef(null);
  const fileInputRef = useRef(null);
  const mediaRecorderRef = useRef(null); // ДОБАВЛЕНО: Референция для MediaRecorder
  const { chatId: chatIdParam } = useParams();
  const navigate = useNavigate();
  const { userInteracted, handleInteraction } = useUserInteraction();
  const { usersStatus } = useUserStatusContext();
  const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
  const { setCurrentUserId: setNotificationUserId, setCurrentChatId } = usePrivateMessageNotification();
  const [currentUserId, setCurrentUserId] = useState(null);

  const [isTranslationEnabled, setIsTranslationEnabled] = useState(false);
  const [translationLanguage, setTranslationLanguage] = useState('auto');
  const [translationMode, setTranslationMode] = useState('selective'); // Добавляем режим перевода
  const [translationQueue, setTranslationQueue] = useState([]);
  const [isTranslating, setIsTranslating] = useState(false);
  const [showTranslationModal, setShowTranslationModal] = useState(false);

  const [isSpellCheckEnabled, setIsSpellCheckEnabled] = useState(false);

  const [isTTSEnabled, setIsTTSEnabled] = useState(false); // Состояние для включения/выключения озвучивания
  
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

  const [isTranscriptionEnabled, setIsTranscriptionEnabled] = useState(false);
  const [transcriptionMode, setTranscriptionMode] = useState('selective');
  const [showTranscriptionModal, setShowTranscriptionModal] = useState(false);

  // ДОБАВЛЕНО: Состояние для транскрибированного контента
  const [transcribedContentMap, setTranscribedContentMap] = useState({});

  // Состояние для очереди транскрипции
  const [transcriptionQueue, setTranscriptionQueue] = useState([]);
  const [isTranscribing, setIsTranscribing] = useState(false);

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

  const scrollToBottom = () => {
    if (messagesEndRef.current && messageListRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: 'smooth' });
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

      try {
        const chatData = await getChatById(chatIdParam);
        setChatName(chatData.name);
        const names = chatData.name.split('_');
        const interlocutorName = names.find((name) => name !== username);
        setDisplayChatName(`Chat with ${interlocutorName}`);
        setChatId(chatIdParam);
        setCurrentChatId(chatIdParam);

        const initialMessages = await getMessages(chatIdParam);
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
          if (savedTranslation) {
            const { translatedContent, translationLanguage } = JSON.parse(savedTranslation);
            result.translatedContent = translatedContent;
            result.translationLanguage = translationLanguage;
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
          scrollToBottom();
        }, 0); // Прокрутка после загрузки
      } catch (error) {
        console.error('Ошибка при загрузке данных о чате:', error);
        toast.error('Не удалось загрузить данные чата');
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

  useEffect(() => {
    if (!chatId) return;

    const stompClientInstance = new Client({
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
          if (isTranslationEnabled && translationMode === 'new' && isMessageNew(newMessage) && newMessage.messageType !== 'audio') {
            setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
          }
          // Транскрипция нового аудиосообщения
          if (
            isTranscriptionEnabled &&
            (transcriptionMode === 'all' || transcriptionMode === 'new') &&
            newMessage.messageType === 'audio' &&
            isMessageNew(newMessage)
          ) {
            setTranscriptionQueue((prevQueue) => [...prevQueue, newMessage]);
          }
        });
      },
      onWebSocketError: (error) => {
        console.error('WebSocket error in PrivateChat:', error);
      },
      onStompError: (frame) => {
        console.error('STOMP error in PrivateChat:', frame);
        toast.error('Ошибка протокола STOMP');
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
  }, [chatId, isTranslationEnabled, translationLanguage, translationMode, isMessageNew]); 

  useEffect(() => {
    const interval = setInterval(() => {
      if (client && client.connected && messages.length > 0) {
        const unreadMessages = messages.some((msg) => !msg.read);
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
        scrollToBottom();
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

  const markAllMessagesAsRead = (updatedMessages) => {
    console.log('markAllMessagesAsRead is working...');
    if (Array.isArray(updatedMessages)) {
      updatedMessages.forEach((msg) => {
        if (msg.sender.id !== userId && (msg.read === false || msg.read === null)) {
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
                const recipientName = chatName.split('_').find((name) => name !== username);
                console.log('recipientName:', recipientName);

                const userResponse = await fetch(
                    `https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`
                );

                if (userResponse.ok) {
                    const recipient = await userResponse.json();
                    const recipientId = recipient.id;

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
  };

  const handleRightClick = (e, message) => {
    e.preventDefault();
    setContextMenu({
      visible: true,
      x: e.pageX,
      y: e.pageY,
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

  const formatTimestamp = (timestamp) => {
    if (!timestamp) return '';
    try {
      const date = parseISO(timestamp);
      const kievDate = new Date(date.toLocaleString('en-US', { timeZone: 'Europe/Kiev' }));
      if (isToday(kievDate)) {
        return format(kievDate, 'HH:mm');
      }
      return format(kievDate, 'dd.MM.yy');
    } catch (error) {
      console.error('Error formatting timestamp:', error);
      return '';
    }
  };

  const shouldShowAvatar = (msg, index) => {
    if (msg.sender.id === userId) return false;
    if (index === 0) return true;
    const prevMessage = messages[index - 1];
    return prevMessage.sender.id !== msg.sender.id;
  };

  const isFirstMessage = (msg, index) => {
    if (msg.sender.id !== userId) return false;
    if (index === 0) return true;
    const prevMessage = messages[index - 1];
    return prevMessage.sender.id !== msg.sender.id;
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
        toast.error('Failed to play audio message');
        return {
            ...prev,
            [msgId]: {
                ...prevData,
                isReady: false,
                isPlaying: false,
                error: `Failed to load audio: ${errorMsg}`,
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
                toast.error('Failed to preload audio message');
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

  return (
    <div className="private-chat">
      <div className="private-chat-name">
        <div className="private-chat-name-mobile">{displayChatName}</div>
        <div className="private-icons-row">
          <div className="private-transcription-controls">
            <button
              className="private-transcription-button"
              onClick={() => setShowTranscriptionModal(true)}
              data-tooltip={isTranscriptionEnabled ? 'Transcription is ON' : 'Transcription is OFF'}
              style={{ color: isTranscriptionEnabled ? '#42a5f5' : '#B0BEC5' }}
            >
              🎤
            </button>
            {isTranscriptionEnabled && <span className="private-transcription-indicator">ON</span>}
            <button
              className="private-tts-button"
              onClick={handleTTSToggle}
              data-tooltip={isTTSEnabled ? 'Text-to-Speech is ON' : 'Text-to-Speech is OFF'}
              style={{ color: isTTSEnabled ? '#42a5f5' : '#B0BEC5' }}
            >
              🔊
            </button>
            {isTTSEnabled && <span className="private-tts-indicator">ON</span>}
          </div>
          <div className="private-translation-controls"> 
            <button
              className="private-spellcheck-button" 
              onClick={handleSpellCheckToggle}
              data-tooltip={isSpellCheckEnabled ? 'Spell check is ON' : 'Spell check is OFF'}
              style={{ color: isSpellCheckEnabled ? '#42a5f5' : '#B0BEC5' }}
            >
              ✍️
            </button>
            {isSpellCheckEnabled && <span className="private-spellcheck-indicator">SC</span>} 
            <button
              className="private-translation-button" 
              onClick={() => setShowTranslationModal(true)}
              data-tooltip="Toggle translation for messages"
              style={{ color: isTranslationEnabled ? '#42a5f5' : '#B0BEC5' }}
            >
              🌐
            </button>
            {isTranslationEnabled && (
              <span className="private-translation-indicator"> 
                {translationLanguage === 'auto' ? 'AUTO' : translationLanguage.toUpperCase().slice(0, 2)}
              </span>
            )}
          </div>
        </div>
        <div className="private-display-name">{displayChatName}</div>
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
      <div className="private-message-list" ref={messageListRef}>
        {messages.map((msg, index) => (
          <div
            key={msg.id}
            className={`private-message ${
              msg.sender.id === userId ? 'private-current-user' : 'private-other-user'
            } ${isFirstMessage(msg, index) ? 'first-message' : ''} ${
              msg.messageType === 'audio' ? 'private-audio-message' : ''
            }`}
            onContextMenu={(e) => handleRightClick(e, msg)}
          >
            <div className="private-message-wrapper">
              {shouldShowAvatar(msg, index) && (
                <div className="private-avatar-container">
                  {msg.sender.avatar ? (
                    <div className="private-avatar-wrapper">
                      <img
                        src={`https://unlimitedpossibilities12.org${msg.sender.avatar}`}
                        alt={`${msg.sender.username} avatar`}
                        className="private-message-avatar"
                        onClick={() => handleAvatarClick(msg.sender.id)}
                        style={{ cursor: 'pointer' }}
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
                                {audioProgress[msg.id]?.isPlaying ? '⏸' : '▶'}
                              </button>
                              <div
                                className="private-audio-progress-bar"
                                onClick={(e) => handleSeek(msg.id, e)}
                                onMouseDown={(e) => handleDragStart(msg.id, e)}
                                style={{ cursor: audioProgress[msg.id]?.duration === 0 ? 'not-allowed' : 'pointer' }}
                              >
                                <div
                                  className="private-audio-progress"
                                  style={{
                                    width: `${
                                      audioProgress[msg.id]?.duration && audioProgress[msg.id]?.isReady && audioProgress[msg.id]?.duration !== 0
                                        ? (audioProgress[msg.id].currentTime / audioProgress[msg.id].duration) * 100
                                        : 0
                                    }%`,
                                  }}
                                >
                                  <div className="private-audio-progress-thumb"></div>
                                </div>
                              </div>
                              <span className="private-audio-time">
                                {formatAudioTime(audioProgress[msg.id]?.currentTime || 0)} /{' '}
                                {formatAudioTime(audioProgress[msg.id]?.duration || 0)}
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
                        {/* ИЗМЕНЕНО: Добавлен класс tts-enabled для корректных отступов */}
                        <span
                          className={`private-message-content ${isTranslationEnabled && msg.messageType !== 'audio' ? 'translate-enabled' : ''} ${isTTSEnabled && msg.messageType !== 'audio' ? 'tts-enabled' : ''}`}
                          onClick={(e) => {
                            if (e.target.tagName === 'A') return;
                            if (msg.translatedContent) {
                              setMessages((prevMessages) =>
                                prevMessages.map((m) =>
                                  m.id === msg.id ? { ...m, showOriginal: !m.showOriginal } : m
                                )
                              );
                            }
                          }}
                          style={{ cursor: msg.translatedContent ? 'pointer' : 'default' }}
                        >
                          <span className="private-original-content">
                            {msg.showOriginal || !msg.translatedContent ? msg.content : msg.translatedContent}
                          </span>
                          {msg.translatedContent && (
                            <span className="private-translated-content">{msg.translatedContent}</span>
                          )}
                        </span>
                        {msg.fileUrl && (
                          <a
                            href={msg.fileUrl}
                            download
                            className={`private-file-link ${isTranslationEnabled && msg.messageType !== 'audio' ? 'translate-enabled' : ''} ${isTTSEnabled && msg.messageType !== 'audio' ? 'tts-enabled' : ''}`}
                          >
                            📥 Download file
                          </a>
                        )}
                      </>
                    )}
                    <div className="private-message-status">
                      {msg.sender.id === userId && (
                        <>
                          {msg.delivered && <span className="private-delivery-indicator">✔️</span>}
                          {msg.read && <span className="private-read-indicator">✔️</span>}
                        </>
                      )}
                    </div>
                    <div className="private-message-timestamp">{formatTimestamp(msg.timestamp)}</div>
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
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="#4a148c" xmlns="http://www.w3.org/2000/svg">
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
        ))}
        <div ref={messagesEndRef} />
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
          className="private-context-menu"
          style={{ top: contextMenu.y, left: contextMenu.x }}
          onMouseLeave={() => setContextMenu({ visible: false })}
        >
          <button onClick={() => handleMenuAction('edit')}>Edit</button>
          <button onClick={() => handleMenuAction('delete')}>Delete</button>
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

export default PrivateChat;