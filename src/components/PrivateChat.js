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
    const [userId] = useState(Number(localStorage.getItem('userId')));
    const [username] = useState(localStorage.getItem('username'));
    const [client, setClient] = useState(null);
    const [chatId, setChatId] = useState(null);
    const [chatName, setChatName] = useState('');
    const messagesEndRef = useRef(null);
    const fileInputRef = useRef(null);
    const { chatId: chatIdParam } = useParams();

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

                setMessages(updatedMessages);
            } catch (error) {
                console.error('Ошибка при загрузке данных о чате:', error);
            }
        };

        fetchChatData();
    }, [chatIdParam, username]);

    useEffect(() => {
        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Client connected');
                stompClient.subscribe(`/topic/chat/${chatId}/messages`, (message) => {
                    const newMessage = JSON.parse(message.body);
                    console.log('NewMessage: ', newMessage);
                    updateMessages(newMessage);
                });
            },
            onWebSocketError: (error) => {
                console.error('WebSocket error:', error);
            },
        });

        setClient(stompClient);
        stompClient.activate(); // Активируем клиента при монтировании компонента

        return () => {
            stompClient.deactivate(); // Деактивируем клиента при размонтировании
        };
    }, [chatId]);

    useEffect(() => {
        const checkAndMarkMessagesAsRead = () => {
            if (client && client.connected && messages.length > 0) {
                const unreadMessages = messages.some(msg => !msg.read);
                if (unreadMessages) {
                    markAllMessagesAsRead(messages);
                }
            }
        };

        if (client) {
            checkAndMarkMessagesAsRead(); // Проверяем на непрочитанные сообщения
        }
    }, [messages, client]);

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
                id: Date.now(),
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
        if (fileInputRef.current) {
            fileInputRef.current.value = null;
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
                    >
                        <strong>{msg.sender.username}: </strong>
                        <span className="message-content">{msg.content}</span>
                        {msg.fileUrl && (
                            <a href={msg.fileUrl} download>📥 Download file</a>
                        )}
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
        </div>
    );
};

export default PrivateChat;