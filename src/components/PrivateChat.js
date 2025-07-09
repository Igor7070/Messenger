import React, { useEffect, useState, useRef } from 'react';
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
  const [translateNewOnly, setTranslateNewOnly] = useState(false);
  const [translationQueue, setTranslationQueue] = useState([]);
  const [isTranslating, setIsTranslating] = useState(false);
  const [showTranslationModal, setShowTranslationModal] = useState(false);

  const [isSpellCheckEnabled, setIsSpellCheckEnabled] = useState(false);
  // ДОБАВЛЕНО: Состояния для аудиозаписи
  const [isRecording, setIsRecording] = useState(false);
  const [audioBlob, setAudioBlob] = useState(null);
  const [micIconState, setMicIconState] = useState('mic'); // 'mic', 'recording', 'sending'

  // ДОБАВЛЕНО: Состояния для управления прогрессом аудио
  const [audioProgress, setAudioProgress] = useState({}); // Прогресс для каждого аудиосообщения
  const [audioDuration, setAudioDuration] = useState({}); // Длительность для каждого аудиосообщения
  const audioRefs = useRef({}); // Референции для аудиоэлементов

  // ДОБАВЛЕНО: Состояния для динамического размера текстового поля
  const [rows, setRows] = useState(1);
  const [charPerLine, setCharPerLine] = useState(50);
  const textAreaRef = useRef(null);
  
  // ДОБАВЛЕНО: использование хука
  const {
    isMessageNew,
    translateExistingMessages,
    translateNextMessage,
    translateMessageFunc,
    clearTranslations,
    handleTranslationSettingsSave,
    handleSpellCheckToggle,
    spellCheckMessage,
  } = usePrivateChatTranslationAndSpellCheck({
    chatId,
    chatIdParam,
    messages,
    setMessages,
    isTranslationEnabled,
    setIsTranslationEnabled,
    translationLanguage,
    setTranslationLanguage,
    translateNewOnly,
    setTranslateNewOnly,
    translationQueue,
    setTranslationQueue,
    isTranslating,
    setIsTranslating,
    setIsSpellCheckEnabled,
  });

  const scrollToBottom = () => {
    if (messagesEndRef.current && messageListRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  };

  const handleAvatarClick = (senderId) => {
    navigate(`/user/${senderId}`);
  };

  //useEffect(() => {
    // Сброс всех тостов и тестовый тост
    //toast.dismiss(); // Очищаем очередь тостов
    //console.log('Testing toast rendering in PrivateChat');
    //toast.info('Test toast in PrivateChat', { toastId: 'test-privatechat', autoClose: 3000 });
  //}, []);

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
          const savedTranslation = localStorage.getItem(translationKey);
          if (savedTranslation) {
            const { translatedContent, translationLanguage } = JSON.parse(savedTranslation);
            return {
              ...msg,
              delivered: msg.delivered_status,
              read: msg.read_status,
              translatedContent,
              translationLanguage,
              showOriginal: true,
            };
          }
          return {
            ...msg,
            delivered: msg.delivered_status,
            read: msg.read_status,
            showOriginal: true,
          };
        });

        setMessages(updatedMessages);
        // Загрузка настройки орфографии
        setIsSpellCheckEnabled(localStorage.getItem(`spellcheck_enabled_${chatIdParam}`) === 'true');
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
      setTranslateNewOnly(localStorage.getItem(`translate_new_only_${chatIdParam}`) === 'true');
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
      setMessages((prevMessages) =>
        prevMessages.map((msg) =>
          msg.translatedContent || msg.translationLanguage
            ? {
                ...msg,
                translatedContent: null,
                translationLanguage: null,
                showOriginal: true,
              }
            : msg
        )
      );
    }
  }, [isTranslationEnabled, messages]);

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
          // Добавлено: перевод нового сообщения
          if (isTranslationEnabled && isMessageNew(newMessage)) {
            setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
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
  }, [chatId, isTranslationEnabled, translationLanguage]); // Добавлено: isTranslationEnabled, translationLanguage

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

  // Добавлено: запуск перевода при изменении настроек
  useEffect(() => {
    if (isTranslationEnabled) {
      translateExistingMessages();
    } else {
      clearTranslations();
    }
  }, [isTranslationEnabled, translationLanguage, translateNewOnly]);

  // Добавлено: обработка очереди переводов
  useEffect(() => {
    translateNextMessage();
  }, [translationQueue, isTranslating]);

  // ДОБАВЛЕНО: Пересчет символов на строку при изменении размера окна
  useEffect(() => {
    calculateCharPerLine();
    window.addEventListener('resize', calculateCharPerLine);
    return () => window.removeEventListener('resize', calculateCharPerLine);
  }, []);

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
    console.log('Updating message:', newMessage); // Отладка
    setMessages((prevMessages) => {
      if (newMessage.content === 'Message deleted') {
        return prevMessages.filter((msg) => msg.id !== newMessage.id);
      }

      const existingMessageIndex = prevMessages.findIndex((msg) => msg.id === newMessage.id);
      if (existingMessageIndex !== -1) {
        const updatedMessages = [...prevMessages];
        updatedMessages[existingMessageIndex] = {
          ...newMessage,
          delivered: newMessage.delivered_status,
          read: newMessage.read_status,
          translatedContent: updatedMessages[existingMessageIndex].translatedContent,
          translationLanguage: updatedMessages[existingMessageIndex].translationLanguage,
          showOriginal: updatedMessages[existingMessageIndex].showOriginal,
          messageType: newMessage.messageType, // ДОБАВЛЕНО: Сохранение messageType
        };
        console.log('Updated existing message:', updatedMessages[existingMessageIndex]); // Отладка
        if (
          isTranslationEnabled &&
          isMessageNew(newMessage) &&
          (!updatedMessages[existingMessageIndex].translatedContent ||
            updatedMessages[existingMessageIndex].translationLanguage !== translationLanguage)
        ) {
          setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
        }
        return updatedMessages;
      } else {
        const newMsg = {
          ...newMessage,
          delivered: newMessage.delivered_status,
          read: newMessage.read_status,
          showOriginal: true,
          messageType: newMessage.messageType, // ДОБАВЛЕНО: Сохранение messageType
        };
        console.log('New message added:', newMsg); // Отладка
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
       // Проверяем орфографию, если включена
      let finalContent = content;
      if (isSpellCheckEnabled) {
        const spellCheckTimestamp = localStorage.getItem(`spellcheck_enabled_timestamp_${chatIdParam}`); // Используем chatIdParam
        if (spellCheckTimestamp) {
          finalContent = await spellCheckMessage(content);
        }
      }

      const message = {
        id: editingMessageId || Date.now(),
        content: finalContent, // Используем исправленный текст
        sender: { id: userId, username },
        chat: { id: chatId, name: chatName },
        fileUrl: selectedFile ? await uploadFile(selectedFile) : null,
        delivered: false,
        read: false,
        timestamp: new Date().toISOString(),
      };

      if (client && client.connected) {
        client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });

        setTimeout(async () => {
          const response = await fetch(
            `https://unlimitedpossibilities12.org/api/messages/search?content=${encodeURIComponent(
              finalContent // Используем исправленный текст
            )}&senderId=${userId}&chatId=${chatId}`
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
                console.log('updatedMessage.read:', updatedMessage.read);
                console.log('updatedMessage.read_status:', updatedMessage.read_status);
                console.log('messageStatus.read:', messageStatus.read);
                console.log('messageStatus.read_status:', messageStatus.read_status);
                console.log('userId:', userId);

                if (messageStatus.read_status === false || messageStatus.read_status === null) {
                  console.log('Notification must be sent...');
                  await fetch(`https://unlimitedpossibilities12.org/api/messages/notify`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                      userId,
                      recipientId,
                      content: finalContent, // Используем исправленный текст
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
            console.error('Error while searching for message');
          }
        }, 3000);

        resetForm();
      } else {
        console.error('STOMP client is not connected');
        toast.error('Failed to send message: no connection');
      }
    } else {
      toast.warn('Enter a message or select a file');
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  const resetForm = () => {
    setContent('');
    setSelectedFile(null);
    setEditingMessageId(null);
    setRows(1); // ДОБАВЛЕНО: Сброс количества строк
    if (fileInputRef.current) {
      fileInputRef.current.value = null;
    }
    if (textAreaRef.current) {
      textAreaRef.current.style.height = '44px'; // ДОБАВЛЕНО: Сброс высоты textarea
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

  // ДОБАВЛЕНО: Метод для начала записи аудио
  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mediaRecorder = new MediaRecorder(stream, { mimeType: 'audio/webm' });
      mediaRecorderRef.current = mediaRecorder;
      const chunks = [];

      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) {
          chunks.push(e.data);
        }
      };

      mediaRecorder.onstop = () => {
        const blob = new Blob(chunks, { type: 'audio/webm' });
        setAudioBlob(blob);
        stream.getTracks().forEach(track => track.stop());
        setMicIconState('sending');
        sendAudioMessage(blob);
      };

      setMicIconState('recording');
      mediaRecorder.start();
      setIsRecording(true);
      toast.info('Recording started');
    } catch (error) {
      console.error('Error starting recording:', error);
      toast.error('Failed to start recording');
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

  // ДОБАВЛЕНО: Метод для отправки аудиосообщения
  const sendAudioMessage = async (audioBlob) => {
    if (!audioBlob || !userId) {
      console.warn('Audio or userId is missing');
      toast.error('Failed to send audio message');
      setMicIconState('mic');
      return;
    }

    const message = {
      id: Date.now(),
      content: '',
      sender: { id: userId, username },
      chat: { id: chatId, name: chatName },
      fileUrl: await uploadFile(audioBlob),
      delivered: false,
      read: false,
      timestamp: new Date().toISOString(),
      messageType: 'audio',
    };

    console.log('Sending audio message:', message);

    if (client && client.connected) {
      client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
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
    const newRows = Math.min(5, calculateRows(newValue)); // Ограничиваем до 5 строк
    setRows(newRows);
    const lineHeight = window.innerWidth <= 800 ? 20 : 24; // 20px для мобильных, 24px для десктопа
    textAreaRef.current.style.height = `${newRows * lineHeight + 20}px`; // Учитываем padding (10px + 10px)
  };

  // ДОБАВЛЕНО: Форматирование времени (мм:сс)
  const formatTime = (seconds) => {
    const minutes = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${minutes}:${secs < 10 ? '0' : ''}${secs}`;
  };

  // ДОБАВЛЕНО: Обработчик обновления прогресса
  const handleTimeUpdate = (messageId, audioElement) => {
    const progress = (audioElement.currentTime / audioElement.duration) * 100;
    setAudioProgress((prev) => ({ ...prev, [messageId]: progress }));
  };

  // ДОБАВЛЕНО: Обработчик загрузки метаданных
  const handleLoadedMetadata = (messageId, audioElement) => {
    setAudioDuration((prev) => ({ ...prev, [messageId]: audioElement.duration }));
  };

  // ДОБАВЛЕНО: Обработчик клика по прогресс-бару
  const handleProgressClick = (messageId, e) => {
    const progressBar = e.target;
    const rect = progressBar.getBoundingClientRect();
    const clickX = e.clientX - rect.left;
    const percentage = (clickX / rect.width) * 100;
    const audio = audioRefs.current[messageId];
    if (audio) {
      const newTime = (percentage / 100) * audio.duration;
      audio.currentTime = newTime;
      setAudioProgress((prev) => ({ ...prev, [messageId]: percentage }));
    }
  };

  return (
    <div className="private-chat">
      <div className="private-chat-name">
        {displayChatName}
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
            data-tooltip="Toggle translation for messages" // Подсказка
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
            } ${isFirstMessage(msg, index) ? 'first-message' : ''}`}
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
                    {msg.messageType === 'audio' && msg.fileUrl ? (
                      <div className="private-audio-message-container">
                        <div className="private-custom-audio-player">
                          <button
                            className="private-audio-control"
                            onClick={() => {
                              const audio = audioRefs.current[msg.id];
                              if (audio) {
                                if (audio.paused) {
                                  audio.play();
                                } else {
                                  audio.pause();
                                }
                              }
                            }}
                          >
                            {audioRefs.current[msg.id]?.paused !== false ? '▶️' : '⏸️'}
                          </button>
                          <div
                            className="private-progress-bar"
                            onClick={(e) => handleProgressClick(msg.id, e)}
                          >
                            <div
                              className="private-progress"
                              style={{ width: `${audioProgress[msg.id] || 0}%` }}
                            ></div>
                          </div>
                          <span className="private-audio-time">
                            {formatTime(audioRefs.current[msg.id]?.currentTime || 0)} /{' '}
                            {formatTime(audioDuration[msg.id] || 0)}
                          </span>
                        </div>
                        <audio
                          ref={(el) => (audioRefs.current[msg.id] = el)}
                          onTimeUpdate={(e) => handleTimeUpdate(msg.id, e.target)}
                          onLoadedMetadata={(e) => handleLoadedMetadata(msg.id, e.target)}
                        >
                          <source src={msg.fileUrl} type="audio/webm" />
                        </audio>
                      </div>
                    ) : (
                      <>
                        <span
                          className="private-message-content"
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
                          <a href={msg.fileUrl} download className="private-file-link">
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
              </div>
            </div>
          </div>
        ))}
        <div ref={messagesEndRef} />
      </div>
      <div className="private-input-container">
        <div className="private-textarea-wrapper">
          <textarea
            ref={textAreaRef}
            value={content}
            onChange={handleInputChange}
            onKeyDown={handleKeyDown}
            placeholder="Enter your message..."
            className="private-input-message"
            rows={rows}
          />
          {content.length === 0 && micIconState !== 'sending' && (
            <div className="private-mic-icon-container">
              <span
                onClick={isRecording ? stopRecording : startRecording}
                className={`private-mic-icon ${micIconState} ${!userId ? 'disabled' : ''}`}
                role="button"
                aria-label={micIconState === 'mic' ? 'Start Recording' : micIconState === 'recording' ? 'Stop Recording' : 'Sending Audio'}
              >
                {micIconState === 'mic' && <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z" fill="#757575"/>
                  <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z" fill="#757575"/>
                </svg>}
                {micIconState === 'recording' && <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <circle cx="12" cy="12" r="8" fill="#FF0000"/>
                </svg>}
                {micIconState === 'sending' && <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" fill="#0288D1"/>
                </svg>}
              </span>
            </div>
          )}
        </div>
        <div className="private-button-container">
          <input
            type="file"
            onChange={(e) => setSelectedFile(e.target.files[0])}
            className="private-file-input"
            ref={fileInputRef}
            id="private-file-input"
          />
          <label htmlFor="private-file-input" className="private-file-input-label">
            Choose File
          </label>
          <button onClick={sendMessage} className="private-send-button">
            Send
          </button>
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