import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import '../styles/PersonalChats.css';
import { toast } from 'react-toastify';
import IncomingCallNotification from './IncomingCallNotification';
import { getMessages } from '../api/messages';
import { format, isToday, parseISO } from 'date-fns';
import { useUserInteraction } from '../context/UserInteractionContext';
import { useUserStatusContext } from '../context/UserStatusContext';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import { usePrivateMessageNotification } from '../context/PrivateMessageNotificationContext';
import AssistantIcon from './AssistantIcon';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const PersonalChats = () => {
    const { userId } = useParams();
    const navigate = useNavigate();
    const location = useLocation();
    const [privateChats, setPrivateChats] = useState([]);
    const [chatPartners, setChatPartners] = useState({});
    const [contextMenu, setContextMenu] = useState({ visible: false, chatId: null, x: 0, y: 0 });
    const [shouldRefreshChats, setShouldRefreshChats] = useState(true);
    const { userInteracted, handleInteraction } = useUserInteraction();
    const currentUserName = location.state?.currentUserName || 'User';
    const currentUserId = userId;
    const { usersStatus } = useUserStatusContext();
    const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
    const { setCurrentUserId: setNotificationUserId } = usePrivateMessageNotification();
    const stompClientRef = useRef(null);

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

    // Сброс тостов и тестовый тост
    //useEffect(() => {
        //toast.dismiss(); // Очищаем очередь тостов
        //console.log('Testing toast rendering in PersonalChats');
        //toast.info('Test toast in PersonalChats', { toastId: 'test-personalchats', autoClose: 3000 });
    //}, []);

    // Синхронизация currentUserId с контекстом
    useEffect(() => {
        if (currentUserId) {
            setContextUserId(currentUserId);
            setNotificationUserId(currentUserId);
        }
    }, [currentUserId, setContextUserId, setNotificationUserId]);

    // Обработка клика для userInteracted
    useEffect(() => {
        const handleClick = () => {
            handleInteraction();
            setContextMenu({ ...contextMenu, visible: false });
        };
        document.addEventListener('click', handleClick);
        return () => {
            document.removeEventListener('click', handleClick);
        };
    }, [handleInteraction, contextMenu]);

    // Инициализация WebSocket для получения сообщений
    useEffect(() => {
        if (!privateChats.length || !currentUserId) return;

        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket for private chats');

                privateChats.forEach((chat) => {
                    stompClient.subscribe(`/topic/chat/${chat.id}/messages`, (message) => {
                        const newMessage = JSON.parse(message.body);
                        console.log('New message received for chat', chat.id, ':', newMessage);

                        // Обновляем список чатов с новым сообщением
                        setPrivateChats((prevChats) =>
                            prevChats.map((prevChat) => {
                                if (prevChat.id === chat.id) {
                                    const isOwnMessage = newMessage.sender.id === Number(currentUserId);
                                    return {
                                        ...prevChat,
                                        lastMessage: newMessage.content || 'No messages yet',
                                        timestamp: newMessage.timestamp || '',
                                        unreadCount: isOwnMessage
                                            ? prevChat.unreadCount
                                            : prevChat.unreadCount + 1,
                                    };
                                }
                                return prevChat;
                            })
                        );
                    });
                });
            },
            onWebSocketError: (error) => {
                console.error('WebSocket error:', error);
            },
            onStompError: (frame) => {
                console.error('STOMP error:', frame);
            },
        });

        stompClientRef.current = stompClient;
        stompClient.activate();

        return () => {
            if (stompClientRef.current) {
                stompClientRef.current.deactivate();
                console.log('WebSocket for private chats disconnected');
            }
        };
    }, [privateChats, currentUserId]);

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
                    //toast.error('Failed to fetch private chats');
                }
            } catch (error) {
                console.error('Error fetching private chats:', error);
                toast.error('Error fetching private chats');
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

    const handleChatClick = (chat) => {
        setShouldRefreshChats(true);
        const route = chat.isSecret ? `/secret-chat/${chat.id}` : `/private-chat/${chat.id}`;
        navigate(route);
    };

    const getChatPartnerName = (chatName) => {
        const parts = chatName.split('_');
        if (parts.length === 3 && parts[2] === 'secret') {
            return parts[0] === currentUserName ? parts[1] : parts[0];
        } else if (parts.length === 2) {
            return parts[0] === currentUserName ? parts[1] : parts[0];
        }
        console.warn('No partner name found in chatName:', chatName);
        return '';
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
                toast.success('Chat deleted successfully');
            } else {
                console.error('Failed to delete chat from user list:', response.statusText);
                toast.error('Failed to delete chat');
            }
        } catch (error) {
            console.error('Error deleting chat:', error);
            toast.error('Error deleting chat');
        }
    };

    const closeContextMenu = () => {
        setContextMenu({ ...contextMenu, visible: false });
    };

    return (
        <>
        <div className="personal-chats-container" onClick={closeContextMenu}>
            <h1 className="custom-h1">{`${currentUserName}'s private chats`}</h1>
            <ul>
                {privateChats.map(chat => {
                    const partner = chatPartners[chat.id] || {};
                    const avatarSrc = partner.avatar ? `https://unlimitedpossibilities12.org${partner.avatar}` : null;
                    const initials = partner.username ? partner.username.charAt(0).toUpperCase() : '';
                    const partnerName = getChatPartnerName(chat.name);

                    return (
                        <li
                            key={chat.id}
                            onClick={() => handleChatClick(chat)}
                            onContextMenu={(event) => handleContextMenu(event, chat.id)}
                            className="chat-item1"
                        >
                            <div className="chat-avatar-container">
                                {avatarSrc ? (
                                    <img src={avatarSrc} alt={`${partnerName} avatar`} className="chat-avatar" />
                                ) : (
                                    <div className="chat-avatar">{initials}</div>
                                )}
                                {usersStatus[partner.id]?.online && (
                                    <span className="chat-status-indicator-online"></span>
                                )}
                            </div>
                            <div className="chat-info">
                                <div className="chat-header">
                                    <span className="chat-name-list-item">
                                        {chat.isSecret && <span className="secret-chat-icon">🔒 </span>}
                                        {partnerName}
                                    </span>
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
                <div className="context-menu" style={{ top: contextMenu.y, left: contextMenu.x }}>
                    <button onClick={handleDeleteChat}>Delete chat</button>
                </div>
            )}

            {incomingCall && (
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
        {/* Иконка вне контейнера — fixed относительно всего экрана */}
        <AssistantIcon size="large" className="fixed-app-bottom-right" />
        </>
    );
};

export default PersonalChats;