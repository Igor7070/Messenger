import React, { useState, useEffect } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import '../styles/PersonalChats.css';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import IncomingCallNotification from './IncomingCallNotification'; // Импорт компонента уведомления
import { useUserInteraction } from './UserInteractionContext';

const PersonalChats = () => {
    const { userId } = useParams();
    const navigate = useNavigate();
    const location = useLocation();
    const [privateChats, setPrivateChats] = useState([]);
    const [chatPartners, setChatPartners] = useState({});
    const [contextMenu, setContextMenu] = useState({ visible: false, chatId: null, x: 0, y: 0 });
    const [client, setClient] = useState(null);
    const [incomingCall, setIncomingCall] = useState(null); // Состояние для входящего звонка
    const { userInteracted, handleInteraction } = useUserInteraction();
    const stompClient = useRef(null); // Используем useRef для stompClient
    const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/melodiya_kovboy.mp3')); // Инициализация
    audioRef.current.loop = true; // Устанавливаем зацикливание

    const currentUserName = location.state?.currentUserName || 'User';

    // Уведомления о новых сообщениях
    useEffect(() => {
        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');

                // Подписка на уведомления
                stompClient.subscribe(`/topic/notifications/${userId}`, (message) => {
                    const notification = message.body;
                    console.log("Received notification:", notification);

                    // Используйте регулярное выражение для извлечения chatId
                    const chatIdMatch = notification.match(/\(chatId: (\d+)\)/);
                    const chatId = chatIdMatch ? chatIdMatch[1] : null; // Извлекаем chatId, если найдено

                    // Отображение уведомления
                    toast.info(notification, {
                        autoClose: false,
                        onClick: () => {
                            if (chatId) {
                                // Переход к личному чату при нажатии на уведомление
                                navigate(`/private-chat/${chatId}`);
                            } else {
                                console.error("chatId не найден в уведомлении");
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
        setContextMenu({ ...contextMenu, visible: false }); // Скрываем контекстное меню
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

export default PersonalChats;