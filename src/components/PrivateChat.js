import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages } from '../api/messages';
import '../styles/Messenger.css';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import { useParams } from 'react-router-dom';
import { getChatById } from '../api/chats';

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

    /*useEffect(() => {
        const fetchChatData = async () => {
            console.log('fetchChatData is working...');
            if (!chatIdParam || !username) return;

            try {
                const chatData = await getChatById(chatIdParam);
                setChatName(chatData.name);
                setChatId(chatIdParam);

                const initialMessages = await getMessages(chatIdParam);
                setMessages(initialMessages.map(msg => ({
                    ...msg,
                    delivered: msg.delivered_status,
                    read: msg.read_status
                })));

                // Отправляем уведомления о прочтении для всех непрочитанных сообщений
                //markAllMessagesAsRead(); // Вызов функции после загрузки сообщений
            } catch (error) {
                console.error('Ошибка при загрузке данных о чате:', error);
            }
        };
        fetchChatData();
    }, [chatIdParam, username]);*/

    /*useEffect(() => {
        const fetchChatData = async () => {
            console.log('fetchChatData is working...');
            if (!chatIdParam || !username) return;
    
            try {
                const chatData = await getChatById(chatIdParam);
                setChatName(chatData.name);
                setChatId(chatIdParam);
    
                const initialMessages = await getMessages(chatIdParam);
                setMessages(prevMessages => {
                    const updatedMessages = initialMessages.map(msg => ({
                        ...msg,
                        delivered: msg.delivered_status,
                        read: msg.read_status
                    }));
    
                    // Теперь вызываем markAllMessagesAsRead после обновления состояния
                    markAllMessagesAsRead(updatedMessages); // Вызываем здесь

                    // Логируем все сообщения после вызова markAllMessagesAsRead
                    console.log('All messages after markAllMessagesAsRead:', updatedMessages);
                    return updatedMessages; // Возвращаем обновленный массив сообщений
                });
            } catch (error) {
                console.error('Ошибка при загрузке данных о чате:', error);
            }
        };
    
        fetchChatData();
    }, [chatIdParam, username]);*/

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
                setMessages(updatedMessages); // Здесь не вызываем markAllMessagesAsRead
            } catch (error) {
                console.error('Ошибка при загрузке данных о чате:', error);
            }
        };
    
        fetchChatData();
    }, [chatIdParam, username]);

    /*useEffect(() => {
        if (messages.length > 0 && client && client.connected) {
            markAllMessagesAsRead(); // Вызываем, когда сообщения загружены
        }
    }, [messages, client]);*/ // Зависимость от сообщений и клиента

    /*useEffect(() => {
        if (messages.length > 0 && client && client.connected) {
            const unreadMessages = messages.some(msg => !msg.read);
            if (unreadMessages) {
                markAllMessagesAsRead(messages); // Передаем текущие сообщения
            }
        }
    }, [messages, client]);*/

    useEffect(() => {
        const checkAndMarkMessagesAsRead = () => {
            if (client && client.connected && messages.length > 0) {
                const unreadMessages = messages.some(msg => !msg.read);
                if (unreadMessages) {
                    markAllMessagesAsRead(messages);
                }
            }
        };
    
        const onConnect = () => {
            console.log('Client connected');
            checkAndMarkMessagesAsRead();
        };
    
        if (client) {
            // Проверяем, поддерживает ли клиент метод on
            if (typeof client.on === 'function') {
                client.on('connect', onConnect);
            } else {
                console.error('Client does not support the `on` method:', client);
            }
        }
    
        checkAndMarkMessagesAsRead();
    
        return () => {
            if (client && typeof client.off === 'function') {
                client.off('connect', onConnect);
            }
        };
    }, [messages, client]);

    useEffect(() => {
        if (!chatId) return;

        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                stompClient.subscribe(`/topic/chat/${chatId}/messages`, (message) => {
                    const newMessage = JSON.parse(message.body);
                    console.log('NewMessage: ', newMessage);
                    updateMessages(newMessage);
                    // Удаляем отправку уведомления о прочтении здесь
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
    }, [chatId]);

    /*const markAllMessagesAsRead = () => {
        console.log('markAllMessagesAsRead is working...');
        messages.forEach(msg => {
            console.log('msg: ' + msg);
            if (msg.sender.id !== userId && !msg.read) {
                if (client && client.connected) {
                    client.publish({ destination: `/app/readMessage/${chatId}`, body: JSON.stringify(msg.id) });
                    // Обновляем локальное состояние, чтобы отобразить, что сообщение прочитано
                    setMessages(prevMessages => 
                        prevMessages.map(message => 
                            message.id === msg.id ? { ...message, read: true } : message
                        )
                    );
                }
            }
        });
    };*/

    const markAllMessagesAsRead = (updatedMessages) => {
        console.log('markAllMessagesAsRead is working...');
        // Проверяем, что updatedMessages определен и является массивом
        if (Array.isArray(updatedMessages)) {
            updatedMessages.forEach(msg => {
                console.log(`Checking message: senderId: ${msg.sender.id}, userId: ${userId}, read: ${msg.read}`);
                if (msg.sender.id !== userId && (msg.read === false || msg.read === null)) {
                    if (client && client.connected) {
                        client.publish({ destination: `/app/readMessage/${chatId}`, body: JSON.stringify(msg.id) });
                        setMessages(prevMessages => 
                            prevMessages.map(message => 
                                message.id === msg.id ? { ...message, read: true } : message
                            )
                        );
                        console.log('markAllMessagesAsRead conditions1 are met...');
                    }
                    console.log('markAllMessagesAsRead conditions2 are met...');
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
                resetForm();
            } else {
                console.error('STOMP client is not connected');
            }
        } else {
            toast.warn('Content is missing');
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
                    >
                        <strong>{msg.sender.username}: </strong>
                        <span className="message-content">{msg.content}</span>
                        {msg.fileUrl && (
                            <a href={msg.fileUrl} download>📥 Download file</a>
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
        </div>
    );
};

export default PrivateChat;