import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages } from '../api/messages';
import '../styles/PrivateChat.css';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import { useParams, useNavigate } from 'react-router-dom';
import { getChatById } from '../api/chats';
import IncomingCallNotification from './IncomingCallNotification';
import { useUserInteraction } from './UserInteractionContext';
import { format, isToday, parseISO } from 'date-fns';
import { useUserStatusContext } from '../context/UserStatusContext';
import { useCallWebSocket } from '../context/CallWebSocketContext'; // Новый импорт

const PrivateChat = () => {
    const [messages, setMessages] = useState([]);
    const [content, setContent] = useState('');
    const [selectedFile, setSelectedFile] = useState(null);
    const [userId, setUserId] = useState(Number(localStorage.getItem('userId')));
    const [username, setUsername] = useState(localStorage.getItem('username'));
    const [client, setClient] = useState(null);
    const [chatId, setChatId] = useState(null);
    const [chatName, setChatName] = useState('');
    const [displayChatName, setDisplayChatName] = useState('');
    const [editingMessageId, setEditingMessageId] = useState(null);
    const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, message: null });
    const messagesEndRef = useRef(null);
    const messageListRef = useRef(null);
    const fileInputRef = useRef(null);
    const { chatId: chatIdParam } = useParams();
    const navigate = useNavigate();
    const { userInteracted, handleInteraction } = useUserInteraction();
    const { usersStatus } = useUserStatusContext();
    const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
    const [currentUserId, setCurrentUserId] = useState(null);

    // Функция прокрутки к последнему сообщению
    const scrollToBottom = () => {
        if (messagesEndRef.current && messageListRef.current) {
            messagesEndRef.current.scrollIntoView({ behavior: 'smooth' });
        }
    };

    // Обработчик клика по аватару
    const handleAvatarClick = (senderId) => {
        navigate(`/user/${senderId}`);
    };

    useEffect(() => {
        const fetchChatData = async () => {
            console.log('fetchChatData is working...');
            if (!chatIdParam || !username) return;

            try {
                const chatData = await getChatById(chatIdParam);
                setChatName(chatData.name);
                const names = chatData.name.split('_');
                const interlocutorName = names.find(name => name !== username);
                setDisplayChatName(`Chat with ${interlocutorName}`);
                setChatId(chatIdParam);

                const initialMessages = await getMessages(chatIdParam);
                const updatedMessages = initialMessages.map(msg => ({
                    ...msg,
                    delivered: msg.delivered_status,
                    read: msg.read_status
                }));

                setMessages(updatedMessages);
            } catch (error) {
                console.error('Ошибка при загрузке данных о чате:', error);
            }
        };

        fetchChatData();

        const storedUserId = localStorage.getItem('userId');
        if (storedUserId) {
            const userIdNum = Number(storedUserId);
            setCurrentUserId(userIdNum);
            setContextUserId(userIdNum); // Синхронизируем с контекстом
        }
    }, [chatIdParam, username, setContextUserId]);

    useEffect(() => {
        if (!chatId) return;

        const stompClientInstance = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');

                stompClientInstance.subscribe(`/topic/chat/${chatId}/messages`, (message) => {
                    const newMessage = JSON.parse(message.body);
                    console.log('NewMessage: ', newMessage);
                    updateMessages(newMessage);
                });

                stompClientInstance.subscribe(`/topic/notifications/${userId}`, (message) => {
                    const notification = message.body;
                    console.log("Received notification:", notification);

                    const chatIdMatch = notification.match(/\(chatId: (\d+)\)/);
                    const notificationChatId = chatIdMatch ? chatIdMatch[1] : null;

                    if (notificationChatId && notificationChatId !== chatId) {
                        const toastId = toast.info(`Новое сообщение от: ${notification}`, {
                            autoClose: false,
                            onClick: () => {
                                navigate(`/private-chat/${notificationChatId}`);
                                toast.dismiss(toastId);
                            },
                        });
                    }
                });
            },
            onWebSocketError: (error) => {
                console.error('WebSocket error:', error);
            },
        });

        setClient(stompClientInstance);
        stompClientInstance.activate();

        return () => {
            stompClientInstance.deactivate();
        };
    }, [chatId, userId, navigate]);

    useEffect(() => {
        const interval = setInterval(() => {
            if (client && client.connected && messages.length > 0) {
                const unreadMessages = messages.some(msg => !msg.read);
                if (unreadMessages) {
                    markAllMessagesAsRead(messages);
                    clearInterval(interval);
                }
            }
        }, 1000);

        return () => clearInterval(interval);
    }, [messages, client]);

    useEffect(() => {
        const handleClick = () => {
            handleInteraction();
        };

        document.addEventListener('click', handleClick);
        return () => {
            document.removeEventListener('click', handleClick);
        };
    }, [handleInteraction]);

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
                timestamp: new Date().toISOString(),
            };

            if (client && client.connected) {
                client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });

                setTimeout(async () => {
                    const response = await fetch(`https://unlimitedpossibilities12.org/api/messages/search?content=${encodeURIComponent(content)}&senderId=${userId}&chatId=${chatId}`);

                    if (response.ok) {
                        const updatedMessage = await response.json();
                        console.log('updatedMessage:', updatedMessage);

                        const messageId = updatedMessage.id;
                        const statusResponse = await fetch(`https://unlimitedpossibilities12.org/api/messages/${messageId}`);

                        if (statusResponse.ok) {
                            const messageStatus = await statusResponse.json();
                            const recipientName = chatName.split('_').find(name => name !== username);
                            console.log('recipientName:', recipientName);

                            const userResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`);

                            if (userResponse.ok) {
                                const recipient = await userResponse.json();
                                const recipientId = recipient.id;

                                console.log('recipientId:', recipientId);
                                console.log('updatedMessage.read: ' + updatedMessage.read);
                                console.log('updatedMessage.read_status: ' + updatedMessage.read_status);
                                console.log('messageStatus.read: ' + messageStatus.read);
                                console.log('messageStatus.read_status: ' + messageStatus.read_status);
                                console.log('userId: ' + userId);

                                if (messageStatus.read_status === false || messageStatus.read_status === null) {
                                    console.log('Notification must be sent...');
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
            toast.error('You can only edit or delete your own messages.');
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
            toast.success('Message deleted!');
        } else {
            toast.error('Failed to delete message. Try again.');
        }
    };

    // Прокрутка при каждом изменении messages
    useEffect(() => {
        // Используем requestAnimationFrame для надежности после рендеринга
        const rafId = requestAnimationFrame(() => {
            scrollToBottom();
        });
        return () => cancelAnimationFrame(rafId);
    }, [messages]);

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

    const shouldShowAvatar = (msg, index) => {
        if (msg.sender.id === userId) return false;
        if (index === 0) return true;
        const prevMessage = messages[index - 1];
        return prevMessage.sender.id !== msg.sender.id;
    };

    const isFirstMessage = (msg, index) => {
        if (msg.sender.id !== userId) return false; // Только для текущего пользователя
        if (index === 0) return true;
        const prevMessage = messages[index - 1];
        return prevMessage.sender.id !== msg.sender.id;
    };

    return (
        <div className="private-chat">
            <div className="private-chat-name">{displayChatName}</div>
            <div className="private-message-list" ref={messageListRef}>
                {messages.map((msg, index) => (
                    <div
                        key={msg.id}
                        className={`private-message ${msg.sender.id === userId ? 'private-current-user' : 'private-other-user'} ${isFirstMessage(msg, index) ? 'first-message' : ''}`}
                        onContextMenu={(e) => handleRightClick(e, msg)}
                    >
                        <div className="private-message-wrapper">
                            {shouldShowAvatar(msg, index) && (
                                <div className="private-avatar-container">
                                    {msg.sender.avatar ? (
                                        <div className="private-avatar-wrapper">
                                            <img
                                                src={`https://unlimitedpossibilities12.org${msg.sender.avatar}`}
                                                alt={`${msg.sender.username} avatar`}
                                                className="private-message-avatar"
                                                onClick={() => handleAvatarClick(msg.sender.id)}
                                                style={{ cursor: 'pointer' }}
                                            />
                                            {(usersStatus[msg.sender.id]?.online) && (
                                                <span className="status-indicator-online-privatechat"></span>
                                            )}
                                        </div>
                                    ) : (
                                        <div className="private-avatar-wrapper">
                                            <div
                                                className="private-message-avatar"
                                                onClick={() => handleAvatarClick(msg.sender.id)}
                                                style={{ cursor: 'pointer' }}
                                            >
                                                {msg.sender.username.charAt(0).toUpperCase()}
                                            </div>
                                            {(usersStatus[msg.sender.id]?.online) && (
                                                <span className="status-indicator-online-privatechat"></span>
                                            )}
                                        </div>
                                    )}
                                </div>
                            )}
                            <div className={`private-message-content-container ${shouldShowAvatar(msg, index) ? '' : 'no-avatar'}`}>
                                <div className="private-message-body">
                                    <span className="private-message-content">{msg.content}</span>
                                    {msg.fileUrl && (
                                        <a
                                            href={msg.fileUrl}
                                            download
                                            className="private-file-link"
                                        >
                                            📥 Download file
                                        </a>
                                    )}
                                    <div className="private-message-status">
                                        {msg.sender.id === userId && (
                                            <>
                                                {msg.delivered && <span className="private-delivery-indicator">✔️</span>}
                                                {msg.read && <span className="private-read-indicator">✔️</span>}
                                            </>
                                        )}
                                    </div>
                                    <div className="private-message-timestamp">{formatTimestamp(msg.timestamp)}</div>
                                </div>
                            </div>
                        </div>
                    </div>
                ))}
                <div ref={messagesEndRef} />
            </div>
            <div className="private-input-container">
                <textarea
                    value={content}
                    onChange={(e) => setContent(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder="Enter your message..."
                    className="private-input-message"
                />
                <div className="private-button-container">
                    <input
                        type="file"
                        onChange={(e) => setSelectedFile(e.target.files[0])}
                        className="private-file-input"
                        ref={fileInputRef}
                        id="private-file-input"
                    />
                    <label htmlFor="private-file-input" className="private-file-input-label">Choose File</label>
                    <button onClick={sendMessage} className="private-send-button">Send</button>
                </div>
            </div>
            <ToastContainer />
            {contextMenu.visible && (
                <div
                    className="private-context-menu"
                    style={{ top: contextMenu.y, left: contextMenu.x }}
                    onMouseLeave={() => setContextMenu({ visible: false })}
                >
                    <button onClick={() => handleMenuAction('edit')}>Edit</button>
                    <button onClick={() => handleMenuAction('delete')}>Delete</button>
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
    );
};

export default PrivateChat;