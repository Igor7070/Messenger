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
    const [selectedFile, setSelectedFile] = useState(null); // Новое состояние для файла
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [userId, setUserId] = useState(null);
    const [client, setClient] = useState(null);
    const [isLoggedIn, setIsLoggedIn] = useState(false);
    const [errorMessage, setErrorMessage] = useState('');
    const [rows, setRows] = useState(1);
    const [charPerLine, setCharPerLine] = useState(30);
    const [editingMessageId, setEditingMessageId] = useState(null);
    const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, message: null });

    const textAreaRef = useRef(null);
    const messagesEndRef = useRef(null);

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
                    updateMessages(newMessage);
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

    const updateMessages = (newMessage) => {
        console.log("Received message:", newMessage); // Отладка
        setMessages((prevMessages) => {
            const existingMessageIndex = prevMessages.findIndex(msg => msg.id === newMessage.id);
            if (existingMessageIndex !== -1) {
                const updatedMessages = [...prevMessages];
                updatedMessages[existingMessageIndex] = newMessage; 
                return updatedMessages;
            } else if (newMessage.content !== "Message deleted") {
                return [...prevMessages, newMessage];
            }
            return prevMessages;
        });
    };

    useEffect(() => {
        if (messagesEndRef.current) {
            messagesEndRef.current.scrollIntoView({ behavior: "smooth" });
        }
    }, [messages]);

    const sendMessage = async () => {
        if (content && userId) {
            const currentUsername = username;
            const message = {
                id: editingMessageId || Date.now(),
                content,
                sender: { id: userId, username: currentUsername },
                fileUrl: selectedFile ? URL.createObjectURL(selectedFile) : null // URL для файла
            };
            console.log('Sending message:', message);

            if (client && client.connected) {
                if (editingMessageId) {
                    const existingMessage = messages.find(msg => msg.id === editingMessageId);
                    if (existingMessage && existingMessage.sender.username === username) {
                        client.publish({ destination: '/app/editMessage', body: JSON.stringify(message) });
                    } else {
                        toast.error('Вы можете редактировать только свои сообщения.');
                        return;
                    }
                } else {
                    client.publish({ destination: '/app/sendMessage', body: JSON.stringify(message) });
                }
                setContent('');
                setRows(1);
                setEditingMessageId(null);
                setContextMenu({ visible: false });
                setSelectedFile(null); // Сброс состояния файла после отправки
                console.log('Message sent');
            } else {
                console.error('STOMP client is not connected');
            }
        } else {
            console.warn('Content or userId is missing');
        }
    };

    const handleKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    };

    const handleFileChange = (e) => {
        setSelectedFile(e.target.files[0]); // Сохраняем выбранный файл
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
        if (contextMenu.message.sender.username !== username) {
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
                destination: '/app/deleteMessage',
                body: JSON.stringify(id),
            });
            toast.success('Сообщение удалено!');
        } else {
            toast.error('Не удалось удалить сообщение. Попробуйте снова.');
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
                setIsLoggedIn(true);
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
        localStorage.removeItem('userId');
        localStorage.removeItem('username');
        setUserId(null);
        setUsername('');
        setIsLoggedIn(false);
        toast.success('Вы вышли из системы!');
    };

    const handleInputChange = (e) => {
        const newValue = e.target.value;
        setContent(newValue);
        textAreaRef.current.style.height = 'auto';
        const newRows = calculateRows(newValue);
        setRows(newRows);
        textAreaRef.current.style.height = `${newRows * 24}px`;
        console.log("Content state:", newValue);
    };

    const calculateRows = (text) => {
        if (textAreaRef.current) {
            const textHeight = textAreaRef.current.scrollHeight;
            const currentScrollHeight = textHeight > 0 ? textHeight : 1;
            const linesCount = Math.floor(currentScrollHeight / 24);

            if (text.length > 0) {
                return linesCount;
            } else {
                const charCount = text.length;
                return Math.max(1, Math.ceil(charCount / charPerLine));
            }
        }
        return 1;
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
                {messages.map((msg) => (
                    msg.sender ? (
                        <div 
                            key={msg.id}
                            className={`message ${msg.sender.username === username ? 'current-user' : 'other-user'}`} 
                            onContextMenu={(e) => handleRightClick(e, msg)}
                        >
                            <strong>{msg.sender.username}: </strong>
                            {msg.content}
                            {msg.fileUrl && (
                                <a href={msg.fileUrl} download>📥 Скачать файл</a>
                            )}
                        </div>
                    ) : null
                ))}
                <div ref={messagesEndRef} />
            </div>
            <textarea
                ref={textAreaRef}
                value={content}
                onChange={handleInputChange}
                onKeyDown={handleKeyDown}
                placeholder="Enter your message"
                className="input-message"
                rows={rows}
            />
            <input type="file" onChange={handleFileChange} className="file-input" />
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

export default Messenger;