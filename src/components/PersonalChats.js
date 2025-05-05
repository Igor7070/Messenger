import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import '../styles/PersonalChats.css';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import IncomingCallNotification from './IncomingCallNotification';
import { useUserInteraction } from './UserInteractionContext';
import { getMessages } from '../api/messages';
import { format, isToday, parseISO } from 'date-fns';

const PersonalChats = () => {
    const { userId } = useParams();
    const navigate = useNavigate();
    const location = useLocation();
    const [privateChats, setPrivateChats] = useState([]);
    const [chatPartners, setChatPartners] = useState({});
    const [contextMenu, setContextMenu] = useState({ visible: false, chatId: null, x: 0, y: 0 });
    const [client, setClient] = useState(null);
    const [incomingCall, setIncomingCall] = useState(null);
    const [shouldRefreshChats, setShouldRefreshChats] = useState(true);
    const { userInteracted, handleInteraction } = useUserInteraction();
    const stompClient = useRef(null);
    const callRequestRef = useRef(null); // Добавляем useRef для хранения данных вызова
    const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/melodiya_kovboy.mp3'));
    audioRef.current.loop = true;
    const messageAudioRef = useRef(null);

    const currentUserName = location.state?.currentUserName || 'User';
    const currentUserId = userId;

    // Отладка setIncomingCall
    const setIncomingCallWithLog = (value) => {
        console.log('Вызов setIncomingCall:', value);
        console.log('Текущее incomingCall:', incomingCall);
        console.log('Текущее callRequestRef:', callRequestRef.current);
        console.log('Стек вызовов:', new Error().stack);
        setIncomingCall(value);
    };

    // Отладка изменений incomingCall
    useEffect(() => {
        console.log('Состояние incomingCall изменилось:', incomingCall);
    }, [incomingCall]);

    // Форматирование временной метки
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

    // WebSocket для уведомлений
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

                    toast.info(notification, {
                        autoClose: false,
                        onClick: () => {
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

                    // Обновляем список чатов при новом сообщении
                    if (chatId) {
                        setShouldRefreshChats(true);
                    }
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

    // Обработка клика для userInteracted
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
                console.error('Autoplay was prevented:', error);
                toast.warn('Please interact with the page to enable call audio.');
            });
        } else {
            audioRef.current.pause();
            audioRef.current.currentTime = 0;
        }
    }, [incomingCall, userInteracted]);

    // WebSocket для звонков
    useEffect(() => {
        if (!currentUserId) return;

        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            connectTimeout: 10000,
            onConnect: () => {
                console.log('Connected to WebSocket in PersonalChats');
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
                        // Закрываем уведомление, если roomId совпадает или есть звук
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
                            console.log('Отправляем тост: Звонящий отменил вызов.');
                            toast.info('Звонящий отменил вызов.');
                        } else {
                            console.log('Нет активного вызова или звука:', {
                                incomingCall,
                                callRequestRef: callRequestRef.current,
                                callRequest,
                                audioSrc: audioRef.current?.src
                            });
                        }
                    } else if (callRequest.type === 'call' || !callRequest.type) {
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
        console.log('Входящий запрос на звонок:', callRequest);
        // Сохраняем данные вызова в callRequestRef
        callRequestRef.current = {
            roomId: callRequest.roomId,
            callerId: callRequest.callerId,
            recipientId: callRequest.recipientId
        };
        console.log('callRequestRef установлен:', callRequestRef.current);
        setIncomingCallWithLog(callRequest);
        try {
            console.log('Начало fetch для callerId:', callRequest.callerId);
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${callRequest.callerId}`);
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

        console.log('Function acceptCall in PersonalChats working...');
        console.log('Incoming call accepted:', incomingCall);

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
                        signal: { type: 'acceptCall' },
                    });

                    setIncomingCallWithLog(null);
                    callRequestRef.current = null;

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
            signal: { type: 'rejectCall' },
        });
    };

    const sendSignal = (signalMessage) => {
        console.log('Function sendSignal in PersonalChats working...');
        console.log('Sending signal:', JSON.stringify(signalMessage, null, 2));
        const roomId = incomingCall.roomId;
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`,
            body: JSON.stringify(signalMessage),
        });
        console.log('Signal sent successfully.');
    };

    // Получение списка чатов и обновление
    useEffect(() => {
        const fetchPrivateChats = async () => {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}/private-chats`);
                if (response.ok) {
                    const chats = await response.json();
                    setPrivateChats(chats);
                    fetchChatPartners(chats);
                    fetchChatMessages(chats);
                } else {
                    console.error('Failed to fetch private chats:', response.statusText);
                }
            } catch (error) {
                console.error('Error fetching private chats:', error);
            }
        };

        if (shouldRefreshChats) {
            fetchPrivateChats();
            setShouldRefreshChats(false);
        }
    }, [userId, shouldRefreshChats]);

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

    const fetchChatMessages = async (chats) => {
        const updatedChats = [...chats];
        for (const chat of updatedChats) {
            try {
                const messages = await getMessages(chat.id);
                if (messages.length > 0) {
                    const lastMessage = messages[messages.length - 1];
                    chat.lastMessage = lastMessage.content || 'No messages yet';
                    chat.timestamp = lastMessage.timestamp || '';
                    chat.unreadCount = messages.filter(
                        (msg) => !msg.read_status && msg.sender.id !== Number(userId)
                    ).length;
                } else {
                    chat.lastMessage = 'No messages yet';
                    chat.timestamp = '';
                    chat.unreadCount = 0;
                }
            } catch (error) {
                console.error(`Error fetching messages for chat ${chat.id}:`, error);
                chat.lastMessage = 'No messages yet';
                chat.timestamp = '';
                chat.unreadCount = 0;
            }
        }
        setPrivateChats(updatedChats);
    };

    const handleChatClick = (chatId) => {
        setShouldRefreshChats(true);
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
                            <div className="chat-avatar-container">
                                {avatarSrc ? (
                                    <img src={avatarSrc} alt={`${partnerName} avatar`} className="chat-avatar" />
                                ) : (
                                    <div className="chat-avatar">
                                        {initials}
                                    </div>
                                )}
                            </div>
                            <div className="chat-info">
                                <div className="chat-header">
                                    <span className="chat-name-list-item">{partnerName}</span>
                                    {chat.unreadCount > 0 && (
                                        <span className="unread-indicator" aria-label={`${chat.unreadCount} unread messages`}>
                                            {chat.unreadCount}
                                        </span>
                                    )}
                                </div>
                                <div className="chat-footer">
                                    <span className="last-message">{chat.lastMessage}</span>
                                    <span className="timestamp">{formatTimestamp(chat.timestamp)}</span>
                                </div>
                            </div>
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

            {incomingCall && (
                <IncomingCallNotification
                    callerId={incomingCall.callerId}
                    callerName={incomingCall.callerName || 'Unknown'}
                    avatar={incomingCall.avatar}
                    onAccept={acceptCall}
                    onReject={rejectCall}
                />
            )}
        </div>
    );
};

export default PersonalChats;