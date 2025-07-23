import React, { useEffect, useState, useRef, useMemo } from 'react';
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
import { FFmpeg } from '@ffmpeg/ffmpeg';

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
    const [translationMode, setTranslationMode] = useState('selective');
    const [translationQueue, setTranslationQueue] = useState([]);
    const [isTranslating, setIsTranslating] = useState(false);
    const [showTranslationModal, setShowTranslationModal] = useState(false);

    const [isSpellCheckEnabled, setIsSpellCheckEnabled] = useState(false);

    // *** ДОБАВЛЕНО: Состояния для аудиозаписи ***
    const [isRecording, setIsRecording] = useState(false);
    const [audioBlob, setAudioBlob] = useState(null);
    const [micIconState, setMicIconState] = useState('mic'); // 'mic', 'recording', 'sending'

    // Состояния для аудиоплеера
    const [audioProgress, setAudioProgress] = useState({}); // { [msgId]: { currentTime: 0, duration: 0, isPlaying: false, isReady: false, error: null } }
    const audioRefs = useRef({}); // Референции для аудиоэлементов

    // ДОБАВЛЕНО: Состояние для FFmpeg
    const ffmpeg = useRef(new FFmpeg());
    const [ffmpegLoaded, setFfmpegLoaded] = useState(false);

    // ДОБАВЛЕНО: Состояние для отслеживания перетаскивания
    const [isDragging, setIsDragging] = useState(null); // msgId, если перетаскивание активно

    // ДОБАВЛЕНО: использование хука
    const {
      isMessageNew,
      translateExistingMessages,
      translateNextMessage,
      translateMessageFunc,
      translateSingleMessage,
      clearTranslations,
      handleTranslationSettingsSave,
      handleSpellCheckToggle,
      spellCheckMessage,
      translationMode,
      setTranslationMode,
    } = useGroupChatTranslationAndSpellCheck({
      chatId,
      messages,
      setMessages,
      isTranslationEnabled,
      setIsTranslationEnabled,
      translationLanguage,
      setTranslationLanguage,
      translationMode,
      setTranslationMode,
      translationQueue,
      setTranslationQueue,
      isTranslating,
      setIsTranslating,
      setIsSpellCheckEnabled,
    });

    // Мемоизация аудиосообщений
    const memoizedAudioMessages = useMemo(() => {
        return messages.filter((msg) => msg.messageType === 'audio' && msg.fileUrl);
    }, [messages]);

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
        const initializeChat = async () => {
            try {
            // Загружаем настройки пользователя и перевода
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
                setIsTranslationEnabled(localStorage.getItem(`translation_enabled_${chatId}`) === 'true');
                setTranslationLanguage(localStorage.getItem(`translation_language_${chatId}`) || 'auto');
                setTranslationMode(localStorage.getItem(`translation_mode_${chatId}`) || 'selective');
                setIsSpellCheckEnabled(localStorage.getItem(`spellcheck_enabled_${chatId}`) === 'true');
            } else {
                toast.error('User is not authorized');
                navigate('/');
                return;
            }

            // Загружаем сообщения
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
            setTimeout(() => {
                scrollToBottom();
            }, 0);
            } catch (error) {
            console.error('Ошибка при загрузке данных чата:', error);
            toast.error('Failed to load chat data');
            navigate('/');
            }
        };

        initializeChat();
    }, [chatId, setContextUserId, setNotificationUserId, navigate]);

    useEffect(() => {
        if (isTranslationEnabled && translationMode === 'all' && messages.length > 0) {
            console.log('Translating existing messages with language:', translationLanguage);
            translateExistingMessages();
        } else if (!isTranslationEnabled) {
            console.log('Clearing translations');
            clearTranslations();
        }
    }, [isTranslationEnabled, translationLanguage, translationMode, messages, translateExistingMessages, clearTranslations]);

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
                if (isTranslationEnabled && (translationMode === 'new' || translationMode === 'all') && isMessageNew(newMessage)) {
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
    }, [chatId, isTranslationEnabled, translationLanguage, translationMode, isMessageNew]); // Изменено: добавлены зависимости

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

    // ДОБАВЛЕНО: useEffect для добавления/удаления слушателей событий перетаскивания
    useEffect(() => {
        if (isDragging) {
            window.addEventListener('mousemove', handleDragMove);
            window.addEventListener('mouseup', handleDragEnd);
        }
        return () => {
            window.removeEventListener('mousemove', handleDragMove);
            window.removeEventListener('mouseup', handleDragEnd);
        };
    }, [isDragging]);

    useEffect(() => {
        const visibleAudioMessages = memoizedAudioMessages.slice(-10); // Ограничиваем последними 10 аудиосообщениями
        visibleAudioMessages.forEach((msg) => {
            if (
                audioRefs.current[msg.id] &&
                !audioProgress[msg.id]?.isReady &&
                !audioProgress[msg.id]?.isPreloading
            ) {
                setAudioProgress((prev) => ({
                    ...prev,
                    [msg.id]: {
                        ...prev[msg.id] || {},
                        isPreloading: true,
                    },
                }));
                preloadAudio(msg.id, msg.fileUrl);
            }
        });
    }, [memoizedAudioMessages]);

    const updateMessages = (newMessage) => {
        setMessages((prevMessages) => {
            if (newMessage.content === 'Message deleted') {
            return prevMessages.filter((msg) => msg.id !== newMessage.id);
            }

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
              (translationMode === 'new' || translationMode === 'all') &&
              isMessageNew(newMessage) &&
              (!updatedMessages[existingMessageIndex].translatedContent ||
                    updatedMessages[existingMessageIndex].translationLanguage !== translationLanguage)
            ) {
              setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
            }
            return updatedMessages;
            } else {
            const updatedMessages = [...prevMessages, { ...newMessage, showOriginal: true }];
            localStorage.setItem(`last_visit_chat_${chatId}`, newMessage.timestamp);
            if (isTranslationEnabled && isMessageNew(newMessage)) {
                setTranslationQueue((prevQueue) => [...prevQueue, newMessage]);
            }
            return updatedMessages;
            }
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
        if ((content.trim() || selectedFile || audioBlob) && userId) {
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
                fileUrl: selectedFile ? await uploadFile(selectedFile) : audioBlob ? await uploadFile(audioBlob) : null,
                timestamp: new Date().toISOString(),
                messageType: audioBlob ? 'audio' : selectedFile ? 'file' : 'text',
                contentType: audioBlob ? 'audio/mpeg' : selectedFile ? selectedFile.type : null // ДОБАВЛЕНО: Указываем contentType для аудио
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

                resetForm();
            } else {
                console.error('STOMP client is not connected');
                toast.error('WebSocket not connected');
            }
        } else {
            console.warn('Content, file, or audio is missing');
            toast.error('Please provide a message, file, or audio');
        }
    };

    const handleKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey && (content.trim() || selectedFile)) {
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
        textAreaRef.current.style.height = `${newRows * lineHeight + 20}px`; // Учитываем padding (10px + 10px)
        // ДОБАВЛЕНО: Управление классом scroll-enabled для скроллбара
        if (newRows >= 5) {
            textAreaRef.current.classList.add('scroll-enabled');
        } else {
            textAreaRef.current.classList.remove('scroll-enabled');
        }
    };

    const calculateRows = (text) => {
        if (!text) return 1; // Если текст пустой, возвращаем 1 строку
        const lines = text.split('\n'); // Разбиваем текст на строки по переносам
        let totalRows = lines.length; // Количество строк по переносам
        const canvas = document.createElement('canvas');
        const context = canvas.getContext('2d');
        context.font = window.innerWidth <= 800 ? '14px Roboto' : '16px Roboto';
        lines.forEach((line) => {
            if (line.length > 0) {
                const textWidth = context.measureText(line).width;
                const textareaWidth = textAreaRef.current.clientWidth - (window.innerWidth <= 800 ? 36 : 40); // Учитываем padding
                if (textWidth > textareaWidth) {
                    totalRows += Math.floor(textWidth / textareaWidth); // Дополнительные строки при превышении ширины
                }
            }
        });
        return Math.max(1, totalRows); // Минимально 1 строка
    };

    // Определяем, показывать ли аватар
    const shouldShowAvatar = (msg) => {
        return msg.sender.id !== userId;
    };

    // *** ДОБАВЛЕНО: Метод для начала записи аудио ***
    const startRecording = async () => {
        try {
            // ДОБАВЛЕНО: Загружаем FFmpeg перед началом записи
            await loadFFmpeg();

            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            let mimeType = 'audio/webm;codecs=opus';
            if (!MediaRecorder.isTypeSupported(mimeType)) {
                console.warn('audio/webm;codecs=opus not supported, falling back to audio/webm');
                mimeType = 'audio/webm';
                if (!MediaRecorder.isTypeSupported(mimeType)) {
                    console.error('audio/webm not supported');
                    toast.error('Audio recording not supported in this browser');
                    return;
                }
            }
            const mediaRecorder = new MediaRecorder(stream, { 
                mimeType,
                audioBitsPerSecond: 128000 // Устанавливаем битрейт
            });
            mediaRecorderRef.current = mediaRecorder;
            const chunks = [];

            mediaRecorder.ondataavailable = (e) => {
                if (e.data.size > 0) {
                    chunks.push(e.data);
                }
            };

            mediaRecorder.onstop = async () => {
                const webmBlob = new Blob(chunks, { type: mimeType });
                console.log(`Recorded audio: size=${webmBlob.size}, type=${webmBlob.type}`);

                // ДОБАВЛЕНО: Конвертация в .mp3
                try {
                    const inputName = 'input.webm';
                    const outputName = 'output.mp3';
                    const arrayBuffer = await webmBlob.arrayBuffer();
                    await ffmpeg.current.writeFile(inputName, new Uint8Array(arrayBuffer));
                    await ffmpeg.current.exec(['-i', inputName, '-c:a', 'libmp3lame', '-b:a', '128k', outputName]);
                    const mp3Data = await ffmpeg.current.readFile(outputName);
                    const mp3Blob = new Blob([mp3Data.buffer], { type: 'audio/mpeg' });
                    console.log(`Converted audio: size=${mp3Blob.size}, type=${mp3Blob.type}`);
                    setAudioBlob(mp3Blob);
                    stream.getTracks().forEach(track => track.stop());
                    setMicIconState('sending');
                    sendAudioMessage(mp3Blob);
                } catch (error) {
                    console.error('Error converting to mp3:', error);
                    toast.error('Failed to convert audio');
                    stream.getTracks().forEach(track => track.stop());
                    setMicIconState('mic');
                }
            };

            setMicIconState('recording');
            mediaRecorder.start(1000); // Разбиваем на 1-секундные чанки
            setIsRecording(true);
            toast.info('Recording started');
        } catch (error) {
            console.error('Error starting recording:', error);
            toast.error('Failed to start recording');
            setMicIconState('mic');
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

    // ДОБАВЛЕНО: Загрузка FFmpeg
    const loadFFmpeg = async () => {
        if (!ffmpegLoaded) {
            try {
                await ffmpeg.current.load();
                setFfmpegLoaded(true);
                console.log('FFmpeg loaded successfully');
            } catch (error) {
                console.error('Failed to load FFmpeg:', error);
                toast.error('Failed to initialize audio conversion');
                throw error;
            }
        }
    };

    const resetForm = () => {
        setContent('');
        setSelectedFile(null);
        setAudioBlob(null); // Сброс аудио
        setEditingMessageId(null);
        setRows(1);
        setMicIconState('mic'); // Сброс иконки микрофона
        if (fileInputRef.current) {
            fileInputRef.current.value = null;
        }
        if (textAreaRef.current) {
            textAreaRef.current.style.height = '44px';
            // ДОБАВЛЕНО: Удаляем класс scroll-enabled при сбросе
            textAreaRef.current.classList.remove('scroll-enabled');
        }
    };

    // *** ДОБАВЛЕНО: Метод для отправки аудиосообщения ***
    const sendAudioMessage = async (audioBlob) => {
        if (!audioBlob || !userId) {
            console.warn('Audio or userId is missing');
            toast.error('Failed to send audio message');
            setMicIconState('mic');
            return;
        }

        const currentUsername = username;
        const chatName = await getChatName(chatId);

        // ДОБАВЛЕНО: Создаём файл с расширением .mp3
        const audioFile = new File([audioBlob], `audio_${Date.now()}.mp3`, { type: 'audio/mpeg' });

        const message = {
            id: Date.now(),
            content: '',
            sender: { id: userId, username: currentUsername },
            chat: { id: chatId, name: chatName },
            fileUrl: await uploadFile(audioFile),
            timestamp: new Date().toISOString(),
            messageType: 'audio',
            contentType: 'audio/mpeg' // ДОБАВЛЕНО: Указываем contentType для .mp3
        };

        console.log('Sending audio message:', message);

        if (client && client.connected) {
            client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
            setAudioBlob(null);
            console.log('Audio message sent');
            setMicIconState('mic');
        } else {
            console.error('STOMP client is not connected');
            toast.error('WebSocket not connected');
            setMicIconState('mic');
        }
    };

    // Форматирование времени в формате MM:SS
    const formatAudioTime = (seconds) => {
        if (isNaN(seconds) || seconds === 0 || seconds === Infinity) return '0:00';
        const minutes = Math.floor(seconds / 60);
        const remainingSeconds = Math.floor(seconds % 60);
        return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
    };

    // Обработчик изменения времени воспроизведения
    const handleTimeUpdate = (msgId, audioElement) => {
        setAudioProgress((prev) => {
            const prevData = prev[msgId] || {};
            if (
                prevData.currentTime === audioElement.currentTime &&
                prevData.isPlaying === !audioElement.paused &&
                prevData.duration === audioElement.duration
            ) {
                return prev;
            }
            return {
                ...prev,
                [msgId]: {
                    ...prevData,
                    currentTime: audioElement.currentTime,
                    duration: audioElement.duration || prevData.duration || 0,
                    isPlaying: !audioElement.paused,
                    isReady: prevData.isReady || false,
                    error: prevData.error || null,
                },
            };
        });
    };

    // Обработчик загрузки метаданных
    const handleLoadedMetadata = (msgId, audioElement) => {
        console.log(`Metadata loaded for msg ${msgId}: duration=${audioElement.duration}, readyState=${audioElement.readyState}`);
        const duration = isFinite(audioElement.duration) && audioElement.duration > 0 ? audioElement.duration : 0;

        setAudioProgress((prev) => {
            const prevData = prev[msgId] || {};
            if (prevData.isReady && prevData.duration === duration) {
                return prev;
            }
            return {
                ...prev,
                [msgId]: {
                    ...prevData,
                    currentTime: prevData.currentTime || 0,
                    duration,
                    isPlaying: prevData.isPlaying || false,
                    isReady: audioElement.readyState >= 2,
                    error: duration === 0 ? 'Duration unavailable: server issue' : null,
                    isPreloading: false,
                },
            };
        });
    };

    // Обработчик события canplay
    const handleCanPlay = (msgId) => {
        console.log(`Audio can play for msg ${msgId}, readyState=${audioRefs.current[msgId]?.readyState}`);
        setAudioProgress((prev) => ({
            ...prev,
            [msgId]: {
                ...prev[msgId] || {},
                isReady: true,
                error: null,
            },
        }));
    };

    // Обработчик клика по дорожке
    const handleSeek = (msgId, e) => {
        const audio = audioRefs.current[msgId];
        if (!audio || !audioProgress[msgId]?.isReady) {
            console.log(`Seek blocked for msg ${msgId}: audio not ready`);
            return;
        }
        const progressBar = e.currentTarget;
        const rect = progressBar.getBoundingClientRect();
        const clickX = e.clientX - rect.left;
        const width = rect.width;
        const seekTime = (clickX / width) * audio.duration;
        audio.currentTime = seekTime;
        setAudioProgress((prev) => ({
            ...prev,
            [msgId]: {
                ...prev[msgId],
                currentTime: seekTime,
            },
        }));
    };

    // Обработчик ошибок аудио
    const handleAudioError = (msgId, error) => {
        const audio = audioRefs.current[msgId];
        console.error(`Audio error for msg ${msgId}:`, error, 'URL:', audio?.src, 'readyState:', audio?.readyState);
        setAudioProgress((prev) => {
            const prevData = prev[msgId] || {};
            if (prevData.isReady && (prevData.currentTime > 0 || audio?.readyState >= 2)) {
                console.log(`Ignoring error for msg ${msgId}: audio playable, readyState: ${audio?.readyState}`);
                return prev;
            }
            return {
                ...prev,
                [msgId]: {
                    ...prevData,
                    isReady: false,
                    isPlaying: false,
                    error: 'Failed to load audio: connection issue',
                },
            };
        });
    };

    // Обработчик окончания воспроизведения
    const handleAudioEnded = (msgId) => {
        console.log(`Audio ended for msg ${msgId}`);
        const audio = audioRefs.current[msgId];
        setAudioProgress((prev) => ({
            ...prev,
            [msgId]: {
                ...prev[msgId],
                currentTime: 0,
                isPlaying: false,
                isReady: audio?.readyState >= 2,
                error: null,
            },
        }));
        if (audio) {
            audio.currentTime = 0;
            audio.load();
            console.log(`Audio reloaded for msg ${msgId} to maintain playability`);
        }
    };

    // Предзагрузка аудио
    const preloadAudio = (msgId, url) => {
        const audio = audioRefs.current[msgId];
        if (audio && !audioProgress[msgId]?.isReady && !audioProgress[msgId]?.isPreloading) {
            setAudioProgress((prev) => ({
                ...prev,
                [msgId]: { ...prev[msgId], isPreloading: true },
            }));
            if (audio.src === url && audio.readyState >= 2) {
                setAudioProgress((prev) => ({
                    ...prev,
                    [msgId]: {
                        ...prev[msgId],
                        isPreloading: false,
                        isReady: audio.readyState >= 2,
                        error: audio.duration === Infinity ? 'Duration unavailable: file issue' : null,
                    },
                }));
                return;
            }
            const cacheBusterUrl = `${url}?t=${Date.now()}`; // Обход кэша
            audio.src = cacheBusterUrl;
            // ДОБАВЛЕНО: Устанавливаем тип в зависимости от расширения файла
            audio.type = url.endsWith('.mp3') ? 'audio/mpeg' : 'audio/webm';
            audio.load();
            audio.addEventListener('loadedmetadata', () => {
                console.log(`Loaded metadata for msg ${msgId}: duration=${audio.duration}, readyState=${audio.readyState}`);
                setAudioProgress((prev) => ({
                    ...prev,
                    [msgId]: {
                        ...prev[msgId],
                        isPreloading: false,
                        isReady: audio.readyState >= 2,
                        error: audio.duration === Infinity ? 'Duration unavailable: file issue' : null,
                    },
                }));
            }, { once: true });
            audio.addEventListener('canplay', () => {
                console.log(`Can play msg ${msgId}: readyState=${audio.readyState}`);
                setAudioProgress((prev) => ({
                    ...prev,
                    [msgId]: {
                        ...prev[msgId],
                        isPreloading: false,
                        isReady: true,
                    },
                }));
            }, { once: true });
            audio.addEventListener('error', (e) => {
                const errorMsg = e.target.error ? `Error code: ${e.target.error.code}, message: ${e.target.error.message}` : 'Unknown error';
                console.error(`Preload error for msg ${msgId}: ${errorMsg}, URL: ${url}, readyState: ${audio.readyState}`);
                setAudioProgress((prev) => {
                    const prevData = prev[msgId] || {};
                    if (!prevData.retryCount || prevData.retryCount < 2) {
                        setTimeout(() => {
                            console.log(`Retrying preload for msg ${msgId}, attempt ${prevData.retryCount + 1}`);
                            audio.src = `${url}?t=${Date.now()}`;
                            audio.load();
                        }, 2000);
                        return {
                            ...prev,
                            [msgId]: {
                                ...prevData,
                                isPreloading: false,
                                isReady: false,
                                error: 'Retrying to preload audio...',
                                retryCount: (prevData.retryCount || 0) + 1,
                            },
                        };
                    }
                    toast.error('Failed to preload audio message');
                    return {
                        ...prev,
                        [msgId]: {
                            ...prevData,
                            isPreloading: false,
                            isReady: false,
                            error: `Failed to preload audio: ${errorMsg}`,
                        },
                    };
                });
            }, { once: true });
        }
    };

    // ДОБАВЛЕНО: Обработчик начала перетаскивания
    const handleDragStart = (msgId, e) => {
        e.preventDefault();
        const audio = audioRefs.current[msgId];
        if (!audio || !audioProgress[msgId]?.isReady) {
            console.log(`Drag blocked for msg ${msgId}: audio not ready`);
            return;
        }
        setIsDragging(msgId);
        handleSeek(msgId, e); // Выполняем начальную перемотку
    };

    // ДОБАВЛЕНО: Обработчик движения мыши при перетаскивании
    const handleDragMove = (e) => {
        if (isDragging) {
            handleSeek(isDragging, e);
        }
    };

    // ДОБАВЛЕНО: Обработчик окончания перетаскивания
    const handleDragEnd = () => {
        setIsDragging(null);
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
                                        {/* Рендеринг текстового сообщения */}
                                        {msg.content && (
                                            <div
                                                className={`message-content ${isTranslationEnabled && msg.messageType !== 'audio' ? 'translate-enabled' : ''}`}
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
                                                {isTranslationEnabled && msg.messageType !== 'audio' && (
                                                    <div className="message-translate-container">
                                                        <button
                                                            className="message-translate-button"
                                                            onClick={() => translateSingleMessage(msg)}
                                                            data-tooltip="Translate message"
                                                        >
                                                            🌐
                                                        </button>
                                                    </div>
                                                )}
                                            </div>
                                        )}
                                        {/* Рендеринг аудио или файла */}
                                        {msg.messageType === 'audio' && msg.fileUrl ? (
                                            <div className="audio-message-container">
                                                <div className="audio-player-wrapper">
                                                    <audio
                                                        ref={(el) => {
                                                            if (el && audioRefs.current[msg.id] !== el) {
                                                                audioRefs.current[msg.id] = el;
                                                                if (!audioProgress[msg.id]?.isPreloading) {
                                                                    console.log(`Audio ref set for msg ${msg.id}, readyState: ${el?.readyState}`);
                                                                }
                                                            }
                                                        }}
                                                        onTimeUpdate={() => handleTimeUpdate(msg.id, audioRefs.current[msg.id])}
                                                        onLoadedMetadata={() => handleLoadedMetadata(msg.id, audioRefs.current[msg.id])}
                                                        onCanPlay={() => handleCanPlay(msg.id)}
                                                        onError={(e) => handleAudioError(msg.id, e)}
                                                        onEnded={() => handleAudioEnded(msg.id)}
                                                        className="audio-player"
                                                        preload="auto"
                                                    >
                                                        <source src={msg.fileUrl} type={msg.fileUrl.endsWith('.mp3') ? 'audio/mpeg' : 'audio/webm'} />
                                                        Your browser does not support the audio element.
                                                    </audio>
                                                    {audioProgress[msg.id]?.error ? (
                                                        <span className="audio-error">{audioProgress[msg.id].error}</span>
                                                    ) : (
                                                        <div className="audio-controls">
                                                            <button
                                                                className="audio-play-pause"
                                                                onClick={() => {
                                                                    const audio = audioRefs.current[msg.id];
                                                                    if (!audio || !audioProgress[msg.id]?.isReady) {
                                                                        console.log(`Play blocked for msg ${msg.id}:`, audioProgress[msg.id], 'readyState:', audio?.readyState);
                                                                        return;
                                                                    }
                                                                    if (audio.paused) {
                                                                        audio.play().catch((e) => {
                                                                            console.error('Play error:', e);
                                                                            handleAudioError(msg.id, e);
                                                                        });
                                                                    } else {
                                                                        audio.pause();
                                                                    }
                                                                    setAudioProgress((prev) => ({
                                                                        ...prev,
                                                                        [msg.id]: {
                                                                            ...prev[msg.id],
                                                                            isPlaying: audio.paused ? false : true,
                                                                        },
                                                                    }));
                                                                }}
                                                                disabled={!audioProgress[msg.id]?.isReady}
                                                            >
                                                                {audioProgress[msg.id]?.isPlaying ? '⏸' : '▶'}
                                                            </button>
                                                            <div
                                                                className="audio-progress-bar"
                                                                onClick={(e) => handleSeek(msg.id, e)}
                                                                onMouseDown={(e) => handleDragStart(msg.id, e)}
                                                                style={{ cursor: audioProgress[msg.id]?.duration === 0 ? 'not-allowed' : 'pointer' }}
                                                            >
                                                                <div
                                                                    className="audio-progress"
                                                                    style={{
                                                                        width: `${
                                                                            audioProgress[msg.id]?.duration && audioProgress[msg.id]?.isReady && audioProgress[msg.id]?.duration !== 0
                                                                                ? (audioProgress[msg.id].currentTime / audioProgress[msg.id].duration) * 100
                                                                                : 0
                                                                        }%`
                                                                    }}
                                                                >
                                                                    {/* ДОБАВЛЕНО: Ползунок */}
                                                                    <div className="audio-progress-thumb"></div>
                                                                </div>
                                                            </div>
                                                            <span className="audio-time">
                                                                {formatAudioTime(audioProgress[msg.id]?.currentTime || 0)} /{' '}
                                                                {formatAudioTime(audioProgress[msg.id]?.duration || 0)}
                                                            </span>
                                                        </div>
                                                    )}
                                                </div>
                                            </div>
                                        ) : msg.fileUrl ? (
                                            <a href={msg.fileUrl} download className="file-link">
                                                📥 Download file
                                            </a>
                                        ) : null}
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
                    <div className="textarea-inner">
                        <span
                            onClick={() => fileInputRef.current.click()}
                            className="attach-icon"
                            role="button"
                            aria-label="Attach File"
                        >
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                                <path d="M16.5 6v11.5c0 2.21-1.79 4-4 4s-4-1.79-4-4V5a2.5 2.5 0 015 0v10.5c0 .55-.45 1-1 1s-1-.45-1-1V6H10v9.5a2.5 2.5 0 005 0V5c0-2.21-1.79-4-4-4S7 2.79 7 5v12.5c0 3.04 2.46 5.5 5.5 5.5s5.5-2.46 5.5-5.5V6h-1.5z" fill="#757575"/>
                            </svg>
                        </span>
                        <textarea
                            ref={textAreaRef}
                            value={content}
                            onChange={handleInputChange}
                            onKeyDown={handleKeyDown}
                            placeholder="Enter your message..."
                            className="input-message"
                            rows={rows}
                        />
                        <input
                            type="file"
                            onChange={handleFileChange}
                            className="file-input"
                            ref={fileInputRef}
                            id="file-input"
                        />
                        {content.length === 0 && micIconState !== 'sending' && !selectedFile && (
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
                        {(content.length > 0 || selectedFile) && (
                            <div className="send-icon-container">
                                <span
                                    onClick={sendMessage}
                                    className="send-icon"
                                    role="button"
                                    aria-label="Send Message"
                                >
                                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                                        <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" fill="#0288D1"/>
                                    </svg>
                                </span>
                            </div>
                        )}
                    </div>
                    {selectedFile && (
                        <div className="attached-file">
                            <span className="attached-file-name">{selectedFile.name}</span>
                            <button
                                className="remove-file-button"
                                onClick={() => {
                                    setSelectedFile(null);
                                    if (fileInputRef.current) {
                                        fileInputRef.current.value = null;
                                    }
                                }}
                                aria-label="Remove attached file"
                            >
                                ✕
                            </button>
                        </div>
                    )}
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