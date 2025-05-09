import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import '../styles/UserProfile.css';
import { FaEdit, FaEllipsisV, FaPhone, FaVideo } from 'react-icons/fa'; // Добавляем иконки для звонков
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
    const navigate = useNavigate();
    const [user, setUser] = useState(null);
    const [newAvatar, setNewAvatar] = useState(null);
    const [info, setInfo] = useState('');
    const [isEditing, setIsEditing] = useState(false);
    const [error, setError] = useState(null);
    const [currentUserId, setCurrentUserId] = useState(null);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [avatarFileName, setAvatarFileName] = useState('');
    const [incomingCall, setIncomingCall] = useState(null);
    const callRequestRef = useRef(null);
    const { userInteracted, handleInteraction } = useUserInteraction();
    const stompClient = useRef(null);
    const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/melodiya_kovboy.mp3'));
    audioRef.current.loop = true;
    const messageAudioRef = useRef(null);

    const [settingsMenuVisible, setSettingsMenuVisible] = useState(false);

    useWebSocket(currentUserId, messageAudioRef);

    const setIncomingCallWithLog = (value) => {
        console.log('Вызов setIncomingCall:', value);
        console.log('Текущее incomingCall:', incomingCall);
        console.log('Текущее callRequestRef:', callRequestRef.current);
        console.log('Стек вызовов:', new Error().stack);
        setIncomingCall(value);
    };

    useEffect(() => {
        console.log('Состояние incomingCall изменилось:', incomingCall);
    }, [incomingCall]);

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

    useEffect(() => {
        const handleClick = () => {
            handleInteraction();
            setSettingsMenuVisible(false);
        };

        document.addEventListener('click', handleClick);
        return () => {
            document.removeEventListener('click', handleClick);
        };
    }, [handleInteraction]);

    useEffect(() => {
        if (incomingCall && userInteracted) {
            audioRef.current.play().catch(error => {
                console.error('Autoplay was prevented:', error);
                toast.warn('Please interact with the page to enable call audio.');
            });
        } else {
            audioRef.current.pause();
            audioRef.current.currentTime = 0;
        }
    }, [incomingCall, userInteracted]);

    useEffect(() => {
        if (!currentUserId) return;

        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            connectTimeout: 10000,
            onConnect: () => {
                console.log('Connected to WebSocket in UserProfile.js');
                console.log("currentUserId:", currentUserId);
                stompClient.current.subscribe(`/topic/calls/${currentUserId}`, (message) => {
                    console.log('Получено сообщение в /topic/calls:', message.body);
                    let callRequest;
                    try {
                        callRequest = JSON.parse(message.body);
                        console.log('Обработанное сообщение:', callRequest);
                        console.log('Тип сообщения:', callRequest.type);
                    } catch (error) {
                        console.error('Ошибка парсинга сообщения:', error, message.body);
                        return;
                    }

                    if (callRequest.type === 'endCall') {
                        console.log('Получен сигнал endCall:', callRequest);
                        console.log('Текущее состояние incomingCall:', incomingCall);
                        console.log('Текущее callRequestRef:', callRequestRef.current);
                        console.log('audioRef.current:', audioRef.current, 'src:', audioRef.current?.src);
                        if (
                            callRequestRef.current?.roomId === callRequest.roomId ||
                            incomingCall?.roomId === callRequest.roomId ||
                            audioRef.current?.src
                        ) {
                            console.log('Закрываем уведомление о звонке для roomId:', callRequest.roomId);
                            setIncomingCallWithLog(null);
                            callRequestRef.current = null;
                            if (audioRef.current) {
                                audioRef.current.pause();
                                audioRef.current.currentTime = 0;
                            }
                            const cancelMessage = callRequest.type === 'video' ?
                                'The video call was cancelled.' :
                                'The voice call was cancelled.';
                            console.log('Отправляем тост:', cancelMessage);
                            toast.info(cancelMessage);
                        } else {
                            console.log('Нет активного вызова или звука:', {
                                incomingCall,
                                callRequestRef: callRequestRef.current,
                                callRequest,
                                audioSrc: audioRef.current?.src
                            });
                        }
                    } else if (callRequest.type === 'voice' || callRequest.type === 'video') {
                        console.log('Получен запрос на звонок:', callRequest);
                        handleIncomingCall(callRequest);
                    } else {
                        console.log('Получено сообщение с неизвестным типом:', callRequest);
                    }
                });
            },
            onWebSocketError: (error) => {
                console.error('Ошибка WebSocket для звонков:', error);
            },
        });
        stompClient.current.activate();

        return () => {
            stompClient.current.deactivate();
            console.log('WebSocket для звонков отключён');
        };
    }, [currentUserId]);

    const handleIncomingCall = async (callRequest) => {
        console.log("Входящий запрос на звонок:", callRequest);
        callRequestRef.current = {
            roomId: callRequest.roomId,
            callerId: callRequest.callerId,
            recipientId: callRequest.recipientId,
            type: callRequest.type
        };
        console.log('callRequestRef установлен:', callRequestRef.current);
        setIncomingCallWithLog(callRequest);
        const callerId = callRequest.callerId;
        try {
            console.log('Начало fetch для callerId:', callerId);
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`);
            console.log('fetch завершен, status:', response.status);
            if (response.ok) {
                const callerData = await response.json();
                console.log('Данные звонящего:', callerData);
                setIncomingCallWithLog({
                    ...callRequest,
                    callerName: callerData.username,
                    avatar: callerData.avatar
                });
            } else {
                console.error('Ошибка при получении данных звонящего:', response.status);
                setIncomingCallWithLog({
                    ...callRequest,
                    callerName: 'Unknown',
                    avatar: null
                });
            }
        } catch (error) {
            console.error('Ошибка при получении данных звонящего:', error);
            setIncomingCallWithLog({
                ...callRequest,
                callerName: 'Unknown',
                avatar: null
            });
        }
    };

    const acceptCall = () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;

        console.log('Function acceptCall in UserProfile.js working...');
        console.log("Incoming call accepted:", incomingCall);

        const callerId = incomingCall.callerId;
        const recipientId = currentUserId;

        const fetchUserNames = async () => {
            try {
                const callerResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`);
                const recipientResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${recipientId}`);

                if (callerResponse.ok && recipientResponse.ok) {
                    const callerData = await callerResponse.json();
                    const recipientData = await recipientResponse.json();

                    sendSignal({
                        from: currentUserId,
                        signal: { type: 'acceptCall' }
                    });

                    setIncomingCallWithLog(null);
                    callRequestRef.current = null;

                    const route = incomingCall.type === 'video' ? `/video-call/${incomingCall.roomId}` : `/voice-call/${incomingCall.roomId}`;
                    navigate(route, {
                        state: {
                            incomingCall,
                            isCaller: false,
                            currentUserId: currentUserId,
                            callerName: callerData.username,
                            recipientName: recipientData.username
                        }
                    });
                    toast.success(incomingCall.type === 'video' ? 'Video call accepted' : 'Voice call accepted');
                } else {
                    console.error('Error fetching user names');
                    toast.error('Failed to fetch user data');
                }
            } catch (error) {
                console.error('Error fetching user names:', error);
                toast.error('Error fetching user data');
            }
        };

        fetchUserNames();
    };

    const rejectCall = () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;

        setIncomingCallWithLog(null);
        callRequestRef.current = null;
        sendSignal({
            from: currentUserId,
            signal: { type: 'rejectCall' }
        });
        toast.info(incomingCall.type === 'video' ? 'Video call rejected' : 'Voice call rejected');
    };

    const sendSignal = (signalMessage) => {
        console.log('Function sendSignal in UserProfile.js working...');
        console.log("Sending signal:", JSON.stringify(signalMessage, null, 2));
        const roomId = incomingCall.roomId;
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`,
            body: JSON.stringify(signalMessage),
        });
        console.log('Signal sent successfully.');
    };

    const handleCall = async () => {
        const roomId = `room-${currentUserId}-${userId}`;
        console.log("Room ID:", roomId);

        console.log("Voice Call Request:");
        console.log("callerId - currentUserId:", currentUserId);
        console.log("recipientId - userId:", userId);
        const callRequest = {
            callerId: currentUserId,
            recipientId: parseInt(userId),
            roomId: roomId,
            callerName: localStorage.getItem('username') || 'Unknown',
            type: 'voice'
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
                        callerName: callerData.username,
                        recipientName: user.username
                    }
                });
            } else {
                toast.error('Failed to initiate voice call');
            }
        } catch (error) {
            console.error('Error initiating voice call:', error);
            toast.error('Error initiating voice call');
        }
    };

    const handleVideoCall = async () => {
        const roomId = `room-${currentUserId}-${userId}`;
        console.log("Room ID:", roomId);

        console.log("Video Call Request:");
        console.log("callerId - currentUserId:", currentUserId);
        console.log("recipientId - userId:", userId);
        const callRequest = {
            callerId: currentUserId,
            recipientId: parseInt(userId),
            roomId: roomId,
            callerName: localStorage.getItem('username') || 'Unknown',
            type: 'video'
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

                toast.success(`Video calling ${user.username}...`);
                navigate(`/video-call/${roomId}`, {
                    state: {
                        incomingCall: callRequest,
                        isCaller: true,
                        currentUserId: currentUserId,
                        callerName: callerData.username,
                        recipientName: user.username
                    }
                });
            } else {
                toast.error('Failed to initiate video call');
            }
        } catch (error) {
            console.error('Error initiating video call:', error);
            toast.error('Error initiating video call');
        }
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
        const currentUsername = localStorage.getItem('username');
        const chatName = `${user.username}_${currentUsername}`;

        if (currentUserId && userId !== String(currentUserId)) {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/chats/check?name1=${user.username}&name2=${currentUsername}`);
                if (!response.ok) {
                    throw new Error(`HTTP error! Status: ${response.status}`);
                }

                const data = await response.json();
                const chatId = data.chatId;

                if (chatId !== -1) {
                    const checkResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${currentUserId}/private-chats/${chatId}/exists`);
                    console.log('checkResponse status:', checkResponse.status);

                    if (checkResponse.ok) {
                        const text = await checkResponse.text();
                        console.log('checkResponse text:', text);
                        const exists = text === "Chat exists in user's private chats";
                        if (exists) {
                            navigate(`/private-chat/${chatId}`);
                            toast.success(`Navigating to chat with ${user.username}!`);
                        } else {
                            console.error('Unexpected response for OK status:', text);
                            toast.error('Unexpected server response');
                        }
                    } else if (checkResponse.status === 404) {
                        console.log('Чат не существует у текущего пользователя, добавляем его');
                        const addResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${currentUserId}/private-chats/${chatId}/add`, {
                            method: 'POST',
                        });
                        console.log('addResponse status:', addResponse.status, 'text:', await addResponse.text());

                        if (addResponse.ok) {
                            navigate(`/private-chat/${chatId}`);
                            toast.success(`Navigating to chat with ${user.username}!`);
                        } else {
                            toast.error('Failed to add chat to user');
                        }
                    } else {
                        console.log('checkResponse error text:', await checkResponse.text());
                        toast.error('Error checking chat existence');
                    }
                } else {
                    const newChat = {
                        name: chatName,
                        private: true
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

    const handlePersonalChats = () => {
        const currentUserName = localStorage.getItem('username');
        navigate(`/user/${currentUserId}/personal-chats`, { state: { currentUserName } });
    };

    const toggleSettingsMenu = (event) => {
        event.stopPropagation();
        setSettingsMenuVisible(prevVisible => !prevVisible);
    };

    const handleDeleteAccount = async () => {
        console.log('Delete account clicked');
        const confirmed = window.confirm("Are you sure you want to delete your account?");
        if (confirmed) {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${currentUserId}`, {
                    method: 'DELETE',
                });

                if (response.ok) {
                    console.log('User deleted successfully');
                    localStorage.removeItem('userId');
                    localStorage.removeItem('username');
                    navigate('/');
                    toast.success('Account deleted successfully!');
                } else {
                    console.error('Failed to delete user:', response.statusText);
                    toast.error('Failed to delete account');
                }
            } catch (error) {
                console.error('Error deleting user:', error);
                toast.error('Error deleting account');
            }
        }
    };

    if (error) return <div className="error-message">{error}</div>;
    if (!user) return <div>Loading...</div>;

    return (
        <div className="user-profile-container">
            <ToastContainer position="top-right" autoClose={5000} hideProgressBar={false} newestOnTop={false} closeOnClick rtl={false} pauseOnFocusLoss draggable pauseOnHover />
            <audio ref={messageAudioRef}>
                <source src="https://igor7070.github.io/Messenger/zvuk-dlya-soobsen.mp3" type="audio/mpeg" />
                Ваш браузер не поддерживает аудио.
            </audio>
            <h1 className="user-profile-username">{user.username}</h1>
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
                <h2 className="user-info-title">About me:</h2>
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
                        <p className="user-info-description">{info}</p>
                        {userId === String(currentUserId) && (
                            <FaEdit className="edit-icon" onClick={() => setIsEditing(true)} />
                        )}
                    </div>
                )}
            </div>

            {userId === String(currentUserId) && (
                <div className="button-container">
                    <button onClick={handleUpdateUser} className="update-button">Update Profile</button>
                    <button onClick={handlePersonalChats} className="personal-chats-button">
                        Personal Chats
                    </button>
                    <FaEllipsisV className="settings-icon" onClick={toggleSettingsMenu} />
                    {settingsMenuVisible && (
                        <div className="settings-menu">
                            <button onClick={handleDeleteAccount}>Delete account</button>
                        </div>
                    )}
                </div>
            )}

            {currentUserId && userId !== String(currentUserId) && (
                <button onClick={handleSendMessage} className="send-message-button">Send Message</button>
            )}

            {currentUserId && userId !== String(currentUserId) && (
                <div className="call-buttons-container">
                    <button onClick={handleCall} className="voice-call-button">
                        <FaPhone /> Voice Call
                    </button>
                    <button onClick={handleVideoCall} className="video-call-button">
                        <FaVideo /> Video Call
                    </button>
                </div>
            )}

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

            {incomingCall && (
                <IncomingCallNotification
                    callerId={incomingCall.callerId}
                    callerName={incomingCall.callerName || 'Unknown'}
                    avatar={incomingCall.avatar}
                    callType={incomingCall.type} // Передаем тип звонка
                    onAccept={acceptCall}
                    onReject={rejectCall}
                />
            )}
        </div>
    );
};

export default UserProfile;