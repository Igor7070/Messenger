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
import { useAssistant } from '../context/AssistantContext';
import AssistantChatWindow from '../components/AssistantChatWindow';
import AssistantIcon from './AssistantIcon';
import { format, isToday, parseISO } from 'date-fns';
import sodium from 'libsodium-wrappers';

const KEYSTORE_ALIAS = 'secret_chat_key';

const generateAndSaveKeys = async (userId) => {
  try {
    console.log(`Starting key generation for userId: ${userId}`);
    await sodium.ready;
    const alias = `${KEYSTORE_ALIAS}_${userId}`;
    const existingKey = localStorage.getItem(alias);
    console.log(`Existing private key for ${alias}: ${existingKey ? 'found' : 'not found'}`);
    if (!existingKey) {
      console.log('Generating new key pair...');
      const { publicKey, privateKey } = sodium.crypto_box_keypair();
      const privateKeyBase64 = sodium.to_base64(privateKey);
      const publicKeyBase64 = sodium.to_base64(publicKey).trim(); // Удаляем лишние пробелы
      console.log(`Generated keys - Public: ${publicKeyBase64}, Private: ${privateKeyBase64}`);
      localStorage.setItem(alias, privateKeyBase64);
      console.log(`Private key saved to localStorage for ${alias}`);
      await sendPublicKeyToServer(userId, publicKeyBase64);
      console.log(`Keys generated and saved for userId: ${userId}`);
    } else {
      console.log(`Private key already exists for userId: ${userId}, skipping generation`);
      // Проверяем, есть ли публичный ключ на сервере
      try {
        const response = await fetch(`https://unlimitedpossibilities12.org/api/public_keys/${userId}`);
        if (!response.ok) {
          console.log(`Public key not found for userId: ${userId}, re-uploading...`);
          const storedKeyPair = JSON.parse(localStorage.getItem(`keyPair_${userId}`));
          if (storedKeyPair?.publicKey) {
            await sendPublicKeyToServer(userId, storedKeyPair.publicKey);
          }
        }
      } catch (error) {
        console.error(`Error checking public key for userId: ${userId}`, error);
      }
    }
  } catch (error) {
    console.error('Error generating keys:', error);
    toast.error('Failed to initialize encryption keys');
  }
};

const sendPublicKeyToServer = async (userId, publicKey) => {
  try {
    console.log(`Uploading public key for userId: ${userId}, key: ${publicKey}`);
    const response = await fetch(`https://unlimitedpossibilities12.org/api/public_keys`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ userId, publicKey }),
    });
    const responseText = await response.text();
    console.log(`Public key upload response: status=${response.status}, statusText=${response.statusText}, body=${responseText}`);
    if (!response.ok) {
      console.error(`Failed to upload public key: ${responseText}`);
      throw new Error(`Failed to upload public key: ${response.status} - ${responseText}`);
    }
    console.log(`Public key uploaded successfully for userId: ${userId}`);
  } catch (error) {
    console.error('Error uploading public key:', error);
    toast.error(`Failed to upload public key: ${error.message}`);
  }
};

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
  const [activeTab, setActiveTab] = useState('users'); // 'users' или 'chats'
  const messageClient = useRef(null);
  const navigate = useNavigate();
  const { userInteracted, handleInteraction } = useUserInteraction();
  const { usersStatus, initializeWebSocket, disconnectWebSocket, sendOnlineStatus } = useUserStatusContext();
  const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
  const { setCurrentUserId: setNotificationUserId } = usePrivateMessageNotification();
  const { openAssistant } = useAssistant();  // ← это позволит открыть окно по клику

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

  //useEffect(() => {
    // Сброс всех тостов и тестовый тост
    //toast.dismiss(); // Очищаем очередь тостов
    //console.log('Testing toast rendering in Home');
    //toast.info('Test toast in Home', { toastId: 'test-home', autoClose: 3000 });
  //}, []);

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

  const truncateMessage = (message, maxLength = 30) => {
    if (!message) return 'No messages yet';
    return message.length > maxLength ? message.substring(0, maxLength) + '...' : message;
  };

  const fetchChats = async () => {
    try {
      console.log('Fetching chats...');
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
        console.log('Chats loaded:', chatsWithMessages);
        setChats(chatsWithMessages);
      } else {
        toast.error('Failed to fetch chats');
      }
    } catch (error) {
      console.error('Error fetching chats:', error);
      toast.error('Error fetching chats');
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
      console.error('Error parsing last visit time:', error);
      return 0;
    }
  };

  const fetchUsers = async () => {
    try {
      console.log('Fetching users...');
      const response = await fetch('https://unlimitedpossibilities12.org/api/users');
      if (response.ok) {
        const data = await response.json();
        console.log('Users loaded:', data);
        setUsers(data);
      } else {
        toast.error('Failed to fetch users');
      }
    } catch (error) {
      console.error('Error fetching users:', error);
      toast.error('Error fetching users');
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
      console.log('Skipping subscription: no user or chats');
      return;
    }

    let isMounted = true;

    console.log('Initializing WebSocket for messages, chats:', chats);
    const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
    messageClient.current = new Client({
      webSocketFactory: () => socket,
      connectTimeout: 30000,
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        if (!isMounted) return;
        console.log('Connected to WebSocket for messages in Home.js');
        chats
          .filter((chat) => !chat.private)
          .forEach((chat) => {
            console.log(`Subscribing to /topic/chat/${chat.id}/messages`);
            messageClient.current.subscribe(`/topic/chat/${chat.id}/messages`, (message) => {
              console.log(`Received message for chat ${chat.id}:`, message.body);
              const newMessage = JSON.parse(message.body);
              handleNewMessage(newMessage, chat.id);
            });
          });
      },
      onWebSocketError: (error) => {
        console.error('WebSocket error for messages:', error);
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame);
        toast.error('STOMP protocol error');
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
    console.log('Handling new message:', message, 'for chatId:', chatId);
    try {
      const chat = chats.find((c) => c.id === chatId);
      if (!chat) {
        console.log('Chat not found in chats state:', chatId, 'Chats:', chats);
        return;
      }
      if (chat.private) {
        console.log('Message for private chat, ignoring:', chatId);
        return;
      }

      const messages = await getMessages(chatId);
      console.log('Loaded messages for chat', chatId, messages);
      const lastMessage = messages.length > 0 ? messages[messages.length - 1] : null;
      const unreadCount = calculateUnreadCount(messages, chatId);
      console.log('Calculated unreadCount:', unreadCount);

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
        console.log('Updated chats:', updatedChats);
        return [...updatedChats];
      });
    } catch (error) {
      console.error('Error handling new message:', error);
      toast.error('Error updating messages');
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
      await generateAndSaveKeys(userId); // Добавлено генерация ключей
      initializeWebSocket(userId, user.username);
      sendOnlineStatus();
      toast.success('Login successful!');
    } catch (error) {
      toast.error(error.message || 'Error logging in');
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
    toast.success('Logged out successfully!');
  };

  const createChatHandler = async () => {
    if (chatName) {
      try {
        const newChat = await createChat({ name: chatName, private: false });
        setChats([...chats, { ...newChat, lastMessage: null, timestamp: null, unreadCount: 0 }]);
        setChatName('');
        toast.success('Chat created successfully!');
      } catch (error) {
        toast.error(error.message || 'Error creating chat');
      }
    } else {
      toast.error('Chat name cannot be empty');
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
        await generateAndSaveKeys(newUser.id); // Добавлено
        toast.success('User created successfully!');
      } else {
        const errorMessage = await response.text();
        toast.error(errorMessage);
      }
    } catch (error) {
      toast.error('Error creating user');
    }
  };

  const goToChat = (chatId) => {
    /*if (!isLoggedIn || currentUserId === null) {
      toast.error('You need to create an account or log in');
      console.log(`Unauthorized user attempted to access group chat: ${chatId}`);
      return;
    }*/
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
  .filter((chat) => !chat.private && !chat.isSecret) // Только публичные групповые
  .filter((chat) => 
    typeof chat.name === 'string' && 
    chat.name.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const filteredUsers = users.filter((user) =>
    user.username.toLowerCase().includes(userSearchTerm.toLowerCase())
  );

  return (
    <>
    <div className="home-container">
      <div className="login-container-home">
        {isLoggedIn ? (
          <button onClick={logoutUser} className="logout-button-home">
            Logout
          </button>
        ) : (
          <>
            <input
              type="text"
              value={loginUsername}
              onChange={(e) => setLoginUsername(e.target.value)}
              placeholder="Username"
              className="login-input-home"
            />
            <input
              type="password"
              value={loginPassword}
              onChange={(e) => setLoginPassword(e.target.value)}
              placeholder="Password"
              className="login-input-home"
            />
            <button onClick={loginUserHandler} className="login-button-home">
              Login
            </button>
          </>
        )}
      </div>

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

      {/* === ВКЛАДКИ ТОЛЬКО НА ТЕЛЕФОНЕ === */}
      <div className="tab-buttons-mobile">
        <button
          className={`tab-button ${activeTab === 'users' ? 'active' : ''}`}
          onClick={() => setActiveTab('users')}
        >
          Users
        </button>
        <button
          className={`tab-button ${activeTab === 'chats' ? 'active' : ''}`}
          onClick={() => setActiveTab('chats')}
        >
          Group Chats
        </button>
      </div>

      {/* === ОСНОВНОЙ КОНТЕНТ === */}
      <div className="line-divider-container">
        {/* === USERS === */}
        <div className={`users-container ${activeTab === 'users' ? 'tab-active' : ''}`}>
          {/* Заголовок — только на ПК */}
          <h2 className="home-users-h2 desktop-only">Users</h2>

          <button type="button" onClick={openModal} className="create-user-button-main">
            Create a new user
          </button>
          <input
            type="text"
            value={userSearchTerm}
            onChange={(e) => setUserSearchTerm(e.target.value)}
            placeholder="Search user"
            className="search-input"
          />
          <ul className="user-list">
            {filteredUsers.map((user) => {
              const avatarSrc = user.avatar
                ? `https://unlimitedpossibilities12.org${user.avatar}`
                : null;
              const initials = user.username ? user.username.charAt(0).toUpperCase() : '';
              const userStatus = usersStatus[user.id] || { online: false };

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
                    <span className="status-indicator-online-home"></span>
                  )}
                </li>
              );
            })}
          </ul>
        </div>

        <div className="line-divider"></div>

        {/* === GROUP CHATS === */}
        <div className={`chats-container ${activeTab === 'chats' ? 'tab-active' : ''}`}>
          {/* Заголовок — только на ПК */}
          <h1 className="home-group-chats-h1 desktop-only">Group chats</h1>

          <div className="chat-input-container">
            <input
              type="text"
              value={chatName}
              onChange={(e) => setChatName(e.target.value)}
              placeholder="Enter the chat name"
              className="chat-input"
            />
            <button onClick={createChatHandler} className="create-chat-button">
              Create a group chat
            </button>
          </div>
          <input
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Search chats..."
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

      {/* === МОДАЛКА === */}
      <Modal
        isOpen={modalIsOpen}
        onRequestClose={() => setModalIsOpen(false)}
        className="modal"
        overlayClassName="modal-overlay"
      >
        <h2 className="modal-title">Create User</h2>
        <input
          type="text"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="Enter username"
          className="modal-input"
          required
        />
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="Enter password"
          className="modal-input"
          required
        />
        <textarea
          value={bio}
          onChange={(e) => setBio(e.target.value)}
          placeholder="Tell us about yourself (optional)"
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
          Create User
        </button>
        <button onClick={() => setModalIsOpen(false)} className="modal-close-button">
          Close
        </button>
      </Modal>
    </div>
    {/* Иконка ассистента — теперь открывает окно */}
    {window.innerWidth <= 768 ? (
      <div className="assistant-icon-mobile">
        <AssistantIcon size="large" onClick={() => {/* твой обработчик открытия диалога */}} />
      </div>
    ) : (
      <AssistantIcon size="large" position="top-right" onClick={() => {/* твой обработчик */}} />
    )}
    </>
  );
};

export default Home;