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

    const fetchChats = async () => {
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/chats');
            if (response.ok) {
                const data = await response.json();
                setChats(data);
                fetchLastMessages(data);
            } else {
                toast.error('Failed to fetch chats');
            }
        } catch (error) {
            toast.error('Error fetching chats');
        }
    };

    const fetchLastMessages = async (chats) => {
        const updatedChats = await Promise.all(chats.map(async (chat) => {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/messages/chat/${chat.id}`);
                if (response.ok) {
                    const messages = await response.json();
                    const lastMessage = messages.length > 0 ? messages[messages.length - 1] : null;
                    const lastVisit = localStorage.getItem(`last_visit_chat_${chat.id}`);
                    const unreadCount = calculateUnreadCount(messages, lastVisit);
                    return {
                        ...chat,
                        lastMessage: lastMessage ? lastMessage.content : 'No messages yet',
                        timestamp: lastMessage ? lastMessage.timestamp : '',
                        unreadCount
                    };
                }
            } catch (error) {
                console.error(`Error fetching messages for chat ${chat.id}:`, error);
            }
            return { ...chat, lastMessage: 'No messages yet', timestamp: '', unreadCount: 0 };
        }));
        setChats(updatedChats);
    };

    const calculateUnreadCount = (messages, lastVisit) => {
        if (!lastVisit) {
            return messages.length;
        }
        try {
            const lastVisitTime = new Date(lastVisit).toISOString();
            return messages.reduce((count, message) => {
                if (message.timestamp && new Date(message.timestamp) > new Date(lastVisitTime)) {
                    return count + 1;
                }
                return count;
            }, 0);
        } catch (error) {
            console.error('Error parsing timestamps:', error);
            return 0;
        }
    };

    const formatTimestamp = (timestamp) => {
        if (!timestamp) return '';
        try {
            const date = new Date(timestamp);
            const now = new Date();
            const isToday = date.toDateString() === now.toDateString();
            if (isToday) {
                return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            }
            return date.toLocaleDateString();
        } catch (error) {
            console.error('Error formatting timestamp:', error);
            return '';
        }
    };

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
                stompClient.current.subscribe(`/topic/calls/${currentUserId}`, (message) => {
                    const callRequest = JSON.parse(message.body);
                    handleIncomingCall(callRequest);
                });
                chats.forEach(chat => {
                    stompClient.current.subscribe(`/topic/messages/${chat.id}`, (message) => {
                        const newMessage = JSON.parse(message.body);
                        updateChatWithNewMessage(chat.id, newMessage);
                    });
                });
            },
        });
        stompClient.current.activate();

        return () => {
            stompClient.current.deactivate();
        };
    }, [currentUserId, chats]);

    const updateChatWithNewMessage = (chatId, newMessage) => {
        setChats(prevChats => {
            const updatedChats = prevChats.map(chat => {
                if (chat.id === chatId) {
                    const lastVisit = localStorage.getItem(`last_visit_chat_${chat.id}`);
                    const messages = chat.messages ? [...chat.messages, newMessage] : [newMessage];
                    const unreadCount = calculateUnreadCount(messages, lastVisit);
                    return {
                        ...chat,
                        messages,
                        lastMessage: newMessage.content,
                        timestamp: newMessage.timestamp,
                        unreadCount
                    };
                }
                return chat;
            });
            return updatedChats;
        });
        if (messageAudioRef.current) {
            messageAudioRef.current.play().catch(error => {
                console.error('Error playing message sound:', error);
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
                setIncomingCall({ ...callRequest, callerName: callerData.username });
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
                fetch(`https://unlimitedpossibilities12.org/api/users/${recipientId}`)
            ]);
            if (callerResponse.ok && recipientResponse.ok) {
                const callerData = await callerResponse.json();
                const recipientData = await recipientResponse.json();
                stompClient.current.publish({
                    destination: `/app/signal/${incomingCall.roomId}`,
                    body: JSON.stringify({ from: currentUserId, signal: { type: 'acceptCall' } })
                });
                navigate(`/voice-call/${incomingCall.roomId}`, {
                    state: {
                        incomingCall,
                        isCaller: false,
                        currentUserId,
                        callerName: callerData.username,
                        recipientName: recipientData.username
                    }
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
        stompClient.current.publish({
            destination: `/app/signal/${incomingCall.roomId}`,
            body: JSON.stringify({ from: currentUserId, signal: { type: 'rejectCall' } })
        });
    };

    const sendSignal = (signalMessage) => {
        console.log('Function sendSignal in VoiceCall.js working...');
        console.log('Sending signal:', JSON.stringify(signalMessage, null, 2));
        const roomId = incomingCall.roomId;
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`,
            body: JSON.stringify(signalMessage)
        });
        console.log('Signal sent successfully.');
    };

    const loginUser = async () => {
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/users/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: loginUsername, password: loginPassword })
            });
            if (response.ok) {
                const user = await response.json();
                localStorage.setItem('userId', String(user.id));
                localStorage.setItem('username', user.username);
                setCurrentUserId(Number(user.id));
                setLoginUsername('');
                setLoginPassword('');
                setIsLoggedIn(true);
                toast.success('Login successful!');
            } else {
                toast.error(await response.text());
            }
        } catch (error) {
            toast.error('Error logging in');
        }
    };

    const logoutUser = () => {
        localStorage.removeItem('userId');
        setCurrentUserId(null);
        setIsLoggedIn(false);
        toast.success('Logged out successfully!');
    };

    const createChat = async () => {
        if (!chatName) {
            toast.error('Chat name cannot be empty');
            return;
        }
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/chats', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: chatName, private: false })
            });
            if (response.ok) {
                const newChat = await response.json();
                setChats([...chats, { ...newChat, lastMessage: 'No messages yet', timestamp: '', unreadCount: 0 }]);
                setChatName('');
                toast.success('Chat created successfully!');
            } else {
                toast.error(await response.text());
            }
        } catch (error) {
            toast.error('Error creating chat');
        }
    };

    const createUser = async () => {
        const formData = new FormData();
        formData.append('username', username);
        formData.append('password', password);
        formData.append('bio', bio);
        if (avatar) formData.append('avatar', avatar);
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/users/create', {
                method: 'POST',
                body: formData
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
                toast.error(await response.text());
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
        .filter(chat => !chat.private)
        .filter(chat => chat.name.toLowerCase().includes(searchTerm.toLowerCase()));

    const filteredUsers = users.filter(user => user.username.toLowerCase().includes(userSearchTerm.toLowerCase()));

    return (
        <div className="home-container">
            <ToastContainer position="top-right" autoClose={5000} hideProgressBar={false} newestOnTop={false} closeOnClick rtl={false} pauseOnFocusLoss draggable pauseOnHover />
            <div className="login-container-home">
                {isLoggedIn ? (
                    <button onClick={logoutUser} className="logout-button-home">Logout</button>
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
                        <button onClick={loginUser} className="login-button-home">Login</button>
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
                    <button type="button" onClick={openModal} className="create-user-button-main">Create a new user</button>
                    <input
                        type="text"
                        value={userSearchTerm}
                        onChange={(e) => setUserSearchTerm(e.target.value)}
                        placeholder="Search user"
                        className="search-input"
                    />
                    <ul className="user-list">
                        {filteredUsers.map(user => {
                            const avatarSrc = user.avatar ? `https://unlimitedpossibilities12.org${user.avatar}` : null;
                            const initials = user.username ? user.username.charAt(0).toUpperCase() : '';
                            return (
                                <li key={user.id} className="user-item" onClick={() => navigate(`/user/${user.id}`)}>
                                    {avatarSrc ? (
                                        <img src={avatarSrc} alt={`${user.username} avatar`} className="user-avatar" />
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
                        <button onClick={createChat} className="create-chat-button">Create a group chat</button>
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
                                <div className="chat-icon">
                                    <img src="/assets/chat_icon.png" alt="Chat icon" />
                                </div>
                                <div className="chat-info">
                                    <div className="chat-header">
                                        <span className="chat-name">{chat.name}</span>
                                        <span className="chat-timestamp">{formatTimestamp(chat.timestamp)}</span>
                                    </div>
                                    <span className="chat-last-message">{chat.lastMessage}</span>
                                </div>
                                {chat.unreadCount > 0 && (
                                    <span className="unread-count">{chat.unreadCount}</span>
                                )}
                            </li>
                        ))}
                    </ul>
                </div>
            </div>

            <Modal isOpen={modalIsOpen} onRequestClose={() => setModalIsOpen(false)} className="modal" overlayClassName="modal-overlay">
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
                <button type="button" onClick={createUser} className="modal-create-button">Create User</button>
                <button onClick={() => setModalIsOpen(false)} className="modal-close-button">Close</button>
            </Modal>
        </div>
    );
};

export default Home;