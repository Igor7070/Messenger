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
    const [rows, setRows] = useState(1); // Состояние для количества строк

    const textAreaRef = useRef(null);

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

        setClient(stompClient);
        stompClient.activate();

        fetchMessages();

        return () => {
            stompClient.deactivate();
        };
    }, []);

    const sendMessage = async () => {
        if (content && userId) {
            const currentUsername = username;
            const message = {
                content,
                sender: { id: userId, username: currentUsername }
            };
            console.log('Sending message:', message);
    
            if (client && client.connected) {
                client.publish({ destination: '/app/sendMessage', body: JSON.stringify(message) });
                setContent('');
                setRows(1); // Сбрасываем строки при отправке
                console.log('Message sent');
            } else {
                console.error('STOMP client is not connected');
            }
        } else {
            console.warn('Content or userId is missing');
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
                let errorMessage = 'Не удалось создать пользователя. Пожалуйста, попробуйте снова.';
        
                console.warn(error.message);

                if (error.message.includes('User already exists')) {
                    errorMessage = 'Пользователь с таким именем уже существует. Пожалуйста, выберите другое имя.';
                } else if (error.message.includes('Password must be at least 9 characters long, contain letters and numbers')) {
                    errorMessage = 'Пароль должен содержать минимум 9 символов, включая буквы и цифры.';
                }
    
                toast.error(errorMessage);
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
                setIsLoggedIn(true);
                setErrorMessage('');
                toast.success('Вы успешно вошли в систему!');
            } catch (error) {
                let errorMessage = 'Не удалось войти. Пожалуйста, попробуйте снова.';
                console.warn(error.message);
    
                if (error.message.includes('Invalid username or password')) {
                    errorMessage = 'Неверное имя пользователя или пароль.';
                }
    
                toast.error(errorMessage);
            }
        } else {
            toast.error('Пожалуйста, заполните все поля.');
        }
    };

    const handleInputChange = (e) => {
        const newValue = e.target.value; // Получаем новое значение
        setContent(newValue); // Обновляем состояние содержимого
        setRows(calculateRows(newValue)); // Обновляем количество строк
    };

    // Функция для вычисления количества строк
    const calculateRows = (value) => {
        if (textAreaRef.current) {
            const lineHeight = 24; // Высота строки
            const tempElement = document.createElement('div'); // Создаем временный элемент
            tempElement.style.width = `${textAreaRef.current.clientWidth}px`; // Устанавливаем ширину
            tempElement.style.font = getComputedStyle(textAreaRef.current).font; // Копируем стиль шрифта
            tempElement.style.visibility = 'hidden'; // Скрываем элемент
            tempElement.style.overflowWrap = 'break-word'; // Устанавливаем перенос слов
            tempElement.innerText = value || ' '; // Устанавливаем текст
            document.body.appendChild(tempElement); // Добавляем в DOM
            const height = tempElement.scrollHeight; // Получаем высоту
            document.body.removeChild(tempElement); // Удаляем элемент

            return Math.max(1, Math.floor(height / lineHeight)); // Минимум 1 строка
        }
        return 1; // Если текстовое поле не доступно, возвращаем 1
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
            {errorMessage && <div className="error-message">{errorMessage}</div>}
            <div className="message-list">
                {messages.map((msg, index) => (
                    <div key={index} className="message">
                        <strong>{msg.sender.username}: </strong>
                        {msg.content}
                    </div>
                ))}
            </div>
            <textarea
                ref={textAreaRef}
                value={content}
                onChange={handleInputChange}
                placeholder="Enter your message"
                className="input-message"
                rows={rows} // Используем состояние для количества строк
                style={{ resize: 'none' }} // Убираем возможность изменения размера вручную
            />
            <button onClick={sendMessage} className="send-button">Send</button>
            <ToastContainer />
        </div>
    );
};

export default Messenger;