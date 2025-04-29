import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import '../styles/PersonalChats.css';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import IncomingCallNotification from './IncomingCallNotification';
import { useUserInteraction } from './UserInteractionContext';

const PersonalChats = () => {
    const { userId } = useParams();
    const navigate = useNavigate();
    const location = useLocation();
    const [privateChats, setPrivateChats] = useState([]);
    const [chatPartners, setChatPartners] = useState({});
    const [contextMenu, setContextMenu] = useState({ visible: false, chatId: null, x: 0, y: 0 });
    const [client, setClient] = useState(null);
    const [incomingCall, setIncomingCall] = useState(null);
    const { userInteracted, handleInteraction } = useUserInteraction();
    const stompClient = useRef(null);
    const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/melodiya_kovboy.mp3'));
    audioRef.current.loop = true;
    const messageAudioRef = useRef(null); // Для сообщений

    const currentUserName = location.state?.currentUserName || 'User';
    const currentUserId = userId;

    // Уведомления о новых сообщениях
    useEffect(() => {
        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket for notifications');

                stompClient.subscribe(`/topic/notifications/${userId}`, (message) => {
                    const notification = message.body;
                    console.log("Received notification:", notification);

                    const chatIdMatch = notification.match(/\(chatId: (\d+)\)/);
                    const chatId = chatIdMatch ? chatIdMatch[1] : null;

                    // Воспроизведение звука уведомления
                    if (userInteracted && messageAudioRef && messageAudioRef.current) {
                        console.log('Attempting to play message audio');
                        messageAudioRef.current.play()
                            .then(() => console.log('Message audio played successfully'))
                            .catch(error => {
                                console.error('Failed to play message audio:', error);
                                toast.warn('Please interact with the page to enable notification sounds.');
                            });
                    } else {
                        console.log('Audio not played: userInteracted =', userInteracted, 'messageAudioRef =', messageAudioRef?.current);
                    }

                    // Отображение уведомления
                    toast.info(notification, {
                        autoClose: false,
                        onClick: () => {
                            // Остановка звука при клике на тост
                            if (messageAudioRef && messageAudioRef.current) {
                                console.log('Pausing message audio on toast click');
                                messageAudioRef.current.pause();
                                messageAudioRef.current.currentTime = 0;
                            }

                            if (chatId) {
                                navigate(`/private-chat/${chatId}`);
                            } else {
                                console.error("chatId не найден в уведомлении");
                                toast.error('Invalid notification format');
                            }
                        },
                    });
                });
            },
            onWebSocketError: (error) => {
                console.error('WebSocket error:', error);
            },
        });

        setClient(stompClient);
        stompClient.activate();

        return () => {
            stompClient.deactivate();
        };
    }, [userId, userInteracted, navigate]);

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
                
    // Управление звуком звонка
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
            
    // WebSocket для звонков
    useEffect(() => {
        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            onConnect: () => {
                console.log('Connected to WebSocket in PersonalChats');
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
        console.log("Incoming call request:", callRequest);
        setIncomingCall(callRequest);
                
        const callerId = callRequest.callerId;
                
        try {
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`);
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
            
    const acceptCall = () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;
            
        console.log('Function acceptCall in PersonalChats working...');
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
                        signal: {
                            type: 'acceptCall'
                        }
                    });
                                
                    navigate(`/voice-call/${incomingCall.roomId}`, { 
                        state: { 
                            incomingCall, 
                            isCaller: false, 
                            currentUserId: currentUserId, 
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
                
        fetchUserNames();
    };
            
    const rejectCall = () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;
            
        setIncomingCall(null);
        sendSignal({
            from: currentUserId,
            signal: {
                type: 'rejectCall'
            }
        });
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

    useEffect(() => {
        const fetchPrivateChats = async () => {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}/private-chats`);
                if (response.ok) {
                    const chats = await response.json();
                    setPrivateChats(chats);
                    fetchChatPartners(chats);
                } else {
                    console.error('Failed to fetch private chats:', response.statusText);
                }
            } catch (error) {
                console.error('Error fetching private chats:', error);
            }
        };

        fetchPrivateChats();
    }, [userId]);

    const fetchChatPartners = async (chats) => {
        const partners = {};
        for (const chat of chats) {
            const partnerName = getChatPartnerName(chat.name);
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${partnerName}`);
                if (response.ok) {
                    const userData = await response.json();
                    partners[chat.id] = userData;
                } else {
                    console.error(`Failed to fetch user ${partnerName}:`, response.statusText);
                }
            } catch (error) {
                console.error(`Error fetching user ${partnerName}:`, error);
            }
        }
        setChatPartners(partners);
    };

    const handleChatClick = (chatId) => {
        navigate(`/private-chat/${chatId}`);
    };

    const getChatPartnerName = (chatName) => {
        const names = chatName.split('_');
        return names.find(name => name !== currentUserName);
    };

    const handleContextMenu = (event, chatId) => {
        event.preventDefault();
        setContextMenu({
            visible: true,
            chatId: chatId,
            x: event.pageX,
            y: event.pageY,
        });
    };

    const handleDeleteChat = async () => {
        const confirmDelete = window.confirm("Are you sure you want to delete this chat?");
        if (confirmDelete) {
            await deleteChat(contextMenu.chatId);
        }
        setContextMenu({ ...contextMenu, visible: false });
    };

    const deleteChat = async (chatId) => {
        try {
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}/private-chats/${chatId}`, {
                method: 'DELETE',
            });
            
            if (response.ok) {
                setPrivateChats(prevChats => prevChats.filter(chat => chat.id !== chatId));
                console.log("Chat deleted from user list successfully");

                const checkResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/private-chats/check/${chatId}`, {
                    method: 'DELETE',
                });

                const result = await checkResponse.text();
                console.log(result);
            } else {
                console.error('Failed to delete chat from user list:', response.statusText);
            }
        } catch (error) {
            console.error('Error deleting chat:', error);
        }
    };

    const closeContextMenu = () => {
        setContextMenu({ ...contextMenu, visible: false });
    };

    return (
        <div className="personal-chats-container" onClick={closeContextMenu}>
            <h1 className="custom-h1">{`${currentUserName}'s private chats`}</h1>
            <ul>
                {privateChats.map(chat => {
                    const partner = chatPartners[chat.id] || {};
                    const avatarSrc = partner.avatar ? `https://unlimitedpossibilities12.org${partner.avatar}` : null;
                    const initials = partner.username ? partner.username.charAt(0).toUpperCase() : '';
                    const partnerName = getChatPartnerName(chat.name);

                    return (
                        <li key={chat.id} onClick={() => handleChatClick(chat.id)} 
                            onContextMenu={(event) => handleContextMenu(event, chat.id)} 
                            className="chat-item1">
                            {avatarSrc ? (
                                <img src={avatarSrc} alt={`${partnerName} avatar`} className="chat-avatar" />
                            ) : (
                                <div className="chat-avatar">
                                    {initials}
                                </div>
                            )}
                            <span className="chat-name-list-item">{partnerName}</span>
                        </li>
                    );
                })}
            </ul>

            {contextMenu.visible && (
                <div 
                    className="context-menu" 
                    style={{ top: contextMenu.y, left: contextMenu.x }}
                >
                    <button onClick={handleDeleteChat}>Delete chat</button>
                </div>
            )}

            <ToastContainer />
            <audio ref={messageAudioRef}>
                <source src="https://igor7070.github.io/Messenger/zvuk-dlya-soobsen.mp3" type="audio/mpeg" />
                Ваш браузер не поддерживает аудио.
            </audio>
            {/* Тестовая кнопка для проверки звука (раскомментируйте для теста)
            <button onClick={() => {
                if (messageAudioRef.current) {
                    console.log('Testing message audio');
                    messageAudioRef.current.play()
                        .then(() => console.log('Message audio played successfully'))
                        .catch(error => console.error('Message audio failed:', error));
                } else {
                    console.error('messageAudioRef is not defined');
                }
            }}>
                Test Message Sound
            </button>
            */}
            
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

export default PersonalChats;