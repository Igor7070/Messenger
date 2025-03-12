import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages } from '../api/messages';
import '../styles/Messenger.css';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import { useParams, useNavigate } from 'react-router-dom';
import { getChatById } from '../api/chats';
import IncomingCallNotification from './IncomingCallNotification'; // Импорт компонента уведомления
import { useUserInteraction } from './UserInteractionContext';

const PrivateChat = () => {
    const [messages, setMessages] = useState([]);
    const [content, setContent] = useState('');
    const [selectedFile, setSelectedFile] = useState(null);
    const [userId, setUserId] = useState(Number(localStorage.getItem('userId')));
    const [username, setUsername] = useState(localStorage.getItem('username'));
    const [client, setClient] = useState(null);
    const [chatId, setChatId] = useState(null);
    const [chatName, setChatName] = useState('');
    const [editingMessageId, setEditingMessageId] = useState(null);
    const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, message: null });
    const messagesEndRef = useRef(null);
    const fileInputRef = useRef(null);
    const { chatId: chatIdParam } = useParams();

    const [currentUserId, setCurrentUserId] = useState(null);
    const [incomingCall, setIncomingCall] = useState(null); // Состояние для входящего звонка
    const { userInteracted, handleInteraction } = useUserInteraction();
    const stompClient = useRef(null); // Используем useRef для stompClient
    const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/melodiya_kovboy.mp3')); // Инициализация
    audioRef.current.loop = true; // Устанавливаем зацикливание
    const navigate = useNavigate();

    useEffect(() => {
        const fetchChatData = async () => {
            console.log('fetchChatData is working...');
            if (!chatIdParam || !username) return;

            try {
                const chatData = await getChatById(chatIdParam);
                setChatName(chatData.name);
                setChatId(chatIdParam);

                const initialMessages = await getMessages(chatIdParam);
                const updatedMessages = initialMessages.map(msg => ({
                    ...msg,
                    delivered: msg.delivered_status,
                    read: msg.read_status
                }));

                // Сохраняем сообщения в состояние
                setMessages(updatedMessages);
            } catch (error) {
                console.error('Ошибка при загрузке данных о чате:', error);
            }
        };

        fetchChatData();
    }, [chatIdParam, username]);

    useEffect(() => {
        if (!chatId) return;

        setCurrentUserId(Number(userId));

        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');
                
                stompClient.subscribe(`/topic/chat/${chatId}/messages`, (message) => {
                    const newMessage = JSON.parse(message.body);
                    console.log('NewMessage: ', newMessage);
                    updateMessages(newMessage);
                });

                // Подписка на уведомления
                /*stompClient.subscribe(`/topic/notifications/${userId}`, (message) => {
                    const notification = message.body;
                    console.log("Received notification:", notification);
                    toast.info(notification, {
                        autoClose: false,
                    });
                });*/
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
    }, [chatId, userId]);

    // Проверка подключения клиента и непрочитанных сообщений
    useEffect(() => {
        const interval = setInterval(() => {
            if (client && client.connected && messages.length > 0) {
                const unreadMessages = messages.some(msg => !msg.read);
                if (unreadMessages) {
                    markAllMessagesAsRead(messages);
                    clearInterval(interval); // Останавливаем проверку после успешного вызова
                }
            }
        }, 1000); // Проверяем каждую секунду

        return () => clearInterval(interval); // Очищаем интервал при размонтировании
    }, [messages, client]);

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

    const markAllMessagesAsRead = (updatedMessages) => {
        console.log('markAllMessagesAsRead is working...');
        if (Array.isArray(updatedMessages)) {
            updatedMessages.forEach(msg => {
                if (msg.sender.id !== userId && (msg.read === false || msg.read === null)) {
                    if (client && client.connected) {
                        client.publish({ destination: `/app/readMessage/${chatId}`, body: JSON.stringify(msg.id) });
                        setMessages(prevMessages => 
                            prevMessages.map(message => 
                                message.id === msg.id ? { ...message, read: true } : message
                            )
                        );
                    }
                }
            });
        } else {
            console.error('updatedMessages is undefined or not an array');
        }
    };

    const updateMessages = (newMessage) => {
        setMessages((prevMessages) => {
            if (newMessage.content === "Message deleted") {
                return prevMessages.filter(msg => msg.id !== newMessage.id);
            }

            const existingMessageIndex = prevMessages.findIndex(msg => msg.id === newMessage.id);
            if (existingMessageIndex !== -1) {
                const updatedMessages = [...prevMessages];
                updatedMessages[existingMessageIndex] = {
                    ...newMessage,
                    delivered: newMessage.delivered_status,
                    read: newMessage.read_status
                };
                return updatedMessages;
            } else {
                return [...prevMessages, {
                    ...newMessage,
                    delivered: newMessage.delivered_status,
                    read: newMessage.read_status
                }];
            }
        });
    };

    const uploadFile = async (file) => {
        const formData = new FormData();
        formData.append('file', file);

        const response = await fetch('https://unlimitedpossibilities12.org/api/files/upload', {
            method: 'POST',
            body: formData,
        });

        if (!response.ok) {
            throw new Error('File upload failed');
        }

        const data = await response.json();
        return data.fileUrl;
    };

    const sendMessage = async () => {
        if ((content || selectedFile) && userId) {
            const message = {
                id: editingMessageId || Date.now(),
                content,
                sender: { id: userId, username },
                chat: { id: chatId, name: chatName },
                fileUrl: selectedFile ? await uploadFile(selectedFile) : null,
                delivered: false,
                read: false,
            };
    
            if (client && client.connected) {
                client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
    
                // Таймер на 1.5 секунды для проверки статуса прочтения
                setTimeout(async () => {
                    // Запрос для получения ID сообщения по содержимому
                    const response = await fetch(`https://unlimitedpossibilities12.org/api/messages/search?content=${encodeURIComponent(content)}&senderId=${userId}&chatId=${chatId}`);
                    
                    if (response.ok) {
                        const updatedMessage = await response.json();
                        console.log('updatedMessage:', updatedMessage);
    
                        // Получаем ID сообщения
                        const messageId = updatedMessage.id; // Предполагается, что это поле существует
    
                        // Запрос на сервер для проверки статуса сообщения
                        const statusResponse = await fetch(`https://unlimitedpossibilities12.org/api/messages/${messageId}`);
                        
                        if (statusResponse.ok) {
                            const messageStatus = await statusResponse.json();
                            
                            // Извлечение имени получателя из имени чата
                            const recipientName = chatName.split('_').find(name => name !== username);
                            console.log('recipientName:', recipientName);
    
                            // Запрос на получение ID получателя
                            const userResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`);
                            
                            if (userResponse.ok) {
                                const recipient = await userResponse.json();
                                const recipientId = recipient.id; // Получаем ID получателя

                                console.log('recipientId:', recipientId);   
                                console.log('updatedMessage.read: ' + updatedMessage.read);
                                console.log('updatedMessage.read_status: ' + updatedMessage.read_status);
                                console.log('messageStatus.read: ' + messageStatus.read);
                                console.log('messageStatus.read_status: ' + messageStatus.read_status);
                                console.log('userId: ' + userId);

                                // Проверка, прочитано ли сообщение
                                if (messageStatus.read_status === false || messageStatus.read_status === null) {
                                    console.log('Notification must be sent...');
                                    /*toast.info(`Новое сообщение от ${username}: "${content}"`, {
                                        onClick: () => {
                                            window.location.href = `/chat/${chatId}`;
                                        },
                                        autoClose: false,
                                    });*/
                                    // Отправка уведомления на сервер
                                    await fetch(`https://unlimitedpossibilities12.org/api/messages/notify`, {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({
                                            userId,
                                            recipientId,
                                            content: message.content,
                                            chatId,
                                        }),
                                    });
                                }
                            } else {
                                console.error('Ошибка при получении ID получателя');
                            }
                        } else {
                            console.error('Ошибка при получении статуса сообщения');
                        }
                    } else {
                        console.error('Ошибка при поиске сообщения');
                    }
                }, 3000);
    
                resetForm();
            } else {
                console.error('STOMP client is not connected');
            }
        } else {
            toast.warn('Content is missing');
        }
    };

    const handleKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    };

    const resetForm = () => {
        setContent('');
        setSelectedFile(null);
        setEditingMessageId(null);
        if (fileInputRef.current) {
            fileInputRef.current.value = null;
        }
    };

    const handleRightClick = (e, message) => {
        e.preventDefault();
        setContextMenu({
            visible: true,
            x: e.pageX,
            y: e.pageY,
            message,
        });
    };

    const handleMenuAction = (action) => {
        if (contextMenu.message.sender.id !== userId) {
            toast.error('Вы можете редактировать или удалять только свои сообщения.');
            return;
        }

        if (action === 'edit') {
            setContent(contextMenu.message.content);
            setEditingMessageId(contextMenu.message.id);
        } else if (action === 'delete') {
            deleteMessage(contextMenu.message.id);
        }
        setContextMenu({ visible: false });
    };

    const deleteMessage = async (id) => {
        if (client && client.connected) {
            client.publish({
                destination: `/app/deleteMessage/${chatId}`,
                body: JSON.stringify(id),
            });
            setMessages(prevMessages => prevMessages.filter(msg => msg.id !== id));
            toast.success('Сообщение удалено!');
        } else {
            toast.error('Не удалось удалить сообщение. Попробуйте снова.');
        }
    };

    // Прокрутка вниз при обновлении сообщений
    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages]);

    return (
        <div className="messenger">
            <div className="chat-name">Chat: {chatName}</div>
            <div className="message-list">
                {messages.map((msg) => (
                    <div 
                        key={msg.id}
                        className={`message ${msg.sender.id === userId ? 'current-user' : 'other-user'}`}
                        onContextMenu={(e) => handleRightClick(e, msg)}
                        style={{ display: 'flex', alignItems: 'flex-start', flexDirection: 'column' }} // Изменено на column
                    >
                        <div style={{ display: 'flex', alignItems: 'center', width: '100%' }}>
                            {msg.sender.avatar ? (
                                <img 
                                    src={`https://unlimitedpossibilities12.org${msg.sender.avatar}`} 
                                    alt={`${msg.sender.username} avatar`} 
                                    className="message-avatar"
                                />
                            ) : (
                                <div className="message-avatar">
                                    {msg.sender.username.charAt(0).toUpperCase()}
                                </div>
                            )}
                            <strong>{msg.sender.username}: </strong>
                            <span className="message-content">{msg.content}</span>
                        </div>
                        {msg.fileUrl && (
                            <a 
                                href={msg.fileUrl} 
                                download 
                                style={{ marginTop: '5px', marginLeft: '112px' }} // Отступ слева для ссылки
                            >
                                📥 Download file
                            </a>
                        )}
                        <div className="message-status">
                            {msg.sender.id === userId && msg.delivered && <span className="delivery-indicator">✔️</span>}
                            {msg.sender.id === userId && msg.read && <span className="read-indicator">✔️</span>}
                        </div>
                    </div>
                ))}
                <div ref={messagesEndRef} /> {/* Элемент для прокрутки */}
            </div>
            <textarea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Enter your message"
                className="input-message"
            />
            <input
                type="file"
                onChange={(e) => setSelectedFile(e.target.files[0])}
                className="file-input"
                ref={fileInputRef}
            />
            <button onClick={sendMessage} className="send-button">Send</button>
            <ToastContainer />
            {contextMenu.visible && (
                <div 
                    className="context-menu" 
                    style={{ top: contextMenu.y, left: contextMenu.x }}
                    onMouseLeave={() => setContextMenu({ visible: false })}
                >
                    <button onClick={() => handleMenuAction('edit')}>Edit</button>
                    <button onClick={() => handleMenuAction('delete')}>Delete</button>
                </div>
            )}

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

export default PrivateChat;