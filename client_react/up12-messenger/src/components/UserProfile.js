import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import '../styles/UserProfile.css';
import '../styles/ProfilePanel.css';
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

const UserProfile = ({ onClose = () => {} }) => {
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

    useEffect(() => {
        const onKey = (event) => { if (event.key === 'Escape') onClose(); };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [onClose]);

    // Снимок сохраняется сразу, как только его выбрали: отдельной кнопки
    // «Update Profile» на карточке больше нет, а выбранный и не сохранённый
    // снимок пропадал бы молча.
    const handleAvatarChange = (e) => {
        const file = e.target.files && e.target.files[0];
        if (!file) return;
        setNewAvatar(file);
        saveProfile(file);
    };

    const saveProfile = async (avatarFile = newAvatar) => {
        const formData = new FormData();
        formData.append('info', info);
        if (avatarFile) {
            formData.append('avatar', avatarFile);
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

    const handleUpdateUser = () => saveProfile();

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
                toast.error('Could not send the message. Please try again.');
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
        localStorage.removeItem('authToken');
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

                toast.success(`Calling ${user.username} — protected call 🔒`, { autoClose: 3000 });

                navigate(`/voice-call/${roomId}`, {
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

    const isMe = Boolean(currentUserId) && userId === String(currentUserId);
    const online = Boolean(usersStatus[userId]?.online);

    return (
        <div className="up-profile-backdrop" onClick={onClose} role="presentation">
            <div
                className="up-profile-panel"
                onClick={(event) => event.stopPropagation()}
                role="dialog"
                aria-label="Profile"
            >
                <header className={`up-profile-head ${isEditing ? 'editing' : ''}`}>
                    {/* В правке шапка меняется так же, как у телеграма: слева
                        стрелка назад и название, справа крестик. Карандаш
                        стоит там же, где у них, — отдельного экрана не нужно,
                        меняются всего две вещи: снимок и описание. */}
                    {isEditing && (
                        <>
                            <button
                                type="button"
                                className="up-profile-icon"
                                title="Back"
                                onClick={() => { setInfo(user.info || ''); setIsEditing(false); }}
                            >
                                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                    <path d="M15 5l-7 7 7 7" />
                                </svg>
                            </button>
                            <h3 className="up-profile-head-title">Edit profile</h3>
                        </>
                    )}
                    {isMe && !isEditing && (
                        <button
                            type="button"
                            className="up-profile-icon"
                            title="Edit"
                            onClick={() => setIsEditing(true)}
                        >
                            <FaEdit />
                        </button>
                    )}
                    {!isEditing && (
                        <button
                            type="button"
                            className="up-profile-icon"
                            title="More"
                            onClick={toggleSettingsMenu}
                        >
                            <FaEllipsisV />
                        </button>
                    )}
                    <button type="button" className="up-profile-icon" title="Close" onClick={onClose}>
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                            <path d="M6 6l12 12M18 6L6 18" />
                        </svg>
                    </button>

                    {settingsMenuVisible && (
                        <div className="up-profile-menu">
                            {isMe ? (
                                <button type="button" className="danger" onClick={handleDeleteAccount}>
                                    Delete account
                                </button>
                            ) : (
                                <>
                                    <button type="button" onClick={handleSecretChat}>Secret chat</button>
                                    <button type="button" onClick={handleSecureCall}>Secure call</button>
                                </>
                            )}
                        </div>
                    )}
                </header>

                {/* Снимок, имя и присутствие — как в карточке на телефоне и в
                    окне своего профиля у телеграма */}
                <div className="up-profile-hero">
                    <div className="up-profile-avatar-wrap">
                        {user.avatar ? (
                            <img
                                src={`https://unlimitedpossibilities12.org${user.avatar}`}
                                alt=""
                                className="up-profile-avatar"
                                onClick={openModal}
                            />
                        ) : (
                            <div className="up-profile-avatar up-profile-avatar-letter">
                                {(user.username || '?').charAt(0).toUpperCase()}
                            </div>
                        )}
                        {online && <span className="up-profile-online" title="Online" />}
                        {isMe && (
                            <label className="up-profile-camera" title="Change the photograph">
                                <input type="file" accept="image/*" onChange={handleAvatarChange} />
                                <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                                    <path d="M9 4l-1.2 2H4.5A1.5 1.5 0 003 7.5v11A1.5 1.5 0 004.5 20h15a1.5 1.5 0 001.5-1.5v-11A1.5 1.5 0 0019.5 6h-3.3L15 4H9zm3 4.8a4.7 4.7 0 110 9.4 4.7 4.7 0 010-9.4z" />
                                </svg>
                            </label>
                        )}
                    </div>
                    <h2 className="up-profile-name">{user.username}</h2>
                    <p className={`up-profile-status ${online ? 'online' : ''}`}>
                        {online ? 'Online' : 'Offline'}
                    </p>
                </div>

                {/* Чужая карточка: те же четыре кнопки, что на телефоне */}
                {!isMe && (
                    <div className="up-profile-actions">
                        <button
                            type="button"
                            onClick={currentUserId ? handleSendMessage : () => toast.warn('You need to log in to send a message')}
                        >
                            <FaEdit />
                            <span>Message</span>
                        </button>
                        <button
                            type="button"
                            onClick={currentUserId ? handleCall : () => toast.warn('You need to log in to call')}
                        >
                            <FaPhone />
                            <span>Call</span>
                        </button>
                        <button
                            type="button"
                            onClick={currentUserId ? handleTranslateCall : () => toast.warn('You need to log in to call')}
                        >
                            <FaLanguage />
                            <span>Translate</span>
                        </button>
                        <button
                            type="button"
                            onClick={currentUserId ? handleVideoCall : () => toast.warn('You need to log in to call')}
                        >
                            <FaVideo />
                            <span>Video</span>
                        </button>
                    </div>
                )}

                <div className="up-profile-rows">
                    {isEditing ? (
                        <>
                            <div className="up-profile-field">
                                <div className="up-profile-field-head">
                                    <span>About</span>
                                    <span className="up-profile-counter">{244 - info.length}</span>
                                </div>
                                <textarea
                                    value={info}
                                    onChange={(event) => setInfo(event.target.value)}
                                    rows={3}
                                    maxLength={244}
                                    autoFocus
                                />
                            </div>
                            <p className="up-profile-hint">
                                Any details, such as what you do or where you are.
                                For example: designer from Kyiv.
                            </p>
                        </>
                    ) : (
                        <div className="up-profile-row">
                            <div className="up-profile-row-body">
                                <p className="up-profile-row-value">{info || '—'}</p>
                                <p className="up-profile-row-label">About</p>
                            </div>
                        </div>
                    )}

                    <div className="up-profile-row">
                        <div className="up-profile-row-body">
                            <p className="up-profile-row-value">@{user.username}</p>
                            <p className="up-profile-row-label">Username</p>
                        </div>
                    </div>
                    {isEditing && (
                        <p className="up-profile-hint">
                            People find you by this name; it cannot be changed.
                        </p>
                    )}
                </div>

                <footer className="up-profile-foot">
                    {isEditing ? (
                        <>
                            <button
                                type="button"
                                className="up-profile-done"
                                onClick={() => { setInfo(user.info || ''); setIsEditing(false); }}
                            >
                                Cancel
                            </button>
                            <button type="button" className="up-profile-save" onClick={handleUpdateUser}>
                                Save
                            </button>
                        </>
                    ) : (
                        <button type="button" className="up-profile-done" onClick={onClose}>Close</button>
                    )}
                </footer>

                {/* Снимок во весь рост */}
                <Modal isOpen={isModalOpen} onRequestClose={closeModal} className="user-profile-modal" overlayClassName="user-profile-modal-overlay">
                    <img
                        src={`https://unlimitedpossibilities12.org/avatars/${avatarFileName}`}
                        alt=""
                        className="user-profile-modal-image"
                        style={{ width: '100%', height: 'auto' }}
                        onClick={closeModal}
                    />
                    <button onClick={closeModal} className="user-profile-modal-close-button">Close</button>
                </Modal>

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
            </div>
        </div>
    );
};

export default UserProfile;