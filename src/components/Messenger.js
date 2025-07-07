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
import TranslationSettingsModal from './TranslationSettingsModal';
import { translateMessage, saveTranslation } from '../api/messages';
import useGroupChatTranslationAndSpellCheck from '../hooks/useGroupChatTranslationAndSpellCheck';

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
    const [charPerLine, setCharPerLine] = useState(50);
    const [editingMessageId, setEditingMessageId] = useState(null);
    const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, message: null });
    const [chatName, setChatName] = useState('');
    const [currentUserId, setCurrentUserId] = useState(null);

    const textAreaRef = useRef(null);
    const messagesEndRef = useRef(null);
    const messageListRef = useRef(null);
    const fileInputRef = useRef(null);
    const mediaRecorderRef = useRef(null); // *** ДОБАВЛЕНО: Референция для MediaRecorder ***
    const { chatId } = useParams();
    const navigate = useNavigate();
    const { userInteracted, handleInteraction } = useUserInteraction();
    const { usersStatus, initializeWebSocket, disconnectWebSocket, sendOnlineStatus } = useUserStatusContext();
    const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
    const { setCurrentUserId: setNotificationUserId } = usePrivateMessageNotification();

    const [isTranslationEnabled, setIsTranslationEnabled] = useState(false);
    const [translationLanguage, setTranslationLanguage] = useState('auto');
    const [translateNewOnly, setTranslateNewOnly] = useState(false);
    const [translationQueue, setTranslationQueue] = useState([]);
    const [isTranslating, setIsTranslating] = useState(false);
    const [showTranslationModal, setShowTranslationModal] = useState(false);

    const [isSpellCheckEnabled, setIsSpellCheckEnabled] = useState(false);
    // *** ДОБАВЛЕНО: Состояния для аудиозаписи ***
    const [isRecording, setIsRecording] = useState(false);
    const [audioBlob, setAudioBlob] = useState(null);
    const [micIconState, setMicIconState] = useState('mic'); // 'mic', 'recording', 'sending'

    // ДОБАВЛЕНО: использование хука
    const {
        isMessageNew,
        translateExistingMessages,
        translateNextMessage,
        translateMessageFunc,
        clearTranslations,
        handleTranslationSettingsSave,
        handleSpellCheckToggle,
        spellCheckMessage,
    } = useGroupChatTranslationAndSpellCheck({
        chatId,
        messages,
        setMessages,
        isTranslationEnabled,
        setIsTranslationEnabled,
        translationLanguage,
        setTranslationLanguage,
        translateNewOnly,
        setTranslateNewOnly,
        translationQueue,
        setTranslationQueue,
        isTranslating,
        setIsTranslating,
        setIsSpellCheckEnabled,
    });

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
        if (messageListRef.current) {
            messageListRef.current.scrollTop = messageListRef.current.scrollHeight;
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
            const userIdNum = Number(storedUserId);
            setUserId(userIdNum);
            setCurrentUserId(userIdNum);
            setContextUserId(userIdNum);
            setNotificationUserId(userIdNum);
            setUsername(storedUsername);
            setIsLoggedIn(true);
            // Добавлено: загрузка настроек перевода
            setIsTranslationEnabled(localStorage.getItem(`translation_enabled_${chatId}`) === 'true');
            setTranslationLanguage(localStorage.getItem(`translation_language_${chatId}`) || 'auto');
            setTranslateNewOnly(localStorage.getItem(`translate_new_only_${chatId}`) === 'true');
            // Загрузка настройки орфографии
            setIsSpellCheckEnabled(localStorage.getItem(`spellcheck_enabled_${chatId}`) === 'true');
        }
    }, [setContextUserId, setNotificationUserId, chatId]);

    useEffect(() => {
        const fetchMessages = async () => {
            try {
                const messages = await getMessages(chatId);
                const updatedMessages = messages.map((msg) => {
                    const translationKey = `translation_${chatId}_${msg.id}`;
                    const savedTranslation = localStorage.getItem(translationKey);
                    if (savedTranslation) {
                        const { translatedContent, translationLanguage } = JSON.parse(savedTranslation);
                        return { ...msg, translatedContent, translationLanguage, showOriginal: true };
                    }
                    return { ...msg, showOriginal: true };
                });
                setMessages(updatedMessages);
                const name = await getChatName(chatId);
                setChatName(name);
                if (messages.length > 0) {
                    localStorage.setItem(`last_visit_chat_${chatId}`, messages[messages.length - 1].timestamp);
                }
                // Добавлено: прокрутка вниз после загрузки сообщений
                setTimeout(() => {
                    scrollToBottom();
                }, 0); // setTimeout для выполнения после рендера
            } catch (error) {
                console.error('Ошибка при загрузке сообщений:', error);
                toast.error('Failed to load messages');
            }
        };

        fetchMessages();
    }, [chatId]);

    useEffect(() => {
        if (!isTranslationEnabled && messages.some((msg) => msg.translatedContent || msg.translationLanguage)) {
            console.log('Cleaning translations in messages due to disabled translation');
            setMessages((prevMessages) =>
                prevMessages.map((msg) =>
                    msg.translatedContent || msg.translationLanguage
                    ? {
                        ...msg,
                        translatedContent: null,
                        translationLanguage: null,
                        showOriginal: true,
                        }
                    : msg
                )
            );
        }
    }, [isTranslationEnabled, messages]);

    // Добавлено: обработка новых сообщений для перевода
    useEffect(() => {
        const stompClientInstance = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
            console.log('Connected to WebSocket');
            stompClientInstance.subscribe(`/topic/chat/${chatId}/messages`, (message) => {
                const newMessage = JSON.parse(message.body);
                updateMessages(newMessage);
                // Добавлено: перевод нового сообщения
                if (isTranslationEnabled && isMessageNew(newMessage)) {
                setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
                }
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
    }, [chatId, isTranslationEnabled, translationLanguage]); // Изменено: добавлены зависимости

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
        // Прокрутка при добавлении нового сообщения
        if (messages.length > 0) {
            setTimeout(() => {
            scrollToBottom();
            }, 0); // setTimeout для выполнения после рендера
        }
    }, [messages.length]);

    // Добавлено: запуск перевода при изменении настроек
    useEffect(() => {
        if (isTranslationEnabled) {
            translateExistingMessages();
        } else {
            clearTranslations();
        }
    }, [isTranslationEnabled, translationLanguage, translateNewOnly]);

    // Добавлено: обработка очереди переводов
    useEffect(() => {
        translateNextMessage();
    }, [translationQueue, isTranslating]);

    useEffect(() => {
        const calculateCharPerLine = () => {
            if (textAreaRef.current) {
                const canvas = document.createElement('canvas');
                const context = canvas.getContext('2d');
                context.font = window.innerWidth <= 800 ? '14px Roboto' : '16px Roboto';
                const avgCharWidth = context.measureText('m').width; // Средняя ширина символа
                const textareaWidth = textAreaRef.current.clientWidth - 50; // Учитываем padding (40px десктоп, 36px мобильный)
                const chars = Math.floor(textareaWidth / avgCharWidth);
                setCharPerLine(chars);
            }
        };
        calculateCharPerLine();
        window.addEventListener('resize', calculateCharPerLine);
        return () => window.removeEventListener('resize', calculateCharPerLine);
    }, []);

    const updateMessages = (newMessage) => {
        setMessages((prevMessages) => {
            const existingMessageIndex = prevMessages.findIndex((msg) => msg.id === newMessage.id);
            if (existingMessageIndex !== -1) {
                const updatedMessages = [...prevMessages];
                updatedMessages[existingMessageIndex] = {
                    ...newMessage,
                    translatedContent: updatedMessages[existingMessageIndex].translatedContent,
                    translationLanguage: updatedMessages[existingMessageIndex].translationLanguage,
                    showOriginal: updatedMessages[existingMessageIndex].showOriginal,
                };
                if (
                    isTranslationEnabled &&
                    isMessageNew(newMessage) &&
                    (!updatedMessages[existingMessageIndex].translatedContent ||
                    updatedMessages[existingMessageIndex].translationLanguage !== translationLanguage)
                ) {
                    setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
                }
                return updatedMessages;
            } else if (newMessage.content !== 'Message deleted') {
                const updatedMessages = [...prevMessages, newMessage];
                localStorage.setItem(`last_visit_chat_${chatId}`, newMessage.timestamp);
                if (isTranslationEnabled && isMessageNew(newMessage)) {
                    setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
                }
                return updatedMessages;
            }
            return prevMessages;
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
            const errorText = await response.text();
            console.error('Upload failed:', errorText);
            throw new Error('File upload failed');
        }

        const data = await response.json();
        return data.fileUrl;
    };

    const sendMessage = async () => {
        if ((content.trim() || selectedFile) && userId) { // Проверяем наличие текста или файла
            const currentUsername = username;
            const chatName = await getChatName(chatId);

            // Проверяем орфографию, если включена
            let finalContent = content;
            if (isSpellCheckEnabled) {
                const spellCheckTimestamp = localStorage.getItem(`spellcheck_enabled_timestamp_${chatId}`);
                if (spellCheckTimestamp) {
                    finalContent = await spellCheckMessage(content);
                }
            }

            const message = {
                id: editingMessageId || Date.now(),
                content: finalContent,
                sender: { id: userId, username: currentUsername },
                chat: { id: chatId, name: chatName },
                fileUrl: selectedFile ? await uploadFile(selectedFile) : null,
                timestamp: new Date().toISOString(),
                messageType: selectedFile ? 'file' : 'text', // Устанавливаем тип сообщения
            };

            console.log('Sending message:', message);

            if (client && client.connected) {
                if (editingMessageId) {
                    const existingMessage = messages.find(msg => msg.id === editingMessageId);
                    if (existingMessage && existingMessage.sender.id === userId) {
                        client.publish({ destination: `/app/editMessage/${chatId}`, body: JSON.stringify(message) });
                        setMessages(prevMessages =>
                            prevMessages.map(msg =>
                                msg.id === editingMessageId ? { ...msg, content: finalContent, timestamp: message.timestamp } : msg
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
                toast.error('WebSocket not connected');
            }
        } else {
            console.warn('Content or file is missing');
            toast.error('Please provide a message or file');
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
                sendOnlineStatus();
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
        const newRows = Math.min(5, calculateRows(newValue)); // Ограничиваем до 5 строк
        setRows(newRows);
        const lineHeight = window.innerWidth <= 800 ? 20 : 24; // 20px для мобильных, 24px для десктопа
        textAreaRef.current.style.height = `${newRows * lineHeight + 20}px`; // Учитываем padding (10px сверху + 10px снизу)
        textAreaRef.current.style.boxSizing = 'border-box'; // Гарантируем включение padding в высоту
    };

    const calculateRows = (text) => {
        if (!text) return 1; // Если текст пустой, возвращаем 1 строку
        const lines = text.split('\n'); // Разбиваем текст на строки по переносам
        let totalRows = lines.length; // Количество строк по переносам
        if (textAreaRef.current) {
            const canvas = document.createElement('canvas');
            const context = canvas.getContext('2d');
            context.font = window.innerWidth <= 800 ? '14px Roboto' : '16px Roboto';
            const textareaWidth = textAreaRef.current.clientWidth - (window.innerWidth <= 800 ? 36 : 40); // Учитываем padding
            lines.forEach((line) => {
                if (line.length > 0) {
                    const textWidth = context.measureText(line).width;
                    if (textWidth > textareaWidth) {
                        totalRows += Math.floor((textWidth - textareaWidth) / textareaWidth); // Только дополнительные строки
                    }
                }
            });
        }
        return Math.max(1, totalRows); // Минимально 1 строка
    };

    // Определяем, показывать ли аватар
    const shouldShowAvatar = (msg) => {
        return msg.sender.id !== userId;
    };

    // *** ДОБАВЛЕНО: Метод для начала записи аудио ***
    const startRecording = async () => {
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            const mediaRecorder = new MediaRecorder(stream, { mimeType: 'audio/webm' });
            mediaRecorderRef.current = mediaRecorder;
            const chunks = [];

            mediaRecorder.ondataavailable = (e) => {
                if (e.data.size > 0) {
                    chunks.push(e.data);
                }
            };

            mediaRecorder.onstop = () => {
                const blob = new Blob(chunks, { type: 'audio/webm' });
                setAudioBlob(blob);
                stream.getTracks().forEach(track => track.stop());
                setMicIconState('sending'); // ДОБАВЛЕНО: Установка иконки отправки
                sendAudioMessage(blob); // Автоматическая отправка аудиосообщения
            };

            setMicIconState('recording'); // ДОБАВЛЕНО: Установка иконки красного кружка
            mediaRecorder.start();
            setIsRecording(true);
            toast.info('Recording started');
        } catch (error) {
            console.error('Error starting recording:', error);
            toast.error('Failed to start recording');
        }
    };

    // *** ДОБАВЛЕНО: Метод для остановки записи аудио ***
    const stopRecording = () => {
        if (mediaRecorderRef.current && isRecording) {
            setMicIconState('sending'); // ДОБАВЛЕНО: Сброс иконки на микрофон (временно, до отправки)
            mediaRecorderRef.current.stop();
            setIsRecording(false);
            toast.info('Recording stopped');
        }
    };

    // *** ДОБАВЛЕНО: Метод для отправки аудиосообщения ***
    const sendAudioMessage = async (audioBlob) => {
        if (!audioBlob || !userId) {
            console.warn('Audio or userId is missing');
            toast.error('Failed to send audio message');
            setMicIconState('mic'); // ДОБАВЛЕНО: Сброс иконки на микрофон при ошибке
            return;
        }

        const currentUsername = username;
        const chatName = await getChatName(chatId);

        const message = {
            id: Date.now(),
            content: '', // Пустой контент для аудиосообщений
            sender: { id: userId, username: currentUsername },
            chat: { id: chatId, name: chatName },
            fileUrl: await uploadFile(audioBlob),
            timestamp: new Date().toISOString(),
            messageType: 'audio', // Устанавливаем тип аудио
        };

        console.log('Sending audio message:', message);

        if (client && client.connected) {
            client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
            setAudioBlob(null); // Сбрасываем аудио
            console.log('Audio message sent');
            setMicIconState('mic'); // ДОБАВЛЕНО: Сброс иконки на микрофон после отправки
        } else {
            console.error('STOMP client is not connected');
            toast.error('WebSocket not connected');
            setMicIconState('mic'); // ДОБАВЛЕНО: Сброс иконки на микрофон при ошибке
        }
    };

    return (
        <div className="messenger">
            <div className="chat-header-group">
                <div className="chat-name">Group Chat: {chatName}</div>
                <div className="translation-controls">
                    <button
                        className="spellcheck-button" // Новая кнопка
                        onClick={handleSpellCheckToggle}
                        data-tooltip={isSpellCheckEnabled ? 'Spell check is ON' : 'Spell check is OFF'} // Подсказка
                        style={{ color: isSpellCheckEnabled ? '#42a5f5' : '#B0BEC5' }}
                    >
                        ✍️
                    </button>
                    {isSpellCheckEnabled && <span className="spellcheck-indicator">SC</span>}
                    <button
                        className="translation-button"
                        onClick={() => setShowTranslationModal(true)}
                        data-tooltip="Toggle translation for messages" // Подсказка
                        style={{ color: isTranslationEnabled ? '#42a5f5' : '#B0BEC5' }}
                    >
                        🌐
                    </button>
                    {isTranslationEnabled && (
                        <span className="translation-indicator">
                            {translationLanguage === 'auto' ? 'AUTO' : translationLanguage.toUpperCase().slice(0, 2)}
                        </span>
                    )}
                </div>
            </div>
            {showTranslationModal && (
                <TranslationSettingsModal
                    chatId={chatId}
                    onSave={handleTranslationSettingsSave}
                    onClose={() => setShowTranslationModal(false)}
                />
            )}
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
                            className={`message ${msg.sender.id === userId ? 'current-user' : 'other-user'} ${
                                msg.messageType === 'audio' ? 'audio-message' : ''
                            }`} // *** ИЗМЕНЕНО: Добавлен класс audio-message ***
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
                                            {usersStatus[msg.sender.id]?.online && (
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
                                            {usersStatus[msg.sender.id]?.online && (
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
                                        {/* *** ИЗМЕНЕНО: Условный рендеринг по messageType *** */}
                                        {msg.content && msg.messageType !== 'audio' && (
                                            <div
                                                className="message-content"
                                                onClick={(e) => {
                                                    if (e.target.tagName === 'A') return;
                                                    if (msg.translatedContent) {
                                                        setMessages((prevMessages) =>
                                                            prevMessages.map((m) =>
                                                                m.id === msg.id ? { ...m, showOriginal: !m.showOriginal } : m
                                                            )
                                                        );
                                                    }
                                                }}
                                                style={{ cursor: msg.translatedContent ? 'pointer' : 'default' }}
                                            >
                                                <span className="original-content">
                                                    {msg.showOriginal || !msg.translatedContent ? msg.content : msg.translatedContent}
                                                </span>
                                                {msg.translatedContent && (
                                                    <span className="translated-content">{msg.translatedContent}</span>
                                                )}
                                            </div>
                                        )}
                                        {msg.messageType === 'audio' && msg.fileUrl ? (
                                            <div className="audio-message-container">
                                                <audio controls className="audio-player">
                                                    <source src={msg.fileUrl} type="audio/webm" />
                                                    Your browser does not support the audio element.
                                                </audio>
                                            </div>
                                        ) : msg.fileUrl ? (
                                            <a href={msg.fileUrl} download className="file-link">
                                                📥 Download file
                                            </a>
                                        ) : null}
                                        {/* *** КОНЕЦ ИЗМЕНЕНИЯ *** */}
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
                <div className="textarea-wrapper">
                    <textarea
                        ref={textAreaRef}
                        value={content}
                        onChange={handleInputChange}
                        onKeyDown={handleKeyDown}
                        placeholder="Enter your message..."
                        className="input-message"
                        rows={rows}
                    />
                    {content.length === 0 && !isRecording && micIconState !== 'sending' && (
                        <div className="mic-icon-container">
                            <span
                                onClick={isRecording ? stopRecording : startRecording}
                                className={`mic-icon ${micIconState} ${!isLoggedIn ? 'disabled' : ''}`}
                                role="button"
                                aria-label={micIconState === 'mic' ? 'Start Recording' : micIconState === 'recording' ? 'Stop Recording' : 'Sending Audio'}
                            >
                                {micIconState === 'mic' && <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                                    <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z" fill="#757575"/>
                                    <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z" fill="#757575"/>
                                </svg>}
                                {micIconState === 'recording' && <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                                    <circle cx="12" cy="12" r="8" fill="#FF0000"/>
                                </svg>}
                                {micIconState === 'sending' && <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                                    <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" fill="#0288D1"/>
                                </svg>}
                            </span>
                        </div>
                    )}
                </div>
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