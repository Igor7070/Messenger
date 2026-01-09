import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import '../styles/UserProfile.css';
import { FaEdit, FaEllipsisV, FaPhone, FaVideo, FaLanguage } from 'react-icons/fa';
import { toast } from 'react-toastify';
import Modal from 'react-modal';
import IncomingCallNotification from './IncomingCallNotification';
import { useUserInteraction } from '../context/UserInteractionContext';
import { useUserStatusContext } from '../context/UserStatusContext';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import { usePrivateMessageNotification } from '../context/PrivateMessageNotificationContext';
import { generateChatName } from './SecretPrivateChat';
import AssistantIcon from './AssistantIcon';

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
    const [settingsMenuVisible, setSettingsMenuVisible] = useState(false);
    const { userInteracted, handleInteraction } = useUserInteraction();
    const { usersStatus, disconnectWebSocket } = useUserStatusContext();
    const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
    const { setCurrentUserId: setNotificationUserId } = usePrivateMessageNotification();

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
            const userIdNum = Number(storedUserId);
            setCurrentUserId(userIdNum);
            setContextUserId(userIdNum);
            setNotificationUserId(userIdNum);
        }
    }, [userId, setContextUserId, setNotificationUserId]);

    //useEffect(() => {
        // Сброс всех тостов и тестовый тост
        //toast.dismiss(); // Очищаем очередь тостов
        //console.log('Testing toast rendering in UserProfile');
        //toast.info('Test toast in UserProfile', { toastId: 'test-userprofile', autoClose: 3000 });
    //}, []);

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

    const handleTranslateCall = async () => {
        const roomId = `room-${currentUserId}-${userId}`;
        console.log("Room ID:", roomId);

        console.log("Translate Call Request:");
        console.log("callerId - currentUserId:", currentUserId);
        console.log("recipientId - userId:", userId);
        const callRequest = {
            callerId: currentUserId,
            recipientId: parseInt(userId),
            roomId: roomId,
            callerName: localStorage.getItem('username') || 'Unknown',
            type: 'translate'
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

                toast.success(`Calling ${user.username} with translation...`);
                navigate(`/voice-call-translate/${roomId}`, {
                    state: {
                    incomingCall: callRequest,
                    isCaller: true,
                    currentUserId: currentUserId,
                    callerName: callerData.username,
                    recipientName: user.username
                    }
                });
            } else {
                toast.error('Failed to initiate translate call');
            }
        } catch (error) {
            console.error('Error initiating translate call:', error);
            toast.error('Error initiating translate call');
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
                    disconnectWebSocket();
                    setContextUserId(null);
                    setNotificationUserId(null);
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

    const handleSecretChat = async () => {
        if (!currentUserId) {
            toast.error('You need to create an account or log in');
            return;
        }
        if (String(currentUserId) === userId) {
            toast.error('You cannot start a secret chat with yourself');
            return;
        }

        try {
            const currentUsername = localStorage.getItem('username');
            const targetUsername = user.username;
            const chatName = generateChatName(currentUsername, targetUsername, true);

            // Проверка существующих чатов текущего пользователя
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${currentUserId}/private-chats`);
            if (!response.ok) {
                throw new Error(`Failed to fetch private chats: ${response.status}`);
            }

            const chats = await response.json();
            console.log('Fetched private chats:', chats);

            // Поиск существующего секретного чата
            const existingChat = chats.find(chat => chat.isSecret && chat.name === chatName);
            if (existingChat) {
                navigate(`/secret-chat/${existingChat.id}`);
                toast.success(`Navigating to existing secret chat with ${targetUsername} (chatId: ${existingChat.id})`);
                return;
            }

            // Создание нового секретного чата
            const newChat = {
                name: chatName,
                isSecret: true,
            };
            const createResponse = await fetch('https://unlimitedpossibilities12.org/api/chats', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(newChat),
            });

            if (!createResponse.ok) {
                throw new Error(`Failed to create chat: ${createResponse.status}`);
            }

            const createdChat = await createResponse.json();
            const newChatId = createdChat.id;

            // Привязка чата к обоим пользователям
            const addCurrentUserResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${currentUserId}/private-chats/${newChatId}/add`, {
                method: 'POST',
            });
            if (!addCurrentUserResponse.ok) {
                throw new Error(`Failed to add chat to current user: ${addCurrentUserResponse.status}`);
            }

            const addTargetUserResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}/private-chats/${newChatId}/add`, {
                method: 'POST',
            });
            if (!addTargetUserResponse.ok) {
                throw new Error(`Failed to add chat to target user: ${addTargetUserResponse.status}`);
            }

            navigate(`/secret-chat/${newChatId}`);
            toast.success(`Navigating to secret chat with ${targetUsername}!`);
        } catch (error) {
            console.error('Error creating secret chat:', error);
            toast.error('Error creating secret chat: ' + error.message);
        }
    };

    const handleSecureCall = async () => {
        if (!currentUserId) {
            toast.error('You need to log in');
            return;
        }
        if (String(currentUserId) === userId) {
            toast.error('You cannot call yourself');
            return;
        }

        const roomId = `secure-${currentUserId}-${userId}-${Date.now()}`;

        const callRequest = {
            callerId: currentUserId,
            recipientId: parseInt(userId),
            roomId: roomId,
            callerName: localStorage.getItem('username') || 'Unknown',
            type: 'voice_secure'
        };

        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/webrtc/call', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(callRequest),
            });

            if (response.ok) {
                const callerResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${currentUserId}`);
                const callerData = await callerResponse.json();

                toast.success(`Защищённый звонок ${user.username}... 🔒`, { autoClose: 3000 });

                navigate(`/secure-call/${roomId}`, {
                    state: {
                        incomingCall: callRequest,
                        isCaller: true,
                        currentUserId: currentUserId,
                        callerName: callerData.username,
                        recipientName: user.username,
                        recipientId: parseInt(userId),
                    }
                });
            } else {
                toast.error('Failed to initiate secure call');
            }
        } catch (error) {
            console.error('Error initiating secure call:', error);
            toast.error('Error initiating secure call');
        }
    };

    if (error) return <div className="error-message">{error}</div>;
    if (!user) return <div>Loading...</div>;

    return (
        <div className="user-profile-container">
            <h1 className="user-profile-username">{user.username}</h1>
            <span className="userprofile-status-text">
                <span className={`userprofile-status-label ${usersStatus[userId]?.online ? 'userprofile-online' : 'userprofile-offline'}`}>
                    {usersStatus[userId]?.online ? 'Online' : 'Offline'}
                </span>
            </span>
            <div className="userprofile-avatar-container">
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
                {(usersStatus[userId]?.online) && (
                    <span className="userprofile-status-indicator-online"></span>
                )}
            </div>

            {/* === КНОПКИ ДЛЯ СВОЕЙ СТРАНИЦЫ — ТОЛЬКО ЕСЛИ АВТОРИЗОВАН === */}
            {currentUserId && userId === String(currentUserId) && (
                <div className="userprofile-avatar-input-container">
                    <label className="userprofile-file-label">
                        <input type="file" onChange={handleAvatarChange} className="userprofile-avatar-input" />
                        Select New Avatar
                    </label>
                </div>
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
                        {currentUserId && userId === String(currentUserId) && (
                            <FaEdit className="edit-icon" onClick={() => setIsEditing(true)} />
                        )}
                    </div>
                )}
            </div>

            {/* === КНОПКИ ДЛЯ СВОЕЙ СТРАНИЦЫ — ВИДНЫ ТОЛЬКО АВТОРИЗОВАННЫМ === */}
            {currentUserId && userId === String(currentUserId) && (
                <div className="button-container">
                    <button onClick={handleUpdateUser} className="update-button">Update Profile</button>
                    <button onClick={() => navigate('/calls')} className="calls-button">Calls</button>
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

            {/* === КНОПКИ ДЛЯ ЧУЖОЙ СТРАНИЦЫ — ВИДНЫ ВСЕМ, НО С ТОАСТОМ === */}
            {(!currentUserId || userId !== String(currentUserId)) && (
                <>
                    <button
                        onClick={currentUserId ? handleSendMessage : () => toast.warn("You need to log in to send a message.")}
                        className="send-message-button"
                    >
                        Send Message
                    </button>

                    <div className="call-buttons-container">
                        <div className="standard-call-buttons">
                            <button
                                onClick={currentUserId ? handleCall : () => toast.warn("You need to log in to make a voice call.")}
                                className="voice-call-button"
                            >
                                <FaPhone /> Voice Call
                            </button>
                            <button
                                onClick={currentUserId ? handleVideoCall : () => toast.warn("You need to log in to make a video call.")}
                                className="video-call-button"
                            >
                                <FaVideo /> Video Call
                            </button>
                        </div>
                        <div className="translate-call-container">
                            <button
                                onClick={currentUserId ? handleTranslateCall : () => toast.warn("You need to log in to use translation.")}
                                className="translate-call-button"
                            >
                                <FaLanguage /> Translate Call
                            </button>
                        </div>
                    </div>

                    {/* Меню "..." для чужого профиля — только если авторизован */}
                    {currentUserId && (
                        <>
                            <FaEllipsisV className="settings-icon" onClick={toggleSettingsMenu} />
                            {settingsMenuVisible && (
                                <div className="settings-menu">
                                    <button onClick={handleSecretChat} style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                        <span role="img" aria-label="secret chat">🔒</span> Open Secret Chat
                                    </button>

                                    {/* НОВАЯ КНОПКА — ЗАЩИЩЁННЫЙ ЗВОНОК */}
                                    <button 
                                        onClick={handleSecureCall} 
                                        style={{ 
                                            display: 'flex', 
                                            alignItems: 'center', 
                                            gap: '8px',
                                            marginTop: '8px',
                                            color: '#00ff00',
                                            fontWeight: 'bold'
                                        }}
                                    >
                                        <span role="img" aria-label="secure call">🔒📞</span> Secure Call (E2EE)
                                    </button>
                                </div>
                            )}
                        </>
                    )}
                </>
            )}

            {/* === МОДАЛКА АВАТАРА === */}
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

            {/* === ВХОДЯЩИЙ ЗВОНОК === */}
            {incomingCall && currentUserId && (
                <IncomingCallNotification
                    callerId={incomingCall.callerId}
                    callerName={incomingCall.callerName || 'Unknown'}
                    avatar={incomingCall.avatar}
                    callType={incomingCall.type}
                    onAccept={() => acceptCall(navigate)}
                    onReject={rejectCall}
                />
            )}

            {/* Большая иконка ассистента — всегда в правом верхнем углу */}
            <AssistantIcon size="large" position="top-left" />
        </div>
    );
};

export default UserProfile;