import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from 'react-modal';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import '../styles/Home.css';
import useWebSocket from '../hooks/useWebSocket';
import IncomingCallNotification from './IncomingCallNotification';
import { useUserInteraction } from './UserInteractionContext';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { getMessages, createChat, createUser, loginUser } from '../api/messages';
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
    const [incomingCall, setIncomingCall] = useState(null);
    const [lastCallRequest, setLastCallRequest] = useState(null);
    const [isCallActive, setIsCallActive] = useState(false);
    const { userInteracted, handleInteraction } = useUserInteraction();
    const stompClient = useRef(null);
    const messageClient = useRef(null);
    const audioRef = useRef(null);
    const messageAudioRef = useRef(null);
    const navigate = useNavigate();

    useWebSocket(currentUserId, messageAudioRef);

    const logWithTimestamp = (message, data) => {
        console.log(`[${new Date().toISOString()}] ${message}`, data);
    };

    const setIncomingCallWithLog = (value) => {
        logWithTimestamp('Вызов setIncomingCall:', value);
        logWithTimestamp('Текущее incomingCall:', incomingCall);
        logWithTimestamp('Стек вызовов:', new Error().stack);
        setIncomingCall(value);
    };

    const setLastCallRequestWithLog = (value) => {
        logWithTimestamp('Вызов setLastCallRequest:', value);
        logWithTimestamp('Текущее lastCallRequest:', lastCallRequest);
        logWithTimestamp('Стек вызовов:', new Error().stack);
        setLastCallRequest(value);
    };

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
        if (incomingCall && userInteracted) {
            logWithTimestamp('Попытка воспроизведения звука, userInteracted:', userInteracted);
            if (!audioRef.current.src) {
                audioRef.current.src = 'https://igor7070.github.io/Messenger/melodiya_kovboy.mp3';
                logWithTimestamp('audioRef.current.src установлен:', audioRef.current.src);
            }
            audioRef.current.play().catch(error => {
                logWithTimestamp('Автовоспроизведение заблокировано:', error);
                toast.warn('Please interact with the page to enable call audio.');
            });
        } else {
            logWithTimestamp('Звук не воспроизводится, incomingCall:', incomingCall, 'userInteracted:', userInteracted);
            if (audioRef.current) {
                audioRef.current.pause();
                audioRef.current.currentTime = 0;
            }
        }
    }, [incomingCall, userInteracted]);

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
            logWithTimestamp('Ошибка форматирования временной метки:', error);
            return '';
        }
    };

    const truncateMessage = (message, maxLength = 30) => {
        if (!message) return 'No messages yet';
        return message.length > maxLength ? message.substring(0, maxLength) + '...' : message;
    };

    const fetchChats = async () => {
        try {
            logWithTimestamp('Запрашиваем чаты...', null);
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
                logWithTimestamp('Чаты загружены:', chatsWithMessages);
                setChats(chatsWithMessages);
            } else {
                toast.error('Failed to fetch chats');
            }
        } catch (error) {
            logWithTimestamp('Ошибка при загрузке чатов:', error);
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
            logWithTimestamp('Ошибка при разборе времени последнего визита:', error);
            return 0;
        }
    };

    const fetchUsers = async () => {
        try {
            logWithTimestamp('Запрашиваем пользователей...', null);
            const response = await fetch('https://unlimitedpossibilities12.org/api/users');
            if (response.ok) {
                const data = await response.json();
                logWithTimestamp('Пользователи загружены:', data);
                setUsers(data);
            } else {
                toast.error('Failed to fetch users');
            }
        } catch (error) {
            logWithTimestamp('Ошибка при загрузке пользователей:', error);
            toast.error('Error fetching users');
        }
    };

    useEffect(() => {
        const storedUserId = localStorage.getItem('userId');
        if (storedUserId) {
            setCurrentUserId(Number(storedUserId));
            setIsLoggedIn(true);
        }
        fetchChats();
        fetchUsers();
    }, [currentUserId]);

    useEffect(() => {
        if (!currentUserId) return;

        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            connectTimeout: 10000,
            onConnect: () => {
                logWithTimestamp('Подключено к WebSocket для звонков в Home.js', null);
                stompClient.current.subscribe(`/topic/calls/${currentUserId}`, (message) => {
                    logWithTimestamp('Получено сообщение в /topic/calls:', message.body);
                    let callRequest;
                    try {
                        callRequest = JSON.parse(message.body);
                        logWithTimestamp('Обработанное сообщение:', callRequest);
                        logWithTimestamp('Тип сообщения:', callRequest.type);
                    } catch (error) {
                        logWithTimestamp('Ошибка парсинга сообщения:', { error, body: message.body });
                        return;
                    }

                    if (callRequest.type === 'endCall') {
                        logWithTimestamp('Получен сигнал endCall:', callRequest);
                        logWithTimestamp('Текущее состояние incomingCall:', incomingCall);
                        logWithTimestamp('Текущее состояние lastCallRequest:', lastCallRequest);
                        logWithTimestamp('audioRef.current:', { element: audioRef.current, src: audioRef.current?.src });
                        logWithTimestamp('isCallActive:', isCallActive);
                        if (
                            (isCallActive && lastCallRequest?.roomId === callRequest.roomId) ||
                            (isCallActive && incomingCall?.roomId === callRequest.roomId)
                        ) {
                            logWithTimestamp('Закрываем уведомление о звонке для roomId:', callRequest.roomId);
                            setIncomingCallWithLog(null);
                            setLastCallRequestWithLog(null);
                            setIsCallActive(false);
                            if (audioRef.current) {
                                audioRef.current.pause();
                                audioRef.current.currentTime = 0;
                            }
                            toast.info('Звонящий отменил вызов.');
                        } else {
                            logWithTimestamp('Нет активного вызова для roomId:', {
                                roomId: callRequest.roomId,
                                incomingCall,
                                lastCallRequest,
                                isCallActive,
                                audioSrc: audioRef.current?.src
                            });
                        }
                    } else if (callRequest.type === 'call' || !callRequest.type) {
                        logWithTimestamp('Получен запрос на звонок:', callRequest);
                        handleIncomingCall(callRequest);
                    } else {
                        logWithTimestamp('Получено сообщение с неизвестным типом:', { callRequest, fullMessage: message.body });
                    }
                });
            },
            onWebSocketError: (error) => {
                logWithTimestamp('Ошибка WebSocket для звонков:', error);
            },
        });
        stompClient.current.activate();

        return () => {
            stompClient.current.deactivate();
            logWithTimestamp('WebSocket для звонков отключён', null);
        };
    }, [currentUserId]);

    useEffect(() => {
        logWithTimestamp('Состояние incomingCall изменилось:', incomingCall);
    }, [incomingCall]);

    useEffect(() => {
        if (!currentUserId || chats.length === 0) {
            logWithTimestamp('Пропускаем подписку: нет пользователя или чатов', null);
            return;
        }

        logWithTimestamp('Инициализация WebSocket для сообщений, чаты:', chats);
        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        messageClient.current = new Client({
            webSocketFactory: () => socket,
            connectTimeout: 10000,
            onConnect: () => {
                logWithTimestamp('Подключено к WebSocket для сообщений в Home.js', null);
                chats
                    .filter((chat) => !chat.private)
                    .forEach((chat) => {
                        logWithTimestamp(`Подписываемся на /topic/chat/${chat.id}/messages`, null);
                        messageClient.current.subscribe(`/topic/chat/${chat.id}/messages`, (message) => {
                            logWithTimestamp(`Получено сообщение для чата ${chat.id}:`, message.body);
                            const newMessage = JSON.parse(message.body);
                            handleNewMessage(newMessage, chat.id);
                        });
                    });
            },
            onWebSocketError: (error) => {
                logWithTimestamp('Ошибка WebSocket для сообщений:', error);
            },
        });
        messageClient.current.activate();

        return () => {
            messageClient.current.deactivate();
            logWithTimestamp('WebSocket для сообщений отключён', null);
        };
    }, [currentUserId, chats]);

    const handleNewMessage = async (message, chatId) => {
        logWithTimestamp('Обработка нового сообщения:', { message, chatId });
        try {
            const chat = chats.find((c) => c.id === chatId);
            if (!chat) {
                logWithTimestamp('Чат не найден в состоянии chats:', { chatId, chats });
                return;
            }
            if (chat.private) {
                logWithTimestamp('Сообщение для приватного чата, игнорируем:', chatId);
                return;
            }

            const messages = await getMessages(chatId);
            logWithTimestamp('Загружены сообщения для чата', { chatId, messages });
            const lastMessage = messages.length > 0 ? messages[messages.length - 1] : null;
            const unreadCount = calculateUnreadCount(messages, chatId);
            logWithTimestamp('Рассчитан unreadCount:', unreadCount);

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
                logWithTimestamp('Обновлены чаты:', updatedChats);
                return [...updatedChats];
            });
        } catch (error) {
            logWithTimestamp('Ошибка обработки нового сообщения:', error);
            toast.error('Error updating messages');
        }
    };

    const handleIncomingCall = async (callRequest) => {
        logWithTimestamp('Входящий запрос на звонок:', callRequest);
        if (isCallActive && lastCallRequest?.roomId === callRequest.roomId) {
            logWithTimestamp('Вызов с roomId уже обрабатывается:', callRequest.roomId);
            return;
        }
        setIsCallActive(true);
        setLastCallRequestWithLog((prev) => {
            logWithTimestamp('Предыдущее lastCallRequest:', prev);
            return {
                roomId: callRequest.roomId,
                callerId: callRequest.callerId,
                recipientId: callRequest.recipientId
            };
        });
        setIncomingCallWithLog((prev) => {
            logWithTimestamp('Предыдущее incomingCall:', prev);
            return callRequest;
        });
        try {
            logWithTimestamp('Начало API-запроса для callerId:', callRequest.callerId);
            const response = await Promise.race([
                fetch(`https://unlimitedpossibilities12.org/api/users/${callRequest.callerId}`),
                new Promise((_, reject) => setTimeout(() => reject(new Error('API timeout')), 5000))
            ]);
            if (response.ok) {
                const callerData = await response.json();
                logWithTimestamp('API-запрос завершён, данные:', callerData);
                setIncomingCallWithLog((prev) => {
                    logWithTimestamp('Предыдущее incomingCall перед добавлением callerName:', prev);
                    return {
                        ...callRequest,
                        callerName: callerData.username,
                        avatar: callerData.avatar
                    };
                });
            } else {
                logWithTimestamp('Ошибка при получении данных звонящего:', response.status);
            }
        } catch (error) {
            logWithTimestamp('Ошибка при получении данных звонящего:', error);
        }
    };

    const acceptCall = async () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;

        logWithTimestamp('Функция acceptCall в Home.js работает...', null);
        logWithTimestamp('Входящий звонок принят:', incomingCall);

        const callerId = incomingCall.callerId;
        const recipientId = currentUserId;

        try {
            const [callerResponse, recipientResponse] = await Promise.all([
                fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`),
                fetch(`https://unlimitedpossibilities12.org/api/users/${recipientId}`),
            ]);

            if (callerResponse.ok && recipientResponse.ok) {
                const callerData = await callerResponse.json();
                const recipientData = await recipientResponse.json();

                sendSignal({
                    from: currentUserId,
                    signal: { type: 'acceptCall' },
                });

                setIncomingCallWithLog(null);
                setLastCallRequestWithLog(null);
                setIsCallActive(false);

                navigate(`/voice-call/${incomingCall.roomId}`, {
                    state: {
                        incomingCall,
                        isCaller: false,
                        currentUserId: currentUserId,
                        callerName: callerData.username,
                        recipientName: recipientData.username,
                    },
                });
            } else {
                logWithTimestamp('Ошибка при получении имен пользователей', null);
            }
        } catch (error) {
            logWithTimestamp('Ошибка при получении имен пользователей:', error);
        }
    };

    const rejectCall = () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;
        setIncomingCallWithLog(null);
        setLastCallRequestWithLog(null);
        setIsCallActive(false);
        sendSignal({
            from: currentUserId,
            signal: { type: 'rejectCall' },
        });
    };

    const sendSignal = (signalMessage) => {
        logWithTimestamp('Отправка сигнала:', JSON.stringify(signalMessage, null, 2));
        const roomId = incomingCall.roomId;
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`,
            body: JSON.stringify(signalMessage),
        });
        logWithTimestamp('Сигнал успешно отправлен.', null);
    };

    const loginUserHandler = async () => {
        try {
            const user = await loginUser({ username: loginUsername, password: loginPassword });
            localStorage.setItem('userId', String(user.id));
            localStorage.setItem('username', user.username);
            setCurrentUserId(Number(user.id));
            setLoginUsername('');
            setLoginPassword('');
            setIsLoggedIn(true);
            toast.success('Login successful!');
        } catch (error) {
            logWithTimestamp('Ошибка входа:', error);
            toast.error(error.message || 'Error logging in');
        }
    };

    const logoutUser = () => {
        localStorage.removeItem('userId');
        setCurrentUserId(null);
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
                logWithTimestamp('Ошибка создания чата:', error);
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
                toast.success('User created successfully!');
            } else {
                const errorMessage = await response.text();
                toast.error(errorMessage);
            }
        } catch (error) {
            logWithTimestamp('Ошибка создания пользователя:', error);
            toast.error('Error creating user');
        }
    };

    const goToChat = (chatId) => {
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
            <ToastContainer
                position="top-right"
                autoClose={5000}
                hideProgressBar={false}
                newestOnTop={false}
                closeOnClick
                rtl={false}
                pauseOnFocusLoss
                draggable
                pauseOnHover
            />
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

            <audio ref={audioRef} loop>
                <source src="https://igor7070.github.io/Messenger/melodiya_kovboy.mp3" type="audio/mpeg" />
                Ваш браузер не поддерживает аудио.
            </audio>

            <audio ref={messageAudioRef}>
                <source src="https://igor7070.github.io/Messenger/zvuk-dlya-soobsen.mp3" type="audio/mpeg" />
                Ваш браузер не поддерживает аудио.
            </audio>

            {incomingCall && (
                <IncomingCallNotification
                    callerId={incomingCall.callerId}
                    callerName={incomingCall.callerName}
                    avatar={incomingCall.avatar}
                    onAccept={acceptCall}
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