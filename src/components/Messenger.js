import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages, sendMessage as apiSendMessage, loginUser } from '../api/messages';
import { createUser } from '../api/users';
import '../styles/Messenger.css';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import { useParams } from 'react-router-dom';

// Добавляем функцию для получения имени чата
const getChatName = async (chatId) => {
    const response = await fetch(`https://unlimitedpossibilities12.org/api/chats/${chatId}`);
    if (!response.ok) {
        throw new Error('Failed to fetch chat name');
    }
    const chat = await response.json();
    return chat.name; // Вернуть имя чата
};

const Messenger = () => {
    const [messages, setMessages] = useState([]);
    const [content, setContent] = useState('');
    const [selectedFile, setSelectedFile] = useState(null);
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
    const [chatName, setChatName] = useState(''); 

    const textAreaRef = useRef(null);
    const messagesEndRef = useRef(null);
    const fileInputRef = useRef(null);
    const { chatId } = useParams();

    useEffect(() => {
        const storedUserId = localStorage.getItem('userId');
        const storedUsername = localStorage.getItem('username');

        if (storedUserId && storedUsername) {
            setUserId(storedUserId);
            setUsername(storedUsername);
            setIsLoggedIn(true);
        }

        console.log('Chat ID:', chatId); // Логируем chatId здесь
    }, []);

    useEffect(() => {
        console.log('Fetching messages for chatId:', chatId);
        const fetchMessages = async () => {
            try {
                const messages = await getMessages(chatId); // Используем chatId
                console.log('Messages fetched:', messages); // Логируем сообщения
                setMessages(messages);

                // Получаем имя чата и устанавливаем в состояние
                const name = await getChatName(chatId);
                setChatName(name);
            } catch (error) {
                console.error('Ошибка при загрузке сообщений:', error);
            }
        };

        fetchMessages();
    }, [chatId]);

    useEffect(() => {
        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');
                // Подписываемся на сообщения конкретного чата
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
    }, [chatId]); // chatId как зависимость для обновления подписки

    const updateMessages = (newMessage) => {
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

    const uploadFile = async (file) => {
        const formData = new FormData();
        formData.append('file', file);

        const response = await fetch('https://unlimitedpossibilities12.org/api/files/upload', {
            method: 'POST',
            body: formData,
        });

        if (!response.ok) {
            const errorText = await response.text();
            console.error('Upload failed:', errorText);
            throw new Error('File upload failed');
        }

        const data = await response.json();
        return data.fileUrl;
    };

    const sendMessage = async () => {
        if ((content || selectedFile) && userId) {
            const currentUsername = username;
    
            const chatName = await getChatName(chatId);
            console.log('chatName: ', chatName);
    
            const message = {
                id: editingMessageId || Date.now(),
                content,
                sender: { id: userId, username: currentUsername },
                chat: { id: chatId, name: chatName }, // Добавляем chatId и имя чата
                fileUrl: selectedFile ? await uploadFile(selectedFile) : null,
            };
    
            console.log('Sending message:', message); // Логируем сообщение перед отправкой
            console.log('Chat ID:', chatId); // Логируем chatId здесь
    
            if (client && client.connected) {
                if (editingMessageId) {
                    const existingMessage = messages.find(msg => msg.id === editingMessageId);
                    if (existingMessage && existingMessage.sender.username === username) {
                        // Здесь добавляем chatId в сообщение
                        client.publish({ destination: `/app/editMessage/${chatId}`, body: JSON.stringify(message) });
    
                        // Обновите состояние сообщений локально
                        setMessages(prevMessages => 
                            prevMessages.map(msg => 
                                msg.id === editingMessageId ? { ...msg, content } : msg
                            )
                        );
                    } else {
                        toast.error('Вы можете редактировать только свои сообщения.');
                        return;
                    }
                } else {
                    client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
                }
    
                // Сброс состояния после отправки
                setContent('');
                setRows(1);
                setEditingMessageId(null);
                setContextMenu({ visible: false });
                setSelectedFile(null);
    
                if (fileInputRef.current) {
                    fileInputRef.current.value = null;
                }
    
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
        setSelectedFile(e.target.files[0]);
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
            // Убедитесь, что chatId передается вместе с id
            client.publish({
                destination: `/app/deleteMessage/${chatId}`, // Добавьте chatId
                body: JSON.stringify(id),
            });
            
            // Обновите состояние сообщений локально
            setMessages(prevMessages => prevMessages.filter(msg => msg.id !== id));
            
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
            <div className="chat-name">Chat: {chatName}</div> {/* Добавлено отображение имени чата */}
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
                            style={{ display: 'flex', alignItems: 'center' }} // Flexbox для выравнивания
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
                            <span className="message-content">{msg.content}</span> {/* Обернули текст сообщения в span */}
                            {msg.fileUrl && (
                                <a href={msg.fileUrl} download>📥 Download file</a>
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
            <input
                type="file"
                onChange={handleFileChange}
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

export default Messenger;