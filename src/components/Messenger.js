import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages, loginUser, createUser } from '../api/messages';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import '../styles/Messenger.css';

const Messenger = () => {
    const [messages, setMessages] = useState([]);
    const [content, setContent] = useState('');
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [userId, setUserId] = useState(null);
    const textareaRef = useRef(null);

    useEffect(() => {
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

        stompClient.activate();
        fetchMessages();

        return () => {
            stompClient.deactivate();
        };
    }, []);

    const sendMessage = async () => {
        if (content && userId) {
            const message = {
                content,
                sender: { id: userId, username },
            };

            if (client && client.connected) {
                client.publish({ destination: '/app/sendMessage', body: JSON.stringify(message) });
                setContent('');
            } else {
                console.error('STOMP client is not connected');
            }
        } else {
            toast.error('Введите сообщение!');
        }
    };

    const handleInputChange = (e) => {
        setContent(e.target.value);
        if (textareaRef.current) {
            textareaRef.current.style.height = 'auto'; // Сброс высоты
            textareaRef.current.style.height = `${textareaRef.current.scrollHeight}px`; // Установка новой высоты
        }
    };

    const handleCreateUser = async () => {
        if (username && password) {
            const user = { username, password };
            try {
                const newUser = await createUser(user);
                setUserId(newUser.id);
                setUsername(newUser.username);
                setPassword('');
                toast.success('Пользователь успешно создан!');
            } catch (error) {
                toast.error('Не удалось создать пользователя. Пожалуйста, попробуйте снова.');
            }
        } else {
            toast.error('Пожалуйста, заполните все поля.');
        }
    };

    const handleLogin = async () => {
        if (username && password) {
            try {
                const loggedUser = await loginUser({ username, password });
                setUserId(loggedUser.id);
                toast.success('Вы успешно вошли в систему!');
            } catch (error) {
                toast.error('Не удалось войти. Пожалуйста, попробуйте снова.');
            }
        } else {
            toast.error('Пожалуйста, заполните все поля.');
        }
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
                <button onClick={handleLogin} className="login-button">Login</button>
            </div>
            <div className="message-list">
                {messages.map((msg, index) => (
                    <div key={index} className="message">
                        <strong>{msg.sender.username}: </strong>
                        {msg.content}
                    </div>
                ))}
            </div>
            <textarea
                ref={textareaRef}
                value={content}
                onChange={handleInputChange}
                placeholder="Enter your message"
                className="input-message"
                rows={1}
                style={{ resize: 'none', overflow: 'hidden' }} // Убираем возможность изменения размера вручную
            />
            <button onClick={sendMessage} className="send-button">Send</button>
            <ToastContainer />
        </div>
    );
};

export default Messenger;