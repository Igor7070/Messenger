import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom'; // Импортируйте useNavigate
import '../styles/UserProfile.css'; 
import { FaEdit } from 'react-icons/fa';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import Modal from 'react-modal';
import useWebSocket from '../hooks/useWebSocket';
import IncomingCallNotification from './IncomingCallNotification';
import { useUserInteraction } from './UserInteractionContext';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

const UserProfile = () => {
    const { userId } = useParams();
    const navigate = useNavigate(); // Инициализация useNavigate
    const [user, setUser] = useState(null);
    const [newAvatar, setNewAvatar] = useState(null);
    const [info, setInfo] = useState('');
    const [isEditing, setIsEditing] = useState(false);
    const [error, setError] = useState(null);
    const [currentUserId, setCurrentUserId] = useState(null);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [avatarFileName, setAvatarFileName] = useState('');
    const [incomingCall, setIncomingCall] = useState(null); // Состояние для входящего звонка
    const { userInteracted, handleInteraction } = useUserInteraction();
    const stompClient = useRef(null); // Используем useRef для stompClient
    const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/melodiya_kovboy.mp3')); // Инициализация
    audioRef.current.loop = true; // Устанавливаем зацикливание

    // Используем хук для подключения к WebSocket
    useWebSocket(currentUserId);

    useEffect(() => {
        const fetchUser = async () => {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}`);
                if (response.ok) {
                    const data = await response.json();
                    setUser(data);
                    setInfo(data.info || '');
                    const avatarResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}/avatar`);
                    if (avatarResponse.ok) {
                        const fileName = await avatarResponse.text();
                        setAvatarFileName(fileName);
                    } else {
                        console.error('Failed to fetch avatar file name:', avatarResponse.statusText);
                    }
                } else {
                    setError('Failed to fetch user data');
                    console.error('Failed to fetch user:', response.statusText);
                    toast.error('Failed to fetch user data');
                }
            } catch (error) {
                setError('Error fetching user data');
                console.error('Error fetching user:', error);
                toast.error('Error fetching user data');
            }
        };

        fetchUser();

        const storedUserId = localStorage.getItem('userId');
        if (storedUserId) {
            setCurrentUserId(Number(storedUserId));
        }
    }, [userId]);

    // Обработка события клика на документе
    useEffect(() => {
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

    useEffect(() => {         
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

    const handleAvatarChange = (e) => {
        setNewAvatar(e.target.files[0]);
    };

    const handleUpdateUser = async () => {
        const formData = new FormData();
        formData.append('info', info);
        if (newAvatar) {
            formData.append('avatar', newAvatar);
        }

        try {
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}`, {
                method: 'PUT',
                body: formData,
            });

            if (response.ok) {
                const updatedUser = await response.json();
                setUser(updatedUser);
                toast.success('User updated successfully!');
                setIsEditing(false);
            } else {
                setError('Failed to update user');
                console.error('Failed to update user:', response.statusText);
                toast.error('Failed to update user');
            }
        } catch (error) {
            setError('Error updating user');
            console.error('Error updating user:', error);
            toast.error('Error updating user');
        }
    };

    const openModal = () => {
        setIsModalOpen(true);
    };

    const closeModal = () => {
        setIsModalOpen(false);
    };

    const handleSendMessage = async () => {
        const currentUsername = localStorage.getItem('username'); // Получаем актуальное имя пользователя
        const chatName = `${user.username}_${currentUsername}`; // Создаем имя чата
    
        if (currentUserId && userId !== String(currentUserId)) {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/chats/check?name1=${user.username}&name2=${currentUsername}`);
                if (!response.ok) {
                    throw new Error(`HTTP error! Status: ${response.status}`);
                }
    
                const data = await response.json();
                const chatId = data.chatId;
    
                if (chatId !== -1) {
                    navigate(`/private-chat/${chatId}`);
                } else {
                    const newChat = { 
                        name: chatName, 
                        isPrivate: true // Устанавливаем isPrivate в true
                    };
    
                    console.log('New chat data:', newChat);
    
                    const createResponse = await fetch('https://unlimitedpossibilities12.org/api/chats', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                        },
                        body: JSON.stringify(newChat),
                    });
    
                    if (createResponse.ok) {
                        const createdChat = await createResponse.json();
                        navigate(`/private-chat/${createdChat.id}`);
                    } else {
                        toast.error('Failed to create new chat');
                    }
                }
    
                toast.success(`Navigating to chat with ${user.username}!`);
            } catch (error) {
                console.error('Ошибка при отправке сообщения:', error);
                toast.error('Ошибка при отправке сообщения. Попробуйте снова.');
            }
        } else {
            toast.warn('You cannot send a message to yourself.');
        }
    };

    // Обработчик для звонка
    const handleCall = async () => {
        // Генерация уникального roomId
        const roomId = `room-${currentUserId}-${userId}`; // Например, используем комбинацию ID пользователя
        console.log("Room ID:", roomId); // Логируем roomId
    
        console.log("Call Request: ");
        console.log("callerId - currentUserId: ", currentUserId);
        console.log("recipientId - userId: ", userId);
        const callRequest = {
            callerId: currentUserId,
            recipientId: userId,
            roomId: roomId
        };
    
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/webrtc/call', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(callRequest),
            });
    
            if (response.ok) {
                const callerResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${currentUserId}`);
                const callerData = await callerResponse.json();

                toast.success(`Calling ${user.username}...`);
                navigate(`/voice-call/${roomId}`, { 
                    state: { 
                        incomingCall: callRequest, 
                        isCaller: true, 
                        currentUserId: currentUserId,
                        callerName: callerData.username, // Передаем имя звонящего
                        recipientName: user.username // Передаем имя получателя
                    } 
                });
            } else {
                toast.error('Failed to initiate call');
            }
        } catch (error) {
            console.error('Error initiating call:', error);
            toast.error('Error initiating call');
        }
    };

    const handlePersonalChats = () => {
        const currentUserName = localStorage.getItem('username'); // Получаем имя из localStorage
        navigate(`/user/${currentUserId}/personal-chats`, { state: { currentUserName } }); // Переход к личным чатам
    };

    if (error) return <div className="error-message">{error}</div>;
    if (!user) return <div>Loading...</div>;

    return (
        <div className="user-profile-container">
            <ToastContainer position="top-right" autoClose={5000} hideProgressBar={false} newestOnTop={false} closeOnClick rtl={false} pauseOnFocusLoss draggable pauseOnHover />
            <h1>{user.username}</h1>
            {user.avatar ? (
                <img 
                    src={`https://unlimitedpossibilities12.org${user.avatar}`} 
                    alt={`${user.username} avatar`} 
                    className="user-avatar-profile" 
                    onClick={openModal} 
                />
            ) : (
                <div className="user-avatar-profile" style={{ backgroundColor: '#e0e0e0', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                    {user.username.charAt(0).toUpperCase()}
                </div>
            )}
            {userId === String(currentUserId) && ( 
                <input type="file" onChange={handleAvatarChange} className="avatar-input" />
            )}
            <div className="user-info">
                <h2>О себе:</h2>
                {isEditing ? (
                    <textarea 
                        value={info}
                        onChange={(e) => setInfo(e.target.value)}
                        rows={3}
                        className="user-info-input"
                        maxLength={244}
                    />
                ) : (
                    <div className="user-info-text">
                        <p>{info}</p>
                        {userId === String(currentUserId) && ( 
                            <FaEdit className="edit-icon" onClick={() => setIsEditing(true)} />
                        )}
                    </div>
                )}
            </div>

            {userId === String(currentUserId) && ( 
                <div className="button-container"> {/* Новый контейнер */}
                    <button onClick={handleUpdateUser} className="update-button">Update Profile</button>
                    <button onClick={handlePersonalChats} className="personal-chats-button">
                        Personal Chats
                    </button>
                </div>
            )}

            {/* Кнопка "Send message" */}
            {currentUserId && userId !== String(currentUserId) && ( 
                <button onClick={handleSendMessage} className="send-message-button">Send Message</button>
            )}

            {/* Кнопка "Call" */}
            {currentUserId && userId !== String(currentUserId) && ( 
                <button onClick={handleCall} className="call-button">Call</button>
            )}

            {/* Модальное окно для увеличенного изображения */}
            <Modal isOpen={isModalOpen} onRequestClose={closeModal} className="user-profile-modal" overlayClassName="user-profile-modal-overlay">
                <img 
                    src={`https://unlimitedpossibilities12.org/avatars/${avatarFileName}`} 
                    alt={`${user.username} avatar`} 
                    className="user-profile-modal-image" 
                    style={{ width: '100%', height: 'auto' }} 
                    onClick={closeModal} 
                />
                <button onClick={closeModal} className="user-profile-modal-close-button">Close</button>
            </Modal>

            {/* Уведомление о входящем звонке */}
            {incomingCall && (
                <IncomingCallNotification 
                    callerId={incomingCall.callerId} 
                    callerName={incomingCall.callerName}
                    onAccept={acceptCall} 
                    onReject={rejectCall} 
                />
            )}
        </div>
    );
};

export default UserProfile;