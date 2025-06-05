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
      setIsSpellCheckEnabled(localStorage.getItem(`spellcheck_enabled_${chatIdParam}`) === 'true');
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
    if ((content || selectedFile) && userId) {
       // Проверяем орфографию, если включена
      let finalContent = content;
      if (isSpellCheckEnabled) {
        const spellCheckTimestamp = localStorage.getItem(`spellcheck_enabled_timestamp_${chatId}`);
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
              content
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
                      content: message.content,
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
    if (fileInputRef.current) {
      fileInputRef.current.value = null;
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

  // Добавлено: проверка, является ли сообщение новым
  const isMessageNew = (message) => {
    if (!translateNewOnly) return true;
    const enabledTimestamp = localStorage.getItem(`translation_enabled_timestamp_${chatId}`);
    if (!enabledTimestamp) return false;
    try {
      const messageTime = new Date(message.timestamp).getTime();
      const enabledTime = new Date(enabledTimestamp).getTime();
      return messageTime > enabledTime;
    } catch (e) {
      console.error('Error parsing timestamps:', e);
      return false;
    }
  };

  // Добавлено: перевод существующих сообщений
  const translateExistingMessages = () => {
    if (!isTranslationEnabled) return;
    const queue = messages
      .filter(
        (msg) =>
          isMessageNew(msg) &&
          (!msg.translatedContent || msg.translationLanguage !== translationLanguage)
      )
      .map((msg) => ({
        ...msg,
        delivered: msg.delivered ?? msg.delivered_status, // Явно сохраняем
        read: msg.read ?? msg.read_status, // Явно сохраняем
      }));
    console.log('Translation queue:', queue); // Отладка
    setTranslationQueue(queue);
  };

  // Добавлено: обработка следующего сообщения в очереди
  const translateNextMessage = () => {
    if (translationQueue.length === 0 || isTranslating) return;
    setIsTranslating(true);
    const message = translationQueue[0];
    translateMessageFunc(message);
  };

  // Добавлено: перевод одного сообщения
  const translateMessageFunc = async (message) => {
    try {
      console.log('Translating message:', message); // Отладка: проверяем структуру сообщения
      const translatedContent = await translateMessage(message.content, translationLanguage);
      const updatedMessage = {
        ...message, // Копируем все поля
        translatedContent,
        translationLanguage,
        showOriginal: true,
        delivered: message.delivered ?? message.delivered_status, // Явно сохраняем
        read: message.read ?? message.read_status, // Явно сохраняем
      };

      console.log('Updated message after translation:', updatedMessage); // Отладка

      // Сохранение перевода локально
      localStorage.setItem(
        `translation_${chatId}_${message.id}`,
        JSON.stringify({ translatedContent, translationLanguage })
      );

      // Сохранение перевода на сервере
      await saveTranslation(message.id, translatedContent, translationLanguage);

      setMessages((prevMessages) =>
        prevMessages.map((msg) =>
          msg.id === message.id ? updatedMessage : msg
        )
      );

      setTranslationQueue((prevQueue) => prevQueue.slice(1));
      setIsTranslating(false);
    } catch (error) {
      console.error('Translation error:', error);
      setTranslationQueue((prevQueue) => prevQueue.slice(1));
      setIsTranslating(false);
    }
  };

  // Добавлено: очистка переводов
  const clearTranslations = () => {
    setTranslationQueue([]);
    setMessages((prevMessages) => {
      const hasTranslations = prevMessages.some(
        (msg) => msg.translatedContent || msg.translationLanguage
      );
      if (!hasTranslations) return prevMessages;
      // Удаляем переводы из localStorage
      prevMessages.forEach((msg) => {
        localStorage.removeItem(`translation_${chatId}_${msg.id}`);
      });
      return prevMessages.map((msg) =>
        msg.translatedContent || msg.translationLanguage
          ? {
              ...msg,
              translatedContent: null,
              translationLanguage: null,
              showOriginal: true,
            }
          : msg
      );
    });
  };

  // Добавлено: обработка сохранения настроек перевода
  const handleTranslationSettingsSave = (enabled, language, newOnly) => {
    setIsTranslationEnabled(enabled);
    setTranslationLanguage(language);
    setTranslateNewOnly(newOnly);
  };

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

  // Для переключения орфографии
  const handleSpellCheckToggle = () => {
    setIsSpellCheckEnabled((prev) => {
      const newState = !prev;
      console.log(`Toggling spell check for chatId ${chatId}: ${newState}`); // Отладка
      localStorage.setItem(`spellcheck_enabled_${chatId}`, newState);
      if (newState) {
        localStorage.setItem(`spellcheck_enabled_timestamp_${chatId}`, new Date().toISOString());
      } else {
        localStorage.removeItem(`spellcheck_enabled_timestamp_${chatId}`);
      }
      return newState;
    });
  };

  const spellCheckMessage = async (content) => {
    if (!content.trim()) return content; // Пропускаем пустые сообщения
    try {
      const response = await fetch('https://unlimitedpossibilities12.org/api/spellcheck', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: content }),
      });
      if (!response.ok) {
        throw new Error('Spell check failed');
      }
      const result = await response.text(); // Получаем текст ответа
      return result.replace(/^"|"$/g, ''); // Удаляем кавычки
    } catch (error) {
      console.error('Spell check error:', error);
      toast.error('Failed to spell check message. Sending original text.');
      return content; // Возвращаем исходный текст при ошибке
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
        <textarea
          value={content}
          onChange={(e) => setContent(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Enter your message..."
          className="private-input-message"
        />
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