import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages } from '../api/messages';
import '../styles/Messenger.css';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import { useParams } from 'react-router-dom';
import { getChatById } from '../api/chats'; // Импортируйте функцию для получения данных о чате

const PrivateChat = () => {
    const [messages, setMessages] = useState([]);
    const [content, setContent] = useState('');
    const [selectedFile, setSelectedFile] = useState(null);
    const [userId, setUserId] = useState(Number(localStorage.getItem('userId')));
    const [username, setUsername] = useState(localStorage.getItem('username')); // Храните имя пользователя
    const [client, setClient] = useState(null);
    const [chatId, setChatId] = useState(null);
    const [chatName, setChatName] = useState('');
    const messagesEndRef = useRef(null);
    const fileInputRef = useRef(null);
    const { chatId: chatIdParam } = useParams(); // Извлекаем chatId из параметров

    useEffect(() => {
        console.log('Chat ID Param:', chatIdParam); // Логируем chatIdParam
        const fetchChatData = async () => {
            if (!chatIdParam || !username) {
                console.error('Chat ID parameter is undefined or username is missing');
                return; // Прекращаем выполнение, если chatId не определен или имя пользователя отсутствует
            }
    
            try {
                // Получаем данные о чате по ID
                const chatData = await getChatById(chatIdParam);
                setChatName(chatData.name); // Создаем имя чата
                setChatId(chatIdParam); // Установка chatId из параметров
    
                // Получаем сообщения для этого чата
                const messages = await getMessages(chatIdParam);
                setMessages(messages);
            } catch (error) {
                console.error('Ошибка при загрузке данных о чате:', error);
            }
        };
    
        fetchChatData();
    }, [chatIdParam, username]); // Добавьте username в зависимости

    useEffect(() => {
        if (!chatId) return; // Прекращаем выполнение, если chatId не установлен

        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');
                stompClient.subscribe(`/topic/chat/${chatId}/messages`, (message) => {
                    const newMessage = JSON.parse(message.body);
                    updateMessages(newMessage);
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
    }, [chatId]); // Подписка только после установки chatId

    const updateMessages = (newMessage) => {
        setMessages((prevMessages) => [...prevMessages, newMessage]);
    };

    useEffect(() => {
        if (messagesEndRef.current) {
            messagesEndRef.current.scrollIntoView({ behavior: "smooth" });
        }
    }, [messages]);

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
        console.log('Sending message to chat ID:', chatId); // Логируем chatId
        console.log('Chat name:', chatName); // Логируем chatName
        if ((content || selectedFile) && userId) {
            const message = {
                id: Date.now(),
                content,
                sender: { id: userId },
                chat: { id: chatId, name: chatName },
                fileUrl: selectedFile ? await uploadFile(selectedFile) : null,
            };

            if (client && client.connected) {
                client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
                setContent('');
                setSelectedFile(null);
                if (fileInputRef.current) {
                    fileInputRef.current.value = null;
                }
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

    const handleFileChange = (e) => {
        setSelectedFile(e.target.files[0]);
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

    return (
        <div className="messenger">
            <div className="chat-name">Chat: {chatName}</div>
            <div className="message-list">
                {messages.map((msg) => (
                    <div 
                        key={msg.id}
                        className={`message ${msg.sender.id === userId ? 'current-user' : 'other-user'}`} 
                        style={{ display: 'flex', alignItems: 'center' }}
                    >
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
                        {msg.fileUrl && (
                            <a href={msg.fileUrl} download>📥 Download file</a>
                        )}
                    </div>
                ))}
                <div ref={messagesEndRef} />
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
                onChange={handleFileChange}
                className="file-input"
                ref={fileInputRef}
            />
            <button onClick={sendMessage} className="send-button">Send</button>
            <ToastContainer />
        </div>
    );
};

export default PrivateChat;