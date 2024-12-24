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
    const [charPerLine, setCharPerLine] = useState(30); // Определяем переменную
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
        const action = window.confirm("Выберите действие: редактировать или удалить?\nНажмите 'ОК' для редактирования, 'Отмена' для удаления.");
        if (action) {
            setContent(message.content);
            setEditingMessageId(message.id);
        } else {
            deleteMessage(message.id);
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

    const handleCreateUser = async () => {
        if (username && password) {
            const user = { username, password };
            try {
                const newUser = await createUser(user);
                setUserId(newUser.id);
                setUsername(newUser.username);
                setPassword('');
                setIsLoggedIn(true); // Устанавливаем состояние входа

                // Сохраняем данные нового пользователя в localStorage
                localStorage.setItem('userId', newUser.id);
                localStorage.setItem('username', newUser.username);

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

                // Сохраняем данные пользователя в localStorage
                localStorage.setItem('userId', loggedUser.id);
                localStorage.setItem('username', loggedUser.username);

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

    const handleLogout = () => {
        // Очистка localStorage
        localStorage.removeItem('userId');
        localStorage.removeItem('username');

        // Сброс состояния
        setUserId(null);
        setUsername('');
        setIsLoggedIn(false);
        toast.success('Вы вышли из системы!');
    };

    const handleInputChange = (e) => {
        const newValue = e.target.value; // Получаем новое значение
        setContent(newValue); // Обновляем состояние содержимого

        // Сбрасываем высоту перед пересчетом
        textAreaRef.current.style.height = 'auto';

        // Обновляем количество строк
        const newRows = calculateRows(newValue);
        setRows(newRows);

        // Устанавливаем новую высоту текстового поля
        textAreaRef.current.style.height = `${newRows * 24}px`; // Замените 24 на реальную высоту строки

        console.log("Content state:", newValue); // Лог нового содержимого
    };

    const calculateRows = (text) => {
        if (textAreaRef.current) {
            const textHeight = textAreaRef.current.scrollHeight; // Высота текстового поля
            const currentScrollHeight = textHeight > 0 ? textHeight : 1; // Обрабатываем случай, когда высота 0
            const linesCount = Math.floor(currentScrollHeight / 24); // Высчитываем количество строк

            if (text.length > 0) {
                return linesCount;
            } else {
                const charCount = text.length;
                return Math.max(1, Math.ceil(charCount / charPerLine)); // Минимум 1 строка
            }
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