import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages, sendMessage as apiSendMessage, loginUser } from '../api/messages';
import { createUser } from '../api/users';
import '../styles/Messenger.css';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import { useParams, useNavigate } from 'react-router-dom';
import IncomingCallNotification from './IncomingCallNotification';
import { useUserInteraction } from './UserInteractionContext';
import { format, isToday, parseISO } from 'date-fns';
import { useUserStatusContext } from './UserStatusContext';

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

    const textAreaRef = useRef(null);
    const messagesEndRef = useRef(null);
    const messageListRef = useRef(null);
    const fileInputRef = useRef(null);
    const { chatId } = useParams();

    const [currentUserId, setCurrentUserId] = useState(null);
    const [incomingCall, setIncomingCall] = useState(null);
    const { userInteracted, handleInteraction } = useUserInteraction();
    const stompClient = useRef(null);
    const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/melodiya_kovboy.mp3'));
    audioRef.current.loop = true;
    const messageAudioRef = useRef(null);
    const navigate = useNavigate();
    const callRequestRef = useRef(null);

    const { usersStatus, initializeWebSocket, disconnectWebSocket } = useUserStatusContext();

    const setIncomingCallWithLog = (value) => {
        console.log('Вызов setIncomingCall:', value);
        console.log('Текущее incomingCall:', incomingCall);
        console.log('Текущее callRequestRef:', callRequestRef.current);
        console.log('Стек вызовов:', new Error().stack);
        setIncomingCall(value);
    };

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

    useEffect(() => {
        const storedUserId = localStorage.getItem('userId');
        const storedUsername = localStorage.getItem('username');

        if (storedUserId && storedUsername) {
            setUserId(Number(storedUserId));
            setUsername(storedUsername);
            setIsLoggedIn(true);
        }
    }, []);

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
        setCurrentUserId(Number(userId));

        const stompClientInstance = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');
                stompClientInstance.subscribe(`/topic/chat/${chatId}/messages`, (message) => {
                    const newMessage = JSON.parse(message.body);
                    updateMessages(newMessage);
                });

                stompClientInstance.subscribe(`/topic/notifications/${userId}`, (message) => {
                    const notification = message.body;
                    console.log("Received notification:", notification);

                    const chatIdMatch = notification.match(/\(chatId: (\d+)\)/);
                    const notificationChatId = chatIdMatch ? chatIdMatch[1] : null;

                    if (userInteracted && messageAudioRef && messageAudioRef.current) {
                        console.log('Attempting to play message audio');
                        messageAudioRef.current.play()
                            .then(() => console.log('Message audio played successfully'))
                            .catch(error => {
                                console.error('Failed to play message audio:', error);
                                toast.warn('Please interact with the page to enable notification sounds.');
                            });
                    }

                    toast.info(notification, {
                        autoClose: false,
                        onClick: () => {
                            if (messageAudioRef && messageAudioRef.current) {
                                console.log('Pausing message audio on toast click');
                                messageAudioRef.current.pause();
                                messageAudioRef.current.currentTime = 0;
                            }
                            if (notificationChatId) {
                                navigate(`/private-chat/${notificationChatId}`);
                            } else {
                                console.error("chatId не найден в уведомлении");
                                toast.error('Invalid notification format');
                            }
                        },
                    });
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
    }, [chatId, userId, userInteracted, navigate]);

    useEffect(() => {
        const handleClick = () => {
            handleInteraction();
        };

        document.addEventListener('click', handleClick);
        return () => {
            document.removeEventListener('click', handleClick);
        };
    }, [handleInteraction]);

    useEffect(() => {
        if (incomingCall && userInteracted) {
            audioRef.current.play().catch(error => {
                console.error('Autoplay was prevented.', error);
            });
        } else {
            audioRef.current.pause();
            audioRef.current.currentTime = 0;
        }
    }, [incomingCall, userInteracted]);

    useEffect(() => {
        if (!currentUserId) return;
    
        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            onConnect: () => {
                console.log('Connected to WebSocket in Messenger');
                console.log("currentUserId:", currentUserId);
                stompClient.current.subscribe(`/topic/calls/${currentUserId}`, (message) => {
                    let callRequest;
                    try {
                        callRequest = JSON.parse(message.body);
                        console.log('Received call message:', callRequest);
                        console.log('Тип сообщения:', callRequest.type);
                    } catch (error) {
                        console.error('Error parsing call message:', error, message.body);
                        return;
                    }
    
                    if (callRequest.type === 'endCall') {
                        console.log('Received endCall signal:', callRequest);
                        console.log('Текущее состояние incomingCall:', incomingCall);
                        console.log('Текущее callRequestRef:', callRequestRef.current);
                        console.log('audioRef.current:', audioRef.current, 'src:', audioRef.current?.src);
                        if (
                            callRequestRef.current?.roomId === callRequest.roomId ||
                            incomingCall?.roomId === callRequest.roomId ||
                            audioRef.current?.src
                        ) {
                            console.log('Closing call notification for roomId:', callRequest.roomId);
                            setIncomingCallWithLog(null);
                            callRequestRef.current = null;
                            if (audioRef.current) {
                                audioRef.current.pause();
                                audioRef.current.currentTime = 0;
                            }
                            const cancelMessage = callRequest.type === 'video' ?
                                'The video call was cancelled.' :
                                'The voice call was cancelled.';
                            console.log('Отправляем тост:', cancelMessage);
                            toast.info(cancelMessage);
                        } else {
                            console.log('No active call or audio:', {
                                incomingCall,
                                callRequestRef: callRequestRef.current,
                                callRequest,
                                audioSrc: audioRef.current?.src
                            });
                        }
                    } else if (callRequest.type === 'voice' || callRequest.type === 'video') {
                        console.log('Received call request:', callRequest);
                        handleIncomingCall(callRequest);
                    } else {
                        console.log('Received message with unknown type:', callRequest);
                    }
                });
            },
            onWebSocketError: (error) => {
                console.error('WebSocket error for calls:', error);
            },
        });
        stompClient.current.activate();
    
        return () => {
            stompClient.current.deactivate();
            console.log('WebSocket for calls disconnected');
        };
    }, [currentUserId]);

    const handleIncomingCall = async (callRequest) => {
        console.log('Incoming call request:', callRequest);
        callRequestRef.current = {
            roomId: callRequest.roomId,
            callerId: callRequest.callerId,
            recipientId: callRequest.recipientId,
            type: callRequest.type
        };
        console.log('callRequestRef установлен:', callRequestRef.current);
        setIncomingCallWithLog(callRequest);
        try {
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${callRequest.callerId}`);
            if (response.ok) {
                const callerData = await response.json();
                console.log('Данные звонящего:', callerData);
                setIncomingCallWithLog({
                    ...callRequest,
                    callerName: callerData.username,
                    avatar: callerData.avatar
                });
            } else {
                console.error('Error fetching caller data');
                setIncomingCallWithLog({
                    ...callRequest,
                    callerName: 'Unknown',
                    avatar: null
                });
            }
        } catch (error) {
            console.error('Error fetching caller data:', error);
            setIncomingCallWithLog({
                ...callRequest,
                callerName: 'Unknown',
                avatar: null
            });
        }
    };

    const acceptCall = () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;
    
        console.log('Function acceptCall in Messenger working...');
        console.log("Incoming call accepted:", incomingCall);
    
        const callerId = incomingCall.callerId;
        const recipientId = currentUserId;
    
        const fetchUserNames = async () => {
            try {
                const callerResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`);
                const recipientResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${recipientId}`);
    
                if (callerResponse.ok && recipientResponse.ok) {
                    const callerData = await callerResponse.json();
                    const recipientData = await recipientResponse.json();
    
                    sendSignal({
                        from: currentUserId,
                        signal: { type: 'acceptCall' }
                    });
    
                    setIncomingCallWithLog(null);
                    callRequestRef.current = null;
    
                    const route = incomingCall.type === 'video' ? `/video-call/${incomingCall.roomId}` : `/voice-call/${incomingCall.roomId}`;
                    navigate(route, {
                        state: {
                            incomingCall,
                            isCaller: false,
                            currentUserId: currentUserId,
                            callerName: callerData.username,
                            recipientName: recipientData.username
                        }
                    });
                    toast.success(incomingCall.type === 'video' ? 'Video call accepted' : 'Voice call accepted');
                } else {
                    console.error('Error fetching user names');
                    toast.error('Failed to fetch user data');
                }
            } catch (error) {
                console.error('Error fetching user names:', error);
                toast.error('Error fetching user data');
            }
        };
    
        fetchUserNames();
    };

    const rejectCall = () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;
    
        setIncomingCallWithLog(null);
        callRequestRef.current = null;
        sendSignal({
            from: currentUserId,
            signal: { type: 'rejectCall' }
        });
        toast.info(incomingCall.type === 'video' ? 'Video call rejected' : 'Voice call rejected');
    };

    const sendSignal = (signalMessage) => {
        console.log('Function sendSignal in Messenger working...');
        console.log("Sending signal:", JSON.stringify(signalMessage, null, 2));
        const roomId = incomingCall.roomId;
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`,
            body: JSON.stringify(signalMessage),
        });
        console.log('Signal sent successfully.');
    };

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
                setIsLoggedIn(true);
                setErrorMessage('');
                localStorage.setItem('userId', loggedUser.id);
                localStorage.setItem('username', loggedUser.username);
                initializeWebSocket(loggedUser.id, loggedUser.username); // Инициализация WebSocket
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
        disconnectWebSocket(); // Отключаем WebSocket
        localStorage.removeItem('userId');
        localStorage.removeItem('username');
        setUserId(null);
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
                                                <span className="status-indicator-online"></span>
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
                                                <span className="status-indicator-online"></span>
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
            <ToastContainer />
            <audio ref={messageAudioRef}>
                <source src="https://igor7070.github.io/Messenger/zvuk-dlya-soobsen.mp3" type="audio/mpeg" />
                Ваш браузер не поддерживает аудио.
            </audio>
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
                    onAccept={acceptCall}
                    onReject={rejectCall}
                />
            )}
        </div>
    );
};

export default Messenger;