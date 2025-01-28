import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from 'react-modal';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import '../styles/Home.css'; 
import useWebSocket from '../hooks/useWebSocket';
import IncomingCallNotification from './IncomingCallNotification'; // Импортируйте компонент уведомления
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
    const [incomingCall, setIncomingCall] = useState(null); // Состояние для входящего звонка
    const navigate = useNavigate();

    // Используем хук для подключения к WebSocket
    useWebSocket(currentUserId); 

    const fetchChats = async () => {
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/chats');
            if (response.ok) {
                const data = await response.json();
                console.log(data);
                setChats(data);
            } else {
                toast.error('Failed to fetch chats');
            }
        } catch (error) {
            toast.error('Error fetching chats');
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

        // Подключение к WebSocket
        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        const stompClient = new Client({
            webSocketFactory: () => socket,
            onConnect: () => {
                console.log('Connected to WebSocket');
                console.log("currentUserId:", currentUserId);
                stompClient.subscribe(`/topic/calls/${currentUserId}`, (message) => {
                    const callRequest = JSON.parse(message.body);
                    handleIncomingCall(callRequest);
                });
            },
        });
        stompClient.activate();

        return () => {
            stompClient.deactivate();
        };
    }, [currentUserId]);

    const handleIncomingCall = (callRequest) => {
        console.log("Incoming call request:", callRequest); // Логируем объект вызова
        setIncomingCall(callRequest); // Устанавливаем входящий вызов
    };

    const acceptCall = () => {
        console.log('Function acceptCall in Home.js working...');
        console.log("Incoming call accepted:", incomingCall); // Логируем входящий вызов
        console.log("Caller ID:", incomingCall.callerId); // Логируем callerId
        console.log("Room ID:", incomingCall.roomId); // Логируем roomId
        setIncomingCall(null); // Скрыть уведомление
        //navigate(`/voice-call/${incomingCall.roomId}`); // Перейти на страницу звонка
        navigate(`/voice-call/${incomingCall.roomId}`, { 
            state: { incomingCall, isCaller: false } // Указываем, что это не звонящий
        });
    };

    const rejectCall = () => {
        setIncomingCall(null); // Скрыть уведомление
        toast.info(`Call from ${incomingCall.callerId} rejected.`);
    };

    const loginUser = async () => {
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/users/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ username: loginUsername, password: loginPassword }),
            });
    
            if (response.ok) {
                const user = await response.json();
                localStorage.setItem('userId', String(user.id));
                localStorage.setItem('username', user.username); // Установка имени пользователя
                setCurrentUserId(Number(user.id));
                setLoginUsername('');
                setLoginPassword('');
                setIsLoggedIn(true);
                toast.success('Login successful!');
            } else {
                const errorMessage = await response.text();
                toast.error(errorMessage);
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
        if (chatName) {
            try {
                const response = await fetch('https://unlimitedpossibilities12.org/api/chats', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({ name: chatName }),
                });

                if (response.ok) {
                    const newChat = await response.json();
                    setChats([...chats, newChat]);
                    setChatName('');
                    toast.success('Chat created successfully!');
                } else {
                    toast.error('Failed to create chat');
                }
            } catch (error) {
                toast.error('Error creating chat');
            }
        } else {
            toast.error('Chat name cannot be empty');
        }
    };

    const createUser = async () => {
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
        .filter(chat => !chat.private) // Отображаем только групповые чаты
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

            {/* Уведомление о входящем звонке */}
            {incomingCall && (
                <IncomingCallNotification 
                    callerName={incomingCall.callerId} 
                    onAccept={acceptCall} 
                    onReject={rejectCall} 
                />
            )}

            <div className="line-divider-container">
                <div className="users-container">
                    <h2>Users</h2>
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
                                        <div className="user-avatar">
                                            {initials}
                                        </div>
                                    )}
                                    <span className="user-name">{user.username}</span>
                                </li>
                            );
                        })}
                    </ul>
                </div>

                <div className="line-divider"></div>

                <div className="chats-container">
                    <h1>Group chats</h1>
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
                                {chat.name}
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