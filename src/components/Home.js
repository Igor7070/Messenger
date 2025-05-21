import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from 'react-modal';
import { toast } from 'react-toastify';
import '../styles/Home.css';
import { getMessages, createChat, createUser, loginUser } from '../api/messages';
import IncomingCallNotification from './IncomingCallNotification';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { useUserInteraction } from '../context/UserInteractionContext';
import { useUserStatusContext } from '../context/UserStatusContext';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import { usePrivateMessageNotification } from '../context/PrivateMessageNotificationContext';
import { format, isToday, parseISO } from 'date-fns';

const Home = () => {
  const [chatName, setChatName] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [userSearchTerm, setUserSearchTerm] = useState('');
  const [chats, setChats] = useState([]);
  const [users, setUsers] = useState([]);
  const [modalIsOpen, setModalIsOpen] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [bio, setBio] = useState('');
  const [avatar, setAvatar] = useState(null);
  const [currentUserId, setCurrentUserId] = useState(null);
  const [loginUsername, setLoginUsername] = useState('');
  const [loginPassword, setLoginPassword] = useState('');
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const messageClient = useRef(null);
  const messageAudioRef = useRef(null);
  const navigate = useNavigate();
  const { userInteracted, handleInteraction } = useUserInteraction();
  const { usersStatus, initializeWebSocket, disconnectWebSocket } = useUserStatusContext();
  const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
  const { setCurrentUserId: setNotificationUserId } = usePrivateMessageNotification();

  console.log('Rendering Home, usersStatus:', usersStatus);

  useEffect(() => {
    const handleClick = () => {
      handleInteraction();
    };
    document.addEventListener('click', handleClick);
    return () => {
      document.removeEventListener('click', handleClick);
    };
  }, [handleInteraction]);

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
      console.error('Ошибка форматирования временной метки:', error);
      return '';
    }
  };

  const truncateMessage = (message, maxLength = 30) => {
    if (!message) return 'No messages yet';
    return message.length > maxLength ? message.substring(0, maxLength) + '...' : message;
  };

  const fetchChats = async () => {
    try {
      console.log('Запрашиваем чаты...');
      const response = await fetch('https://unlimitedpossibilities12.org/api/chats');
      if (response.ok) {
        const data = await response.json();
        const chatsWithMessages = await Promise.all(
          data.map(async (chat) => {
            const messages = await getMessages(chat.id);
            const lastMessage = messages.length > 0 ? messages[messages.length - 1] : null;
            const unreadCount = calculateUnreadCount(messages, chat.id);
            return {
              ...chat,
              lastMessage: lastMessage ? lastMessage.content : null,
              timestamp: lastMessage ? lastMessage.timestamp : null,
              unreadCount,
            };
          })
        );
        console.log('Чаты загружены:', chatsWithMessages);
        setChats(chatsWithMessages);
      } else {
        toast.error('Не удалось загрузить чаты');
      }
    } catch (error) {
      console.error('Ошибка при загрузке чатов:', error);
      toast.error('Ошибка загрузки чатов');
    }
  };

  const calculateUnreadCount = (messages, chatId) => {
    const lastVisit = localStorage.getItem(`last_visit_chat_${chatId}`);
    if (!lastVisit) {
      return messages.length;
    }
    try {
      const lastVisitTime = new Date(lastVisit).getTime();
      return messages.filter((msg) => new Date(msg.timestamp).getTime() > lastVisitTime).length;
    } catch (error) {
      console.error('Ошибка при разборе времени последнего визита:', error);
      return 0;
    }
  };

  const fetchUsers = async () => {
    try {
      console.log('Запрашиваем пользователей...');
      const response = await fetch('https://unlimitedpossibilities12.org/api/users');
      if (response.ok) {
        const data = await response.json();
        console.log('Пользователи загружены:', data);
        setUsers(data);
      } else {
        toast.error('Не удалось загрузить пользователей');
      }
    } catch (error) {
      console.error('Ошибка при загрузке пользователей:', error);
      toast.error('Ошибка загрузки пользователей');
    }
  };

  useEffect(() => {
    console.log('Home mounted, initializing data');
    const storedUserId = localStorage.getItem('userId');
    if (storedUserId) {
      const userId = Number(storedUserId);
      setCurrentUserId(userId);
      setContextUserId(userId);
      setNotificationUserId(userId);
      setIsLoggedIn(true);
    }
    fetchChats();
    fetchUsers();
  }, [setContextUserId, setNotificationUserId]);

  useEffect(() => {
    if (!currentUserId || chats.length === 0) {
      console.log('Пропускаем подписку: нет пользователя или чатов');
      return;
    }

    let isMounted = true;

    console.log('Инициализация WebSocket для сообщений, чаты:', chats);
    const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
    messageClient.current = new Client({
      webSocketFactory: () => socket,
      connectTimeout: 30000,
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        if (!isMounted) return;
        console.log('Подключено к WebSocket для сообщений в Home.js');
        chats
          .filter((chat) => !chat.private)
          .forEach((chat) => {
            console.log(`Подписываемся на /topic/chat/${chat.id}/messages`);
            messageClient.current.subscribe(`/topic/chat/${chat.id}/messages`, (message) => {
              console.log(`Получено сообщение для чата ${chat.id}:`, message.body);
              const newMessage = JSON.parse(message.body);
              handleNewMessage(newMessage, chat.id);
            });
          });
      },
      onWebSocketError: (error) => {
        console.error('Ошибка WebSocket для сообщений:', error);
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame);
        toast.error('Ошибка протокола STOMP');
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected in Home');
      },
    });
    messageClient.current.activate();

    return () => {
      isMounted = false;
      console.log('Deactivating WebSocket in Home');
      if (messageClient.current) {
        messageClient.current.deactivate();
      }
    };
  }, [currentUserId, chats]);

  const handleNewMessage = async (message, chatId) => {
    console.log('Обработка нового сообщения:', message, 'для chatId:', chatId);
    try {
      const chat = chats.find((c) => c.id === chatId);
      if (!chat) {
        console.log('Чат не найден в состоянии chats:', chatId, 'Chats:', chats);
        return;
      }
      if (chat.private) {
        console.log('Сообщение для приватного чата, игнорируем:', chatId);
        return;
      }

      const messages = await getMessages(chatId);
      console.log('Загружены сообщения для чата', chatId, messages);
      const lastMessage = messages.length > 0 ? messages[messages.length - 1] : null;
      const unreadCount = calculateUnreadCount(messages, chatId);
      console.log('Рассчитан unreadCount:', unreadCount);

      setChats((prevChats) => {
        const updatedChats = prevChats.map((chat) =>
          chat.id === chatId
            ? {
                ...chat,
                lastMessage: lastMessage ? lastMessage.content : null,
                timestamp: lastMessage ? lastMessage.timestamp : null,
                unreadCount,
              }
            : chat
        );
        console.log('Обновлены чаты:', updatedChats);
        return [...updatedChats];
      });
    } catch (error) {
      console.error('Ошибка обработки нового сообщения:', error);
      toast.error('Ошибка обновления сообщений');
    }
  };

  const loginUserHandler = async () => {
    try {
      const user = await loginUser({ username: loginUsername, password: loginPassword });
      const userId = Number(user.id);
      localStorage.setItem('userId', String(userId));
      localStorage.setItem('username', user.username);
      setCurrentUserId(userId);
      setContextUserId(userId);
      setNotificationUserId(userId);
      setLoginUsername('');
      setLoginPassword('');
      setIsLoggedIn(true);
      initializeWebSocket(userId, user.username);
      toast.success('Вход выполнен успешно!');
    } catch (error) {
      toast.error(error.message || 'Ошибка входа');
    }
  };

  const logoutUser = () => {
    disconnectWebSocket();
    localStorage.removeItem('userId');
    localStorage.removeItem('username');
    setCurrentUserId(null);
    setContextUserId(null);
    setNotificationUserId(null);
    setIsLoggedIn(false);
    toast.success('Выход выполнен успешно!');
  };

  const createChatHandler = async () => {
    if (chatName) {
      try {
        const newChat = await createChat({ name: chatName, private: false });
        setChats([...chats, { ...newChat, lastMessage: null, timestamp: null, unreadCount: 0 }]);
        setChatName('');
        toast.success('Чат успешно создан!');
      } catch (error) {
        toast.error(error.message || 'Ошибка создания чата');
      }
    } else {
      toast.error('Название чата не может быть пустым');
    }
  };

  const createUserHandler = async () => {
    const formData = new FormData();
    formData.append('username', username);
    formData.append('password', password);
    formData.append('bio', bio);
    if (avatar) {
      formData.append('avatar', avatar);
    }

    try {
      const response = await fetch('https://unlimitedpossibilities12.org/api/users/create', {
        method: 'POST',
        body: formData,
      });

      if (response.ok) {
        const newUser = await response.json();
        setUsers([...users, newUser]);
        setModalIsOpen(false);
        setUsername('');
        setPassword('');
        setBio('');
        setAvatar(null);
        toast.success('Пользователь успешно создан!');
      } else {
        const errorMessage = await response.text();
        toast.error(errorMessage);
      }
    } catch (error) {
      toast.error('Ошибка создания пользователя');
    }
  };

  const goToChat = (chatId) => {
    if (!isLoggedIn || currentUserId === null) {
      toast.error('Необходимо создать аккаунт или войти');
      console.log(`Unauthorized user attempted to access group chat: ${chatId}`);
      return;
    }
    navigate(`/chat/${chatId}`);
  };

  const openModal = () => {
    setUsername('');
    setPassword('');
    setBio('');
    setAvatar(null);
    setModalIsOpen(true);
  };

  const filteredChats = chats
    .filter((chat) => !chat.private)
    .filter((chat) => chat.name.toLowerCase().includes(searchTerm.toLowerCase()));

  const filteredUsers = users.filter((user) =>
    user.username.toLowerCase().includes(userSearchTerm.toLowerCase())
  );

  return (
    <div className="home-container">
      <div className="login-container-home">
        {isLoggedIn ? (
          <button onClick={logoutUser} className="logout-button-home">
            Выйти
          </button>
        ) : (
          <>
            <input
              type="text"
              value={loginUsername}
              onChange={(e) => setLoginUsername(e.target.value)}
              placeholder="Имя пользователя"
              className="login-input-home"
            />
            <input
              type="password"
              value={loginPassword}
              onChange={(e) => setLoginPassword(e.target.value)}
              placeholder="Пароль"
              className="login-input-home"
            />
            <button onClick={loginUserHandler} className="login-button-home">
              Войти
            </button>
          </>
        )}
      </div>

      <audio ref={messageAudioRef}>
        <source src="https://igor7070.github.io/Messenger/zvuk-dlya-soobsen.mp3" type="audio/mpeg" />
        Ваш браузер не поддерживает аудио.
      </audio>

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

      <div className="line-divider-container">
        <div className="users-container">
          <h2 className="home-users-h2">Пользователи</h2>
          <button type="button" onClick={openModal} className="create-user-button-main">
            Создать нового пользователя
          </button>
          <input
            type="text"
            value={userSearchTerm}
            onChange={(e) => setUserSearchTerm(e.target.value)}
            placeholder="Поиск пользователя"
            className="search-input"
          />
          <ul className="user-list">
            {filteredUsers.map((user) => {
              const avatarSrc = user.avatar
                ? `https://unlimitedpossibilities12.org${user.avatar}`
                : null;
              const initials = user.username ? user.username.charAt(0).toUpperCase() : '';
              const userStatus = usersStatus[user.id] || { online: false };
              console.log(`User ${user.id}: online=${userStatus.online}`);

              return (
                <li
                  key={user.id}
                  className="user-item"
                  onClick={() => navigate(`/user/${user.id}`)}
                >
                  {avatarSrc ? (
                    <img
                      src={avatarSrc}
                      alt={`${user.username} avatar`}
                      className="user-avatar"
                    />
                  ) : (
                    <div className="user-avatar">{initials}</div>
                  )}
                  <span className="user-name">{user.username}</span>
                  {userStatus.online && (
                    <span
                      className="status-indicator-online-home"
                      style={{ backgroundColor: '#4CAF50' }}
                    ></span>
                  )}
                </li>
              );
            })}
          </ul>
        </div>

        <div className="line-divider"></div>

        <div className="chats-container">
          <h1 className="home-group-chats-h1">Групповые чаты</h1>
          <div className="chat-input-container">
            <input
              type="text"
              value={chatName}
              onChange={(e) => setChatName(e.target.value)}
              placeholder="Введите название чата"
              className="chat-input"
            />
            <button onClick={createChatHandler} className="create-chat-button">
              Создать групповой чат
            </button>
          </div>
          <input
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Поиск чатов..."
            className="search-input"
          />
          <ul className="chat-list">
            {filteredChats.map((chat) => (
              <li key={chat.id} onClick={() => goToChat(chat.id)} className="chat-item">
                <div className="chat-info">
                  <div className="chat-header">
                    <span className="chat-name-group">{chat.name}</span>
                    {chat.unreadCount > 0 && (
                      <span className="unread-count">{chat.unreadCount}</span>
                    )}
                  </div>
                  <div className="chat-footer">
                    <span className="chat-last-message">{truncateMessage(chat.lastMessage)}</span>
                    <span className="chat-timestamp">{formatTimestamp(chat.timestamp)}</span>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      </div>

      <Modal
        isOpen={modalIsOpen}
        onRequestClose={() => setModalIsOpen(false)}
        className="modal"
        overlayClassName="modal-overlay"
      >
        <h2 className="modal-title">Создать пользователя</h2>
        <input
          type="text"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="Введите имя пользователя"
          className="modal-input"
          required
        />
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="Введите пароль"
          className="modal-input"
          required
        />
        <textarea
          value={bio}
          onChange={(e) => setBio(e.target.value)}
          placeholder="Расскажите о себе (необязательно)"
          rows={3}
          className="modal-textarea"
          maxLength={244}
        />
        <input
          type="file"
          onChange={(e) => setAvatar(e.target.files[0])}
          className="modal-file-input"
        />
        <button type="button" onClick={createUserHandler} className="modal-create-button">
          Создать пользователя
        </button>
        <button onClick={() => setModalIsOpen(false)} className="modal-close-button">
          Закрыть
        </button>
      </Modal>
    </div>
  );
};

export default Home;