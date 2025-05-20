import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from 'react-modal';
import { toast } from 'react-toastify';
import '../styles/Home.css';
import { getMessages, createChat, createUser, loginUser } from '../api/messages';
import IncomingCallNotification from './IncomingCallNotification';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs'; // Исправлен импорт: Clientлана → Client
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
    const { setCurrentUserId: setNotificationUserId, setCurrentChatId } = usePrivateMessageNotification();

    console.log('Rendering Home, usersStatus:', usersStatus); // Отладка

    useEffect(() => {
        const handleClick = () => {
            handleInteraction();
        };
        document.addEventListener('click', handleClick);
        return () => {
            document.removeEventListener('click', handleClick);
        };
    }, [handleInteraction]);

    // Форматирование временной метки
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

    // Сокращение текста сообщения
    const truncateMessage = (message, maxLength = 30) => {
        if (!message) return 'No messages yet';
        return message.length > maxLength ? message.substring(0, maxLength) + '...' : message;
    };

    // Загрузка чатов
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
                toast.error('Failed to fetch chats', {
                    toastId: `error-fetch-chats-${Date.now()}`,
                });
            }
        } catch (error) {
            console.error('Ошибка при загрузке чатов:', error);
            toast.error('Error fetching chats', {
                toastId: `error-fetch-chats-${Date.now()}`,
            });
        }
    };

    // Вычисление количества непрочитанных сообщений
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

    // Загрузка пользователей
    const fetchUsers = async () => {
        try {
            console.log('Запрашиваем пользователей...');
            const response = await fetch('https://unlimitedpossibilities12.org/api/users');
            if (response.ok) {
                const data = await response.json();
                console.log('Пользователи загружены:', data);
                setUsers(data);
            } else {
                toast.error('Failed to fetch users', {
                    toastId: `error-fetch-users-${Date.now()}`,
                });
            }
        } catch (error) {
            console.error('Ошибка при загрузке пользователей:', error);
            toast.error('Error fetching users', {
                toastId: `error-fetch-users-${Date.now()}`,
            });
        }
    };

    // Инициализация данных
    useEffect(() => {
        const storedUserId = localStorage.getItem('userId');
        if (storedUserId) {
            const userId = Number(storedUserId);
            setCurrentUserId(userId);
            setContextUserId(userId); // Синхронизируем с CallWebSocketContext
            setNotificationUserId(userId); // Синхронизируем с PrivateMessageNotificationContext
            setIsLoggedIn(true);
        }
        fetchChats();
        fetchUsers();
        // Сбрасываем currentChatId при загрузке Home
        setCurrentChatId(null);
    }, [setContextUserId, setNotificationUserId, setCurrentChatId]);

    // Подписка на сообщения
    useEffect(() => {
        if (!currentUserId || chats.length === 0) {
            console.log('Пропускаем подписку: нет пользователя или чатов');
            return;
        }

        let isMounted = true;

        console.log('Инициализация WebSocket для сообщений, чаты:', chats, 'Timestamp:', Date.now());
        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        messageClient.current = new Client({
            webSocketFactory: () => socket,
            connectTimeout: 20000,
            onConnect: () => {
                if (!isMounted) return;
                console.log('Подключено к WebSocket для сообщений в Home.js, Timestamp:', Date.now());
                chats
                    .filter((chat) => !chat.private)
                    .forEach((chat) => {
                        console.log(`Подписываемся на /topic/chat/${chat.id}/messages, Timestamp:`, Date.now());
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
            },
        });
        messageClient.current.activate();

        return () => {
            isMounted = false;
            messageClient.current.deactivate();
            console.log('WebSocket для сообщений отключён');
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

            // Воспроизведение звука для групповых чатов
            if (messageAudioRef.current && userInteracted) {
                messageAudioRef.current.play().catch((error) => {
                    console.error('Ошибка воспроизведения звука уведомления:', error);
                });
            }
        } catch (error) {
            console.error('Ошибка обработки нового сообщения:', error);
            toast.error('Error updating messages', {
                toastId: `error-update-messages-${chatId}-${Date.now()}`,
            });
        }
    };

    const loginUserHandler = async () => {
        try {
            const user = await loginUser({ username: loginUsername, password: loginPassword });
            const userId = Number(user.id);
            localStorage.setItem('userId', String(userId));
            localStorage.setItem('username', user.username);
            setCurrentUserId(userId);
            setContextUserId(userId); // Синхронизируем с CallWebSocketContext
            setNotificationUserId(userId); // Синхронизируем с PrivateMessageNotificationContext
            setLoginUsername('');
            setLoginPassword('');
            setIsLoggedIn(true);
            initializeWebSocket(userId, user.username); // Инициализация WebSocket
            toast.success('Login successful!', {
                toastId: `success-login-${Date.now()}`,
            });
        } catch (error) {
            toast.error(error.message || 'Error logging in', {
                toastId: `error-login-${Date.now()}`,
            });
        }
    };

    const logoutUser = () => {
        disconnectWebSocket(); // Отключаем WebSocket
        localStorage.removeItem('userId');
        localStorage.removeItem('username');
        setCurrentUserId(null);
        setContextUserId(null); // Сбрасываем в CallWebSocketContext
        setNotificationUserId(null); // Сбрасываем в PrivateMessageNotificationContext
        setCurrentChatId(null); // Сбрасываем currentChatId
        setIsLoggedIn(false);
        toast.success('Logged out successfully!', {
            toastId: `success-logout-${Date.now()}`,
        });
    };

    const createChatHandler = async () => {
        if (chatName) {
            try {
                const newChat = await createChat({ name: chatName, private: false });
                setChats([...chats, { ...newChat, lastMessage: null, timestamp: null, unreadCount: 0 }]);
                setChatName('');
                toast.success('Chat created successfully!', {
                    toastId: `success-create-chat-${Date.now()}`,
                });
            } catch (error) {
                toast.error(error.message || 'Error creating chat', {
                    toastId: `error-create-chat-${Date.now()}`,
                });
            }
        } else {
            toast.error('Chat name cannot be empty', {
                toastId: `error-empty-chat-name-${Date.now()}`,
            });
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
                toast.success('User created successfully!', {
                    toastId: `success-create-user-${Date.now()}`,
                });
            } else {
                const errorMessage = await response.text();
                toast.error(errorMessage, {
                    toastId: `error-create-user-${Date.now()}`,
                });
            }
        } catch (error) {
            toast.error('Error creating user', {
                toastId: `error-create-user-${Date.now()}`,
            });
        }
    };

    const goToChat = (chatId) => {
        if (!isLoggedIn || currentUserId === null) {
            toast.error("You need to create an account or log in", {
                toastId: `error-unauthorized-chat-${chatId}-${Date.now()}`,
            });
            console.log(`Unauthorized user attempted to access group chat: ${chatId}`);
            return;
        }
        setCurrentChatId(chatId); // Устанавливаем текущий чат
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
                    <h2 className="home-users-h2">Users</h2>
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
                            console.log(`User ${user.id}: online=${userStatus.online}`); // Отладка

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
                    <h1 className="home-group-chats-h1">Group chats</h1>
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
    );
};

export default Home;