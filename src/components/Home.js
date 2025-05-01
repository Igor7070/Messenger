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
    const { userInteracted, handleInteraction } = useUserInteraction();
    const stompClient = useRef(null);
    const audioRef = useRef(null);
    const messageAudioRef = useRef(null);
    const navigate = useNavigate();

    useWebSocket(currentUserId, messageAudioRef);

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
            audioRef.current.play().catch(error => {
                console.error('Autoplay was prevented.', error);
            });
        } else {
            audioRef.current.pause();
            audioRef.current.currentTime = 0;
        }
    }, [incomingCall, userInteracted]);

    // Форматирование временной метки (согласованное с Messenger.js)
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

    // Сокращение текста сообщения
    const truncateMessage = (message, maxLength = 30) => {
        if (!message) return 'No messages yet';
        return message.length > maxLength ? message.substring(0, maxLength) + '...' : message;
    };

    // Загрузка чатов
    const fetchChats = async () => {
        try {
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
                setChats(chatsWithMessages);
            } else {
                toast.error('Failed to fetch chats');
            }
        } catch (error) {
            toast.error('Error fetching chats');
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
            console.error('Error parsing last visit time:', error);
            return 0;
        }
    };

    // Загрузка пользователей
    const fetchUsers = async () => {
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/users');
            if (response.ok) {
                const data = await response.json();
                setUsers(data);
            } else {
                toast.error('Failed to fetch users');
            }
        } catch (error) {
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

        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            onConnect: () => {
                console.log('Connected to WebSocket in Home.js');
                if (currentUserId) {
                    stompClient.current.subscribe(`/topic/calls/${currentUserId}`, (message) => {
                        const callRequest = JSON.parse(message.body);
                        handleIncomingCall(callRequest);
                    });
                    // Подписка на сообщения для всех чатов
                    stompClient.current.subscribe(`/topic/messages`, (message) => {
                        const newMessage = JSON.parse(message.body);
                        handleNewMessage(newMessage);
                    });
                }
            },
        });
        stompClient.current.activate();

        return () => {
            stompClient.current.deactivate();
        };
    }, [currentUserId]);

    const handleNewMessage = async (message) => {
        const chatId = message.chatId;
        const messages = await getMessages(chatId);
        const lastMessage = messages.length > 0 ? messages[messages.length - 1] : null;
        const unreadCount = calculateUnreadCount(messages, chatId);

        setChats((prevChats) =>
            prevChats.map((chat) =>
                chat.id === chatId
                    ? {
                          ...chat,
                          lastMessage: lastMessage ? lastMessage.content : null,
                          timestamp: lastMessage ? lastMessage.timestamp : null,
                          unreadCount,
                      }
                    : chat
            )
        );

        if (userInteracted) {
            messageAudioRef.current.play().catch((error) => {
                console.error('Error playing message notification sound:', error);
            });
        }
    };

    const handleIncomingCall = async (callRequest) => {
        console.log('Incoming call request:', callRequest);
        setIncomingCall(callRequest);

        try {
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${callRequest.callerId}`);
            if (response.ok) {
                const callerData = await response.json();
                const callerName = callerData.username;
                setIncomingCall({ ...callRequest, callerName });
            } else {
                console.error('Error fetching caller name');
            }
        } catch (error) {
            console.error('Error fetching caller name:', error);
        }
    };

    const acceptCall = async () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;

        console.log('Function acceptCall in Home.js working...');
        console.log('Incoming call accepted:', incomingCall);

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
                console.error('Error fetching user names');
            }
        } catch (error) {
            console.error('Error fetching user names:', error);
        }
    };

    const rejectCall = () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;

        setIncomingCall(null);
        sendSignal({
            from: currentUserId,
            signal: { type: 'rejectCall' },
        });
    };

    const sendSignal = (signalMessage) => {
        console.log('Sending signal:', JSON.stringify(signalMessage, null, 2));
        const roomId = incomingCall.roomId;
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`,
            body: JSON.stringify(signalMessage),
        });
        console.log('Signal sent successfully.');
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