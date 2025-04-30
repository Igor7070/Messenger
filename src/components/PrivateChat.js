import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages } from '../api/messages';
import '../styles/PrivateChat.css';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import { useParams, useNavigate } from 'react-router-dom';
import { getChatById } from '../api/chats';
import IncomingCallNotification from './IncomingCallNotification';
import { useUserInteraction } from './UserInteractionContext';

const PrivateChat = () => {
    const [messages, setMessages] = useState([]);
    const [content, setContent] = useState('');
    const [selectedFile, setSelectedFile] = useState(null);
    const [userId, setUserId] = useState(Number(localStorage.getItem('userId')));
    const [username, setUsername] = useState(localStorage.getItem('username'));
    const [client, setClient] = useState(null);
    const [chatId, setChatId] = useState(null);
    const [chatName, setChatName] = useState('');
    const [displayChatName, setDisplayChatName] = useState('');
    const [editingMessageId, setEditingMessageId] = useState(null);
    const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, message: null });
    const messagesEndRef = useRef(null);
    const fileInputRef = useRef(null);
    const { chatId: chatIdParam } = useParams();

    const [currentUserId, setCurrentUserId] = useState(null);
    const [incomingCall, setIncomingCall] = useState(null);
    const { userInteracted, handleInteraction } = useUserInteraction();
    const stompClient = useRef(null);
    const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/melodiya_kovboy.mp3'));
    audioRef.current.loop = true;
    const navigate = useNavigate();

    useEffect(() => {
        const fetchChatData = async () => {
            console.log('fetchChatData is working...');
            if (!chatIdParam || !username) return;

            try {
                const chatData = await getChatById(chatIdParam);
                setChatName(chatData.name);
                const names = chatData.name.split('_');
                const interlocutorName = names.find(name => name !== username);
                setDisplayChatName(`Chat with ${interlocutorName}`);
                setChatId(chatIdParam);

                const initialMessages = await getMessages(chatIdParam);
                const updatedMessages = initialMessages.map(msg => ({
                    ...msg,
                    delivered: msg.delivered_status,
                    read: msg.read_status
                }));

                setMessages(updatedMessages);
            } catch (error) {
                console.error('Ошибка при загрузке данных о чате:', error);
            }
        };

        fetchChatData();
    }, [chatIdParam, username]);

    useEffect(() => {
        if (!chatId) return;

        setCurrentUserId(Number(userId));

        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');
                
                stompClient.subscribe(`/topic/chat/${chatId}/messages`, (message) => {
                    const newMessage = JSON.parse(message.body);
                    console.log('NewMessage: ', newMessage);
                    updateMessages(newMessage);
                });

                stompClient.subscribe(`/topic/notifications/${userId}`, (message) => {
                    const notification = message.body;
                    console.log("Received notification:", notification);
    
                    const chatIdMatch = notification.match(/\(chatId: (\d+)\)/);
                    const notificationChatId = chatIdMatch ? chatIdMatch[1] : null;
    
                    if (notificationChatId && notificationChatId !== chatId) {
                        const toastId = toast.info(`Новое сообщение от: ${notification}`, {
                            autoClose: false,
                            onClick: () => {
                                navigate(`/private-chat/${notificationChatId}`);
                                toast.dismiss(toastId);
                            },
                        });
                    }
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
    }, [chatId, userId]);

    useEffect(() => {
        const interval = setInterval(() => {
            if (client && client.connected && messages.length > 0) {
                const unreadMessages = messages.some(msg => !msg.read);
                if (unreadMessages) {
                    markAllMessagesAsRead(messages);
                    clearInterval(interval);
                }
            }
        }, 1000);

        return () => clearInterval(interval);
    }, [messages, client]);

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
        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            onConnect: () => {
                console.log('Connected to WebSocket in Home.js');
                console.log("currentUserId:", currentUserId);
                stompClient.current.subscribe(`/topic/calls/${currentUserId}`, (message) => {
                    const callRequest = JSON.parse(message.body);
                    handleIncomingCall(callRequest);
                });
            },
        });
        stompClient.current.activate();
        
        return () => {
            stompClient.current.deactivate();
        };
    }, [currentUserId]);
    
    const handleIncomingCall = async (callRequest) => {
        console.log("Incoming call request:", callRequest);
        setIncomingCall(callRequest);
        
        const callerId = callRequest.callerId;
        
        try {
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`);
            if (response.ok) {
                const callerData = await response.json();
                const callerName = callerData.username;
                setIncomingCall({ ...callRequest, callerName });
            } else {
                console.error('Error fetching caller name');
            }
        } catch (error) {
            console.error('Error fetching caller name:', error);
        }
    };
    
    const acceptCall = () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;
    
        console.log('Function acceptCall in Home.js working...');
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
                        signal: {
                            type: 'acceptCall'
                        }
                    });
                        
                    navigate(`/voice-call/${incomingCall.roomId}`, { 
                        state: { 
                            incomingCall, 
                            isCaller: false, 
                            currentUserId: currentUserId, 
                            callerName: callerData.username,
                            recipientName: recipientData.username
                        } 
                    });
                } else {
                    console.error('Error fetching user names');
                }
            } catch (error) {
                console.error('Error fetching user names:', error);
            }
        };
        
        fetchUserNames();
    };
    
    const rejectCall = () => {
        audioRef.current.pause();
        audioRef.current.currentTime = 0;
    
        setIncomingCall(null);
        sendSignal({
            from: currentUserId,
            signal: {
                type: 'rejectCall'
            }
        });
    };
    
    const sendSignal = (signalMessage) => {
        console.log('Function sendSignal in VoiceCall.js working...');
        console.log("Sending signal:", JSON.stringify(signalMessage, null, 2));
        const roomId = incomingCall.roomId;
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`, 
            body: JSON.stringify(signalMessage),
        });
        console.log('Signal sent successfully.');
    };

    const markAllMessagesAsRead = (updatedMessages) => {
        console.log('markAllMessagesAsRead is working...');
        if (Array.isArray(updatedMessages)) {
            updatedMessages.forEach(msg => {
                if (msg.sender.id !== userId && (msg.read === false || msg.read === null)) {
                    if (client && client.connected) {
                        client.publish({ destination: `/app/readMessage/${chatId}`, body: JSON.stringify(msg.id) });
                        setMessages(prevMessages => 
                            prevMessages.map(message => 
                                message.id === msg.id ? { ...message, read: true } : message
                            )
                        );
                    }
                }
            });
        } else {
            console.error('updatedMessages is undefined or not an array');
        }
    };

    const updateMessages = (newMessage) => {
        setMessages((prevMessages) => {
            if (newMessage.content === "Message deleted") {
                return prevMessages.filter(msg => msg.id !== newMessage.id);
            }

            const existingMessageIndex = prevMessages.findIndex(msg => msg.id === newMessage.id);
            if (existingMessageIndex !== -1) {
                const updatedMessages = [...prevMessages];
                updatedMessages[existingMessageIndex] = {
                    ...newMessage,
                    delivered: newMessage.delivered_status,
                    read: newMessage.read_status
                };
                return updatedMessages;
            } else {
                return [...prevMessages, {
                    ...newMessage,
                    delivered: newMessage.delivered_status,
                    read: newMessage.read_status
                }];
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
            throw new Error('File upload failed');
        }

        const data = await response.json();
        return data.fileUrl;
    };

    const sendMessage = async () => {
        if ((content || selectedFile) && userId) {
            const message = {
                id: editingMessageId || Date.now(),
                content,
                sender: { id: userId, username },
                chat: { id: chatId, name: chatName },
                fileUrl: selectedFile ? await uploadFile(selectedFile) : null,
                delivered: false,
                read: false,
            };
    
            if (client && client.connected) {
                client.publish({ destination: `/app/sendMessage/chat/${chatId}`, body: JSON.stringify(message) });
    
                setTimeout(async () => {
                    const response = await fetch(`https://unlimitedpossibilities12.org/api/messages/search?content=${encodeURIComponent(content)}&senderId=${userId}&chatId=${chatId}`);
                    
                    if (response.ok) {
                        const updatedMessage = await response.json();
                        console.log('updatedMessage:', updatedMessage);
    
                        const messageId = updatedMessage.id;
                        const statusResponse = await fetch(`https://unlimitedpossibilities12.org/api/messages/${messageId}`);
                        
                        if (statusResponse.ok) {
                            const messageStatus = await statusResponse.json();
                            const recipientName = chatName.split('_').find(name => name !== username);
                            console.log('recipientName:', recipientName);
    
                            const userResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`);
                            
                            if (userResponse.ok) {
                                const recipient = await userResponse.json();
                                const recipientId = recipient.id;

                                console.log('recipientId:', recipientId);   
                                console.log('updatedMessage.read: ' + updatedMessage.read);
                                console.log('updatedMessage.read_status: ' + updatedMessage.read_status);
                                console.log('messageStatus.read: ' + messageStatus.read);
                                console.log('messageStatus.read_status: ' + messageStatus.read_status);
                                console.log('userId: ' + userId);

                                if (messageStatus.read_status === false || messageStatus.read_status === null) {
                                    console.log('Notification must be sent...');
                                    await fetch(`https://unlimitedpossibilities12.org/api/messages/notify`, {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({
                                            userId,
                                            recipientId,
                                            content: message.content,
                                            chatId,
                                        }),
                                    });
                                }
                            } else {
                                console.error('Ошибка при получении ID получателя');
                            }
                        } else {
                            console.error('Ошибка при получении статуса сообщения');
                        }
                    } else {
                        console.error('Ошибка при поиске сообщения');
                    }
                }, 3000);
    
                resetForm();
            } else {
                console.error('STOMP client is not connected');
            }
        } else {
            toast.warn('Content is missing');
        }
    };

    const handleKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    };

    const resetForm = () => {
        setContent('');
        setSelectedFile(null);
        setEditingMessageId(null);
        if (fileInputRef.current) {
            fileInputRef.current.value = null;
        }
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

    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages]);

    const shouldShowAvatar = (msg, index) => {
        if (msg.sender.id === userId) return false;
        if (index === 0) return true;
        const prevMessage = messages[index - 1];
        return prevMessage.sender.id !== msg.sender.id;
    };

    return (
        <div className="private-chat">
            <div className="private-chat-name">{displayChatName}</div>
            <div className="private-message-list">
                {messages.map((msg, index) => (
                    <div 
                        key={msg.id}
                        className={`private-message ${msg.sender.id === userId ? 'current-user' : 'other-user'}`}
                        onContextMenu={(e) => handleRightClick(e, msg)}
                    >
                        <div className="private-message-wrapper">
                            {shouldShowAvatar(msg, index) && (
                                <div className="private-avatar-container">
                                    {msg.sender.avatar ? (
                                        <img 
                                            src={`https://unlimitedpossibilities12.org${msg.sender.avatar}`} 
                                            alt={`${msg.sender.username} avatar`} 
                                            className="private-message-avatar"
                                        />
                                    ) : (
                                        <div className="private-message-avatar">
                                            {msg.sender.username.charAt(0).toUpperCase()}
                                        </div>
                                    )}
                                </div>
                            )}
                            <div className={`private-message-content-container ${shouldShowAvatar(msg, index) ? '' : 'no-avatar'}`}>
                                <div className="private-message-body">
                                    <span className="private-message-content">{msg.content}</span>
                                    {msg.fileUrl && (
                                        <a 
                                            href={msg.fileUrl} 
                                            download 
                                            className="private-file-link"
                                        >
                                            📥 Download file
                                        </a>
                                    )}
                                    {msg.sender.id === userId && (
                                        <div className="private-message-status">
                                            {msg.delivered && <span className="private-delivery-indicator">✔️</span>}
                                            {msg.read && <span className="private-read-indicator">✔️</span>}
                                        </div>
                                    )}
                                </div>
                            </div>
                        </div>
                    </div>
                ))}
                <div ref={messagesEndRef} />
            </div>
            <div className="private-input-container">
                <textarea
                    value={content}
                    onChange={(e) => setContent(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder="Enter your message..."
                    className="private-input-message"
                />
                <div className="private-button-container">
                    <input
                        type="file"
                        onChange={(e) => setSelectedFile(e.target.files[0])}
                        className="private-file-input"
                        ref={fileInputRef}
                        id="private-file-input"
                    />
                    <label htmlFor="private-file-input" className="private-file-input-label">Choose File</label>
                    <button onClick={sendMessage} className="private-send-button">Send</button>
                </div>
            </div>
            <ToastContainer />
            {contextMenu.visible && (
                <div 
                    className="private-context-menu" 
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
                    onAccept={acceptCall} 
                    onReject={rejectCall} 
                />
            )}
        </div>
    );
};

export default PrivateChat;