import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from 'react-modal';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import '../styles/Home.css'; 
import useWebSocket from '../hooks/useWebSocket';
import IncomingCallNotification from './IncomingCallNotification'; // Импорт компонента уведомления
import { useUserInteraction } from './UserInteractionContext';
import { useModal } from './ModalContextVoice';
import '../styles/ModalContextVoice.css';
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
    const { userInteracted, handleInteraction } = useUserInteraction();
    const { showModalVoice, openModalVoice, closeModalVoice } = useModal();
    const [hasLoaded, setHasLoaded] = useState(false); // Флаг для отслеживания загрузки
    const stompClient = useRef(null); // Используем useRef для stompClient
    const audioRef = useRef(null); // Используем useRef для аудиоплеера
    const navigate = useNavigate();

    // Используем хук для подключения к WebSocket
    useWebSocket(currentUserId);

    // Обработка события клика на документе
    useEffect(() => {
        // Открыть модальное окно только при первой загрузке
        if (!hasLoaded) {
            openModalVoice();
            setHasLoaded(true); // Установить флаг в true
        }

        const handleClick = () => {
            handleInteraction();
        };

        document.addEventListener('click', handleClick);
        return () => {
            document.removeEventListener('click', handleClick);
        };
    }, [handleInteraction]);

    // Второе useEffect: управление воспроизведением звука
    useEffect(() => {
        if (incomingCall && userInteracted) {
            audioRef.current.play().catch(error => {
                console.error('Autoplay was prevented.', error);
            }); // Запускаем звук при входящем звонке
        } else {
            audioRef.current.pause(); // Останавливаем звук, если звонка нет
            audioRef.current.currentTime = 0; // Сбрасываем время
        }
    }, [incomingCall, userInteracted]); // Добавляем userInteracted в зависимости

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
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            onConnect: () => {
                console.log('Connected to WebSocket in Home.js');
                console.log("currentUserId:", currentUserId);
                stompClient.current.subscribe(`/topic/calls/${currentUserId}`, (message) => {
                    const callRequest = JSON.parse(message.body);
                    handleIncomingCall(callRequest);
                });
            },
        });
        stompClient.current.activate();

        return () => {
            stompClient.current.deactivate();
        };
    }, [currentUserId]);

    const handleIncomingCall = async (callRequest) => {
        console.log("Incoming call request:", callRequest); // Логируем объект вызова
        setIncomingCall(callRequest); // Устанавливаем входящий вызов
    
        // Получаем имя звонящего
        const callerId = callRequest.callerId;
    
        try {
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`);
            if (response.ok) {
                const callerData = await response.json();
                const callerName = callerData.username; // Извлекаем имя звонящего
                setIncomingCall({ ...callRequest, callerName }); // Добавляем имя звонящего к объекту вызова
            } else {
                console.error('Error fetching caller name');
            }
        } catch (error) {
            console.error('Error fetching caller name:', error);
        }
    };

    const acceptCall = () => {
        // Остановка звука при принятии звонка
        audioRef.current.pause();
        audioRef.current.currentTime = 0;

        console.log('Function acceptCall in Home.js working...');
        console.log("Incoming call accepted:", incomingCall); // Логируем входящий вызов
        
        const callerId = incomingCall.callerId;
        const recipientId = currentUserId; // Получаем ID текущего пользователя
    
        // Получаем имена пользователей (можно использовать Promise.all для параллельных запросов)
        const fetchUserNames = async () => {
            try {
                const callerResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`);
                const recipientResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${recipientId}`);
                
                if (callerResponse.ok && recipientResponse.ok) {
                    const callerData = await callerResponse.json();
                    const recipientData = await recipientResponse.json();

                    sendSignal({
                        from: currentUserId,
                        signal: {
                            type: 'acceptCall'
                        }
                    });
                    
                    navigate(`/voice-call/${incomingCall.roomId}`, { 
                        state: { 
                            incomingCall, 
                            isCaller: false, 
                            currentUserId: currentUserId, 
                            callerName: callerData.username, // Передаем имя звонящего
                            recipientName: recipientData.username // Передаем имя получателя
                        } 
                    });
                } else {
                    console.error('Error fetching user names');
                }
            } catch (error) {
                console.error('Error fetching user names:', error);
            }
        };
    
        fetchUserNames();
    };

    const rejectCall = () => {
        // Остановка звука при принятии звонка
        audioRef.current.pause();
        audioRef.current.currentTime = 0;

        setIncomingCall(null); // Скрыть уведомление
        sendSignal({
            from: currentUserId,
            signal: {
                type: 'rejectCall'
            }
        });
        //toast.info(`Call from ${incomingCall.callerId} rejected.`);
    };

    const sendSignal = (signalMessage) => {
        console.log('Function sendSignal in VoiceCall.js working...');
        console.log("Sending signal:", JSON.stringify(signalMessage, null, 2));
        const roomId = incomingCall.roomId;
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`, 
            body: JSON.stringify(signalMessage),
        });
        console.log('Signal sent successfully.');
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

            {showModalVoice && (
                <div className="modal">
                    <div className="modal-content">
                        <p>Press OK to confirm the sound for voice calls.</p>
                        <button onClick={() => { handleInteraction(); closeModalVoice(); }}>OK</button>
                    </div>
                </div>
            )}

            {/* Вставляем аудиофайл */}
            <audio ref={audioRef} loop>
                <source src="https://igor7070.github.io/Messenger/melodiya_kovboy.mp3" type="audio/mpeg" />
                Ваш браузер не поддерживает аудио.
            </audio>

            {/* Уведомление о входящем звонке */}
            {incomingCall && (
                <IncomingCallNotification 
                    callerId={incomingCall.callerId} 
                    callerName={incomingCall.callerName} // Передаем имя звонящего
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