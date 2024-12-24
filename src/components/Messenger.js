import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages, sendMessage as apiSendMessage, loginUser } from '../api/messages';
import { createUser } from '../api/users';
import '../styles/Messenger.css';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';

const Messenger = () => {
    const [messages, setMessages] = useState([]);
    const [content, setContent] = useState('');
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [userId, setUserId] = useState(null);
    const [client, setClient] = useState(null);
    const [isLoggedIn, setIsLoggedIn] = useState(false);
    const [errorMessage, setErrorMessage] = useState('');
    const [rows, setRows] = useState(1);
    const [charPerLine, setCharPerLine] = useState(30);
    const [editingMessageId, setEditingMessageId] = useState(null); // ID редактируемого сообщения

    const textAreaRef = useRef(null);
    const messagesEndRef = useRef(null); // Реф для конца списка сообщений

    useEffect(() => {
        const storedUserId = localStorage.getItem('userId');
        const storedUsername = localStorage.getItem('username');

        if (storedUserId && storedUsername) {
            setUserId(storedUserId);
            setUsername(storedUsername);
            setIsLoggedIn(true);
        }

        const fetchMessages = async () => {
            const messages = await getMessages();
            setMessages(messages);
        };

        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');
                stompClient.subscribe('/topic/messages', (message) => {
                    const newMessage = JSON.parse(message.body);
                    setMessages((prevMessages) => [...prevMessages, newMessage]);
                });
            },
            onWebSocketError: (error) => {
                console.error('WebSocket error:', error);
            },
        });

        setClient(stompClient);
        stompClient.activate();

        fetchMessages();

        return () => {
            stompClient.deactivate();
        };
    }, []);

    useEffect(() => {
        if (messagesEndRef.current) {
            messagesEndRef.current.scrollIntoView({ behavior: "smooth" });
        }
    }, [messages]);

    const sendMessage = async () => {
        if (content && userId) {
            const currentUsername = username;
            const message = {
                id: editingMessageId || Date.now(), // Используем ID для редактирования
                content,
                sender: { id: userId, username: currentUsername }
            };
            console.log('Sending message:', message);

            if (client && client.connected) {
                client.publish({ destination: '/app/sendMessage', body: JSON.stringify(message) });
                setContent('');
                setRows(1); // Сбрасываем строки при отправке
                setEditingMessageId(null); // Сбрасываем ID редактируемого сообщения
                console.log('Message sent');
            } else {
                console.error('STOMP client is not connected');
            }
        } else {
            console.warn('Content or userId is missing');
        }
    };

    const handleRightClick = (e, message) => {
        e.preventDefault();
        const options = window.confirm("Выберите действие: редактировать или удалить?");
        if (options) {
            if (window.confirm("Хотите редактировать сообщение?")) {
                setContent(message.content);
                setEditingMessageId(message.id);
            } else {
                deleteMessage(message.id);
            }
        }
    };

    const deleteMessage = (id) => {
        setMessages((prevMessages) => 
            prevMessages.map(msg => 
                msg.id === id ? { ...msg, content: "Сообщение удалено" } : msg
            )
        );
        toast.success('Сообщение удалено!');
    };

    return (
        <div className="messenger">
            <div className="user-creation">
                <input
                    type="text"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    placeholder="Enter username"
                    className="input-username"
                />
                <input
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Enter password"
                    className="input-password"
                />
                <button onClick={handleCreateUser} className="create-user-button">Create User</button>
                {isLoggedIn ? (
                    <button onClick={handleLogout} className="logout-button">Logout</button>
                ) : (
                    <button onClick={handleLogin} className="login-button">Login</button>
                )}
            </div>
            {errorMessage && <div className="error-message">{errorMessage}</div>}
            <div className="message-list">
                {messages.map((msg, index) => (
                    <div 
                        key={index} 
                        className={`message ${msg.sender.username === username ? 'current-user' : 'other-user'}`} 
                        onContextMenu={(e) => handleRightClick(e, msg)} // Обработчик правого клика
                    >
                        <strong>{msg.sender.username}: </strong>
                        {msg.content}
                    </div>
                ))}
                <div ref={messagesEndRef} /> {/* Ссылка для прокрутки */}
            </div>
            <textarea
                ref={textAreaRef}
                value={content}
                onChange={handleInputChange}
                placeholder="Enter your message"
                className="input-message"
                rows={rows} // Используем состояние для количества строк
            />
            <button onClick={sendMessage} className="send-button">Send</button>
            <ToastContainer />
        </div>
    );
};

export default Messenger;