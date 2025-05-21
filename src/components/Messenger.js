import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages, sendMessage as apiSendMessage, loginUser } from '../api/messages';
import { createUser } from '../api/users';
import '../styles/Messenger.css';
import { toast } from 'react-toastify';
import { useParams, useNavigate } from 'react-router-dom';
import IncomingCallNotification from './IncomingCallNotification';
import { format, isToday, parseISO } from 'date-fns';
import { useUserInteraction } from '../context/UserInteractionContext';
import { useUserStatusContext } from '../context/UserStatusContext';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import { usePrivateMessageNotification } from '../context/PrivateMessageNotificationContext';

const getChatName = async (chatId) => {
    const response = await fetch(`https://unlimitedpossibilities12.org/api/chats/${chatId}`);
    if (!response.ok) {
        throw new Error('Failed to fetch chat name');
    }
    const chat = await response.json();
    return chat.name;
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
    const [currentUserId, setCurrentUserId] = useState(null);

    const textAreaRef = useRef(null);
    const messagesEndRef = useRef(null);
    const messageListRef = useRef(null);
    const fileInputRef = useRef(null);
    const { chatId } = useParams();
    const navigate = useNavigate();
    const { userInteracted, handleInteraction } = useUserInteraction();
    const { usersStatus, initializeWebSocket, disconnectWebSocket } = useUserStatusContext();
    const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
    const { setCurrentUserId: setNotificationUserId } = usePrivateMessageNotification();

    // Форматирование временной метки
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

    // Прокрутка к последнему сообщению
    const scrollToBottom = () => {
        if (messagesEndRef.current && messageListRef.current) {
            messagesEndRef.current.scrollIntoView({ behavior: 'smooth' });
        }
    };

    // Обработчик клика по аватару
    const handleAvatarClick = (senderId) => {
        navigate(`/user/${senderId}`);
    };

    //useEffect(() => {
        // Сброс всех тостов и тестовый тост
        //toast.dismiss(); // Очищаем очередь тостов
        //console.log('Testing toast rendering in Messenger');
        //toast.info('Test toast in Messenger', { toastId: 'test-messenger', autoClose: 3000 });
    //}, []);

    useEffect(() => {
        const storedUserId = localStorage.getItem('userId');
        const storedUsername = localStorage.getItem('username');

        if (storedUserId && storedUsername) {
            const userIdNum = Number(storedUserId);
            setUserId(userIdNum);
            setCurrentUserId(userIdNum);
            setContextUserId(userIdNum);
            setNotificationUserId(userIdNum);
            setUsername(storedUsername);
            setIsLoggedIn(true);
        }
    }, [setContextUserId, setNotificationUserId]);

    useEffect(() => {
        const fetchMessages = async () => {
            try {
                const messages = await getMessages(chatId);
                setMessages(messages);
                const name = await getChatName(chatId);
                setChatName(name);
                // Сохраняем время последнего посещения
                if (messages.length > 0) {
                    const lastMessage = messages[messages.length - 1];
                    localStorage.setItem(`last_visit_chat_${chatId}`, lastMessage.timestamp);
                }
            } catch (error) {
                console.error('Ошибка при загрузке сообщений:', error);
                toast.error('Failed to load messages');
            }
        };

        fetchMessages();
    }, [chatId]);

    useEffect(() => {
        const stompClientInstance = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');
                stompClientInstance.subscribe(`/topic/chat/${chatId}/messages`, (message) => {
                    const newMessage = JSON.parse(message.body);
                    updateMessages(newMessage);
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
    }, [chatId]);

    useEffect(() => {
        const handleClick = () => {
            handleInteraction();
        };

        document.addEventListener('click', handleClick);
        return () => {
            document.removeEventListener('click', handleClick);
        };
    }, [handleInteraction]);

    const updateMessages = (newMessage) => {
        setMessages((prevMessages) => {
            const existingMessageIndex = prevMessages.findIndex(msg => msg.id === newMessage.id);
            if (existingMessageIndex !== -1) {
                const updatedMessages = [...prevMessages];
                updatedMessages[existingMessageIndex] = newMessage;
                return updatedMessages;
            } else if (newMessage.content !== "Message deleted") {
                const updatedMessages = [...prevMessages, newMessage];
                // Обновляем время последнего посещения, если чат открыт
                localStorage.setItem(`last_visit_chat_${chatId}`, newMessage.timestamp);
                return updatedMessages;
            }
            return prevMessages;
        });
    };

    useEffect(() => {
        const rafId = requestAnimationFrame(() => {
            scrollToBottom();
        });
        return () => cancelAnimationFrame(rafId);
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

            const message = {
                id: editingMessageId || Date.now(),
                content,
                sender: { id: userId, username: currentUsername },
                chat: { id: chatId, name: chatName },
                fileUrl: selectedFile ? await uploadFile(selectedFile) : null,
                timestamp: new Date().toISOString(),
            };

            console.log('Sending message:', message);

            if (client && client.connected) {
                if (editingMessageId) {
                    const existingMessage = messages.find(msg => msg.id === editingMessageId);
                    if (existingMessage && existingMessage.sender.id === userId) {
                        client.publish({ destination: `/app/editMessage/${chatId}`, body: JSON.stringify(message) });
                        setMessages(prevMessages =>
                            prevMessages.map(msg =>
                                msg.id === editingMessageId ? { ...msg, content, timestamp: message.timestamp } : msg
                            )
                        );
                    } else {
                        toast.error('You can only edit your own messages.');
                        return;
                    }
                } else {
                    client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
                }

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
                setCurrentUserId(newUser.id);
                setContextUserId(newUser.id);
                setNotificationUserId(newUser.id);
                toast.success('User successfully created!');
            } catch (error) {
                let errorMessage = 'Failed to create user. Please try again.';
                console.warn(error.message);

                if (error.message.includes('User already exists')) {
                    errorMessage = 'A user with this name already exists. Please choose an other name.';
                } else if (error.message.includes('Password must be at least 9 characters long, contain letters and numbers')) {
                    errorMessage = 'The password must contain at least 9 characters, including letters and numbers.';
                }

                toast.error(errorMessage);
            }
        } else {
            toast.error('Please fill in all fields.');
        }
    };

    const handleLogin = async () => {
        if (username && password) {
            try {
                const loggedUser = await loginUser({ username, password });
                setUserId(loggedUser.id);
                setCurrentUserId(loggedUser.id);
                setContextUserId(loggedUser.id);
                setNotificationUserId(loggedUser.id);
                setIsLoggedIn(true);
                setErrorMessage('');
                localStorage.setItem('userId', loggedUser.id);
                localStorage.setItem('username', loggedUser.username);
                initializeWebSocket(loggedUser.id, loggedUser.username);
                toast.success('You have successfully logged in!');
            } catch (error) {
                let errorMessage = 'Failed to log in. Please try again.';
                console.warn(error.message);

                if (error.message.includes('Invalid username or password')) {
                    errorMessage = 'Incorrect username or password.';
                }

                toast.error(errorMessage);
            }
        } else {
            toast.error('Please fill in all fields.');
        }
    };

    const handleLogout = () => {
        disconnectWebSocket();
        localStorage.removeItem('userId');
        localStorage.removeItem('username');
        setUserId(null);
        setCurrentUserId(null);
        setContextUserId(null);
        setNotificationUserId(null);
        setUsername('');
        setIsLoggedIn(false);
        toast.success('You are logged out!');
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

    // Определяем, показывать ли аватар
    const shouldShowAvatar = (msg) => {
        return msg.sender.id !== userId;
    };

    return (
        <div className="messenger">
            <div className="chat-name">Group Chat: {chatName}</div>
            <div className="user-creation">
                {!isLoggedIn ? (
                    <>
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
                    </>
                ) : (
                    <button onClick={handleLogout} className="logout-button">Logout</button>
                )}
            </div>
            {errorMessage && <div className="error-message">{errorMessage}</div>}
            <div className="message-list" ref={messageListRef}>
                {messages.map((msg, index) => (
                    msg.sender ? (
                        <div
                            key={msg.id}
                            className={`message ${msg.sender.id === userId ? 'current-user' : 'other-user'}`}
                            onContextMenu={(e) => handleRightClick(e, msg)}
                        >
                            <div className="message-wrapper">
                                {shouldShowAvatar(msg) && (
                                    <div className="avatar-container">
                                        {msg.sender.avatar ? (
                                            <div className="avatar-wrapper">
                                                <img
                                                    src={`https://unlimitedpossibilities12.org${msg.sender.avatar}`}
                                                    alt={`${msg.sender.username} avatar`}
                                                    className="message-avatar"
                                                    onClick={() => handleAvatarClick(msg.sender.id)}
                                                    style={{ cursor: 'pointer' }}
                                                />
                                                {(usersStatus[msg.sender.id]?.online) && (
                                                    <span className="status-indicator-online-groupchat"></span>
                                                )}
                                            </div>
                                        ) : (
                                            <div className="avatar-wrapper">
                                                <div
                                                    className="message-avatar"
                                                    onClick={() => handleAvatarClick(msg.sender.id)}
                                                    style={{ cursor: 'pointer' }}
                                                >
                                                    {msg.sender.username.charAt(0).toUpperCase()}
                                                </div>
                                                {(usersStatus[msg.sender.id]?.online) && (
                                                    <span className="status-indicator-online-groupchat"></span>
                                                )}
                                            </div>
                                        )}
                                    </div>
                                )}
                                <div className="message-content-container">
                                    <div className="message-body">
                                        {msg.sender.id !== userId && (
                                            <span className="sender-name">{msg.sender.username}</span>
                                        )}
                                        <span className="message-content">{msg.content}</span>
                                        {msg.fileUrl && (
                                            <a
                                                href={msg.fileUrl}
                                                download
                                                className="file-link"
                                            >
                                                📥 Download file
                                            </a>
                                        )}
                                        <div className="message-timestamp">{formatTimestamp(msg.timestamp)}</div>
                                    </div>
                                </div>
                            </div>
                        </div>
                    ) : null
                ))}
                <div ref={messagesEndRef} />
            </div>
            <div className="input-container">
                <textarea
                    ref={textAreaRef}
                    value={content}
                    onChange={handleInputChange}
                    onKeyDown={handleKeyDown}
                    placeholder="Enter your message..."
                    className="input-message"
                    rows={rows}
                />
                <div className="button-container">
                    <input
                        type="file"
                        onChange={handleFileChange}
                        className="file-input"
                        ref={fileInputRef}
                        id="file-input"
                    />
                    <label htmlFor="file-input" className="file-input-label">Choose File</label>
                    <button onClick={sendMessage} className="send-button">Send</button>
                </div>
            </div>
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
            {incomingCall && (
                <IncomingCallNotification
                    callerId={incomingCall.callerId}
                    callerName={incomingCall.callerName}
                    avatar={incomingCall.avatar}
                    callType={incomingCall.type}
                    onAccept={() => acceptCall(navigate)}
                    onReject={rejectCall}
                />
            )}
        </div>
    );
};

export default Messenger;