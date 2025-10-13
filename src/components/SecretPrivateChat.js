import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getMessages } from '../api/messages';
import '../styles/SecretPrivateChat.css';
import { toast } from 'react-toastify';
import { useParams, useNavigate } from 'react-router-dom';
import { getChatById } from '../api/chats';
import IncomingCallNotification from './IncomingCallNotification';
import { format, isToday, parseISO } from 'date-fns';
import { useUserInteraction } from '../context/UserInteractionContext';
import { useUserStatusContext } from '../context/UserStatusContext';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import { usePrivateMessageNotification } from '../context/PrivateMessageNotificationContext';
import { FFmpeg } from '@ffmpeg/ffmpeg';
import * as sodium from 'libsodium-wrappers';

export const generateChatName = (username1, username2, isSecret = false) => {
  const names = [username1, username2].sort();
  return isSecret ? `${names[0]}_${names[1]}_secret` : `${names[0]}_${names[1]}`;
};

const fetchPublicKeyWithRetry = async (recipientId, retries = 3, delay = 2000) => {
  for (let attempt = 1; attempt <= retries; attempt++) {
    try {
      console.log(`Fetching public key for userId: ${recipientId}, attempt ${attempt}/${retries}`);
      const publicKeyResponse = await fetch(
        `https://unlimitedpossibilities12.org/api/public_keys/${recipientId}`
      );
      if (publicKeyResponse.ok) {
        const publicKeyData = await publicKeyResponse.json();
        console.log(`Public key fetched for userId: ${recipientId}: ${publicKeyData.publicKey}`);
        return publicKeyData.publicKey;
      } else if (publicKeyResponse.status === 404) {
        console.warn(`Public key not found for userId: ${recipientId}, attempt ${attempt}/${retries}`);
        if (attempt < retries) {
          await new Promise((resolve) => setTimeout(resolve, delay));
          continue;
        }
        throw new Error(`Failed to fetch public key for userId: ${recipientId}, status: ${publicKeyResponse.status}`);
      } else {
        throw new Error(`Unexpected response: ${publicKeyResponse.status}`);
      }
    } catch (error) {
      console.error(`Error fetching public key for userId: ${recipientId}, attempt ${attempt}/${retries}`, error);
      if (attempt === retries) {
        throw error;
      }
      await new Promise((resolve) => setTimeout(resolve, delay));
    }
  }
};

const getPublicKey = async (userId) => {
  console.log(`Fetching public key for userId: ${userId}`);
  try {
    const publicKey = await fetchPublicKeyWithRetry(userId);
    console.log(`Fetched public key for userId: ${userId}, value: ${publicKey}`);
    try {
      const publicKeyBytes = sodium.from_base64(publicKey);
      console.log(`Public key bytes length for userId: ${userId}: ${publicKeyBytes.length}, hex: ${sodium.to_hex(publicKeyBytes)}`);
    } catch (e) {
      console.error(`Invalid fetched public key for userId: ${userId}, error: ${e.message}`);
    }
    return publicKey;
  } catch (error) {
    console.error(`Error fetching public key for userId: ${userId}, error: ${error.message}`);
    throw new Error(`Failed to fetch public key for userId: ${userId}`);
  }
};

const SecretPrivateChat = () => {
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
  const [isRecording, setIsRecording] = useState(false);
  const [audioBlob, setAudioBlob] = useState(null);
  const [micIconState, setMicIconState] = useState('mic');
  const [audioProgress, setAudioProgress] = useState({});
  const [rows, setRows] = useState(1);
  const [charPerLine, setCharPerLine] = useState(50);
  const [isDragging, setIsDragging] = useState(null);
  const [ffmpegLoaded, setFfmpegLoaded] = useState(false);
  const [publicKeys, setPublicKeys] = useState({}); // Хранит публичные ключи участников чата
  const [keyPair, setKeyPair] = useState(null); // Локальная пара ключей пользователя
  const [localMessageContent, setLocalMessageContent] = useState({});
  const [tempMessageIds, setTempMessageIds] = useState({}); // Хранит пары { tempId: serverId }
  const messagesEndRef = useRef(null);
  const messageListRef = useRef(null);
  const fileInputRef = useRef(null);
  const mediaRecorderRef = useRef(null);
  const audioRefs = useRef({});
  const textAreaRef = useRef(null);
  const ffmpeg = useRef(new FFmpeg());
  const { chatId: chatIdParam } = useParams();
  const navigate = useNavigate();
  const { userInteracted, handleInteraction } = useUserInteraction();
  const { usersStatus } = useUserStatusContext();
  const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
  const { setCurrentUserId: setNotificationUserId, setCurrentChatId } = usePrivateMessageNotification();

  // Функции для работы с локальным контентом
  const saveLocalMessageContent = (key, content) => {
    setLocalMessageContent((prev) => {
      const updatedContent = { ...prev, [key]: content };
      try {
        localStorage.setItem(
          `localMessageContent_${chatId}_${userId}`,
          JSON.stringify(updatedContent)
        );
      } catch (error) {
        console.warn('localStorage unavailable, using in-memory storage', error);
      }
      return updatedContent;
    });
  };

  const getLocalMessageContent = (senderId, timestamp) => {
    const key = `${senderId}_${timestamp}`;
    const content = localMessageContent[key] || '';
    console.log(`Retrieving local content for key ${key}: ${content}`);
    return content;
  };

  // Загрузка локального контента из localStorage при инициализации
  useEffect(() => {
    const storedContent = localStorage.getItem(`localMessageContent_${chatId}_${userId}`);
    if (storedContent) {
      try {
        const parsedContent = JSON.parse(storedContent);
        console.log(`Loaded localMessageContent for chatId: ${chatId}, userId: ${userId}`, parsedContent);
        setLocalMessageContent(parsedContent);
      } catch (error) {
        console.error('Failed to parse localMessageContent from localStorage:', error);
      }
    } else {
      console.log(`No localMessageContent found for chatId: ${chatId}, userId: ${userId}`);
    }
  }, [chatId, userId]);

  // Инициализация libsodium и генерация ключей
  useEffect(() => {
    const initializeSodium = async () => {
      await sodium.ready;
      const storedKeyPair = localStorage.getItem(`keyPair_${userId}`);
      console.log(`Checking keyPair for userId: ${userId}, exists: ${!!storedKeyPair}`);
      if (storedKeyPair) {
        const parsedKeyPair = JSON.parse(storedKeyPair);
        console.log(`Private key length: ${parsedKeyPair.privateKey?.length}, Public key length: ${parsedKeyPair.publicKey?.length}`);
        try {
          const privateKeyBytes = sodium.from_base64(parsedKeyPair.privateKey);
          console.log(`Private key bytes length: ${privateKeyBytes.length}`);
        } catch (e) {
          console.error(`Private key decode error for userId: ${userId}, error: ${e.message}`);
        }
        setKeyPair(parsedKeyPair);
      } else {
        console.log(`Generating new key pair for userId: ${userId}`);
        const newKeyPair = sodium.crypto_box_keypair();
        const publicKeyBase64 = sodium.to_base64(newKeyPair.publicKey);
        const privateKeyBase64 = sodium.to_base64(newKeyPair.privateKey);
        localStorage.setItem(`keyPair_${userId}`, JSON.stringify({
          publicKey: publicKeyBase64,
          privateKey: privateKeyBase64
        }));
        setKeyPair({
          publicKey: publicKeyBase64,
          privateKey: privateKeyBase64
        });
        try {
          console.log(`Uploading public key for userId: ${userId}`);
          const response = await fetch(`https://unlimitedpossibilities12.org/api/public_keys`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId, publicKey: publicKeyBase64 }),
          });
          if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Failed to upload public key: ${response.status} - ${errorText}`);
          }
          console.log(`Public key uploaded successfully for userId: ${userId}`);
        } catch (error) {
          console.error(`Error uploading public key for userId: ${userId}`, error);
          toast.error(`Failed to upload public key: ${error.message}`);
        }
      }
    };
    initializeSodium();
  }, [userId]);

  // Загрузка данных чата и сообщений
  useEffect(() => {
    const fetchChatData = async () => {
      // Очистка кэша ключей для теста (удалить после успешного тестирования)
      Object.keys(localStorage)
        .filter((key) => key.startsWith('public_key_'))
        .forEach((key) => localStorage.removeItem(key));
      console.log('Cleared public key cache in localStorage');

      if (!chatIdParam || !username) {
        toast.error('Invalid chat ID or user is not authorized');
        navigate('/');
        return;
      }

      if (!keyPair) {
        console.log('Waiting for keyPair to load...');
        await new Promise((resolve) => {
          const interval = setInterval(() => {
            if (keyPair) {
              clearInterval(interval);
              resolve();
            }
          }, 100);
        });
      }
      console.log('keyPair loaded:', keyPair);

      try {
        const chatData = await getChatById(chatIdParam);
        if (!chatData.isSecret) {
          toast.error('This is not a secret chat');
          navigate('/');
          return;
        }
        const names = chatData.name.replace('_secret', '').split('_');
        const interlocutorName = names.find((name) => name !== username);
        const normalizedChatName = generateChatName(username, interlocutorName, true);
        setChatName(normalizedChatName);
        setDisplayChatName(`Secret Chat with ${interlocutorName}`);
        setChatId(chatIdParam);
        setCurrentChatId(chatIdParam);

        const userResponse = await fetch(
          `https://unlimitedpossibilities12.org/api/users/by-username/${interlocutorName}`
        );
        if (!userResponse.ok) {
          throw new Error('Failed to fetch recipient');
        }
        const recipient = await userResponse.json();
        const recipientId = recipient.id;

        const recipientPublicKey = await getPublicKey(recipientId);
        setPublicKeys((prev) => ({
          ...prev,
          [recipientId]: recipientPublicKey
        }));

        const initialMessages = await getMessages(chatIdParam);
        const senderIds = [...new Set(initialMessages.map((msg) => msg.sender.id))];
        const publicKeysPromises = senderIds
          .filter((senderId) => senderId !== userId && !publicKeys[senderId])
          .map(async (senderId) => {
            const publicKey = await getPublicKey(senderId);
            return { senderId, publicKey };
          });
        const newPublicKeys = await Promise.all(publicKeysPromises);
        setPublicKeys((prev) => ({
          ...prev,
          ...Object.fromEntries(newPublicKeys.map(({ senderId, publicKey }) => [senderId, publicKey]))
        }));

        const decryptedMessages = await Promise.all(
          initialMessages.map(async (msg) => {
            if (msg.messageType === 'text' && msg.encryptedContent && msg.nonce) {
              const serverTimestamp = new Date(msg.timestamp).getTime();
              const localContent = getLocalMessageContent(msg.sender.id, serverTimestamp);
              if (localContent) {
                console.log(`Using cached content for message id: ${msg.id}, sender: ${msg.sender.id}`);
                return {
                  ...msg,
                  content: localContent,
                  delivered: msg.delivered_status,
                  read: msg.read_status,
                };
              }
              if (msg.sender.id === userId) {
                console.log(`No cached content for own message, id: ${msg.id}`);
                return {
                  ...msg,
                  content: 'Content unavailable',
                  delivered: msg.delivered_status,
                  read: msg.read_status,
                };
              }
              try {
                const decryptedContent = await decryptMessage(
                  msg.encryptedContent,
                  msg.nonce,
                  msg.sender.id
                );
                saveLocalMessageContent(`${msg.sender.id}_${serverTimestamp}`, decryptedContent);
                console.log(`Saved decrypted content for message id: ${msg.id}, sender: ${msg.sender.id}`);
                return {
                  ...msg,
                  content: decryptedContent,
                  delivered: msg.delivered_status,
                  read: msg.read_status,
                };
              } catch (error) {
                console.error('Decryption failed for message:', msg.id, error);
                return {
                  ...msg,
                  content: 'Decryption failed',
                  delivered: msg.delivered_status,
                  read: msg.read_status,
                };
              }
            }
            return {
              ...msg,
              delivered: msg.delivered_status,
              read: msg.read_status,
            };
          })
        );
        setMessages(decryptedMessages);
        setTimeout(() => scrollToBottom(), 0);
      } catch (error) {
        console.error('Error fetching chat data:', error);
        toast.error('Failed to load chat data');
        navigate('/');
      }
    };

    fetchChatData();

    const storedUserId = localStorage.getItem('userId');
    if (storedUserId) {
      const userIdNum = parseInt(storedUserId, 10);
      setContextUserId(userIdNum);
      setNotificationUserId(userIdNum);
    }

    return () => {
      setCurrentChatId(null);
    };
  }, [chatIdParam, username, setContextUserId, setNotificationUserId, setCurrentChatId, navigate]);

  // WebSocket-подключение
  useEffect(() => {
    if (!chatId || !userId) {
      console.warn('WebSocket setup skipped: chatId or userId missing', { chatId, userId });
      return;
    }

    const stompClientInstance = new Client({
      webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
      connectTimeout: 30000,
      reconnectDelay: 5000,
      onConnect: () => {
        console.log('Connected to WebSocket for SecretPrivateChat');
        stompClientInstance.subscribe(`/topic/chat/${chatId}/messages`, async (message) => {
          const newMessage = JSON.parse(message.body);
          console.log('NewMessage:', newMessage);
          console.log('Message details for decryption:', {
            id: newMessage.id,
            messageType: newMessage.messageType,
            encryptedContent: newMessage.encryptedContent,
            nonce: newMessage.nonce,
            senderId: newMessage.sender.id,
            isOwnMessage: newMessage.sender.id === userId
          });

          if (newMessage.messageType === 'text' && newMessage.encryptedContent && newMessage.nonce) {
            const serverTimestamp = new Date(newMessage.timestamp).getTime();
            const localContent = getLocalMessageContent(newMessage.sender.id, serverTimestamp);
            if (localContent) {
              console.log(`Using cached content for message id: ${newMessage.id}, sender: ${newMessage.sender.id}`);
              updateMessages({
                ...newMessage,
                content: localContent,
                delivered: newMessage.delivered_status,
                read: newMessage.read_status,
              });
              return;
            }
            if (newMessage.sender.id !== userId) {
              try {
                const cleanedEncryptedContent = newMessage.encryptedContent.replace(/\n/g, '');
                const cleanedNonce = newMessage.nonce.replace(/\n/g, '');

                let publicKey = publicKeys[newMessage.sender.id];
                if (!publicKey) {
                  console.log(`Public key for senderId: ${newMessage.sender.id} not found, fetching...`);
                  publicKey = await getPublicKey(newMessage.sender.id);
                  setPublicKeys((prev) => ({
                    ...prev,
                    [newMessage.sender.id]: publicKey
                  }));
                }

                const decryptedContent = await decryptMessage(
                  cleanedEncryptedContent,
                  cleanedNonce,
                  newMessage.sender.id
                );
                saveLocalMessageContent(`${newMessage.sender.id}_${serverTimestamp}`, decryptedContent);
                console.log(`Saved decrypted content for message id: ${newMessage.id}, sender: ${newMessage.sender.id}`);
                updateMessages({
                  ...newMessage,
                  content: decryptedContent,
                  delivered: newMessage.delivered_status,
                  read: newMessage.read_status,
                });
              } catch (error) {
                console.error('Decryption failed for new message:', newMessage.id, error);
                updateMessages({
                  ...newMessage,
                  content: 'Decryption failed',
                  delivered: newMessage.delivered_status,
                  read: newMessage.read_status,
                });
                toast.error(`Failed to decrypt message: ${error.message}`);
              }
            } else {
              updateMessages({
                ...newMessage,
                delivered: newMessage.delivered_status,
                read: newMessage.read_status,
              });
            }
          } else {
            updateMessages({
              ...newMessage,
              delivered: newMessage.delivered_status,
              read: newMessage.read_status,
            });
          }
        });
      },
      onWebSocketError: (error) => {
        console.error('WebSocket error in SecretPrivateChat:', error);
        toast.error('WebSocket connection error');
      },
      onStompError: (frame) => {
        console.error('STOMP error in SecretPrivateChat:', frame);
        toast.error('STOMP protocol error');
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected in SecretPrivateChat');
      },
    });

    setClient(stompClientInstance);
    stompClientInstance.activate();

    return () => {
      console.log('Deactivating WebSocket in SecretPrivateChat');
      stompClientInstance.deactivate();
    };
  }, [chatId, userId, publicKeys]);

  // Отметка сообщений как прочитанных
  useEffect(() => {
    const interval = setInterval(() => {
      if (client && client.connected && messages.length > 0) {
        const unreadMessages = messages.some((msg) => !msg.read);
        if (unreadMessages) {
          markAllMessagesAsRead(messages);
          clearInterval(interval);
        }
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [messages, client]);

  // Обработка взаимодействия пользователя
  useEffect(() => {
    const handleClick = () => {
      handleInteraction();
    };
    document.addEventListener('click', handleClick);
    return () => document.removeEventListener('click', handleClick);
  }, [handleInteraction]);

  // Прокрутка к последнему сообщению
  useEffect(() => {
    if (messages.length > 0) {
      setTimeout(() => scrollToBottom(), 0);
    }
  }, [messages.length]);

  // Вычисление символов на строку
  useEffect(() => {
    calculateCharPerLine();
    window.addEventListener('resize', calculateCharPerLine);
    return () => window.removeEventListener('resize', calculateCharPerLine);
  }, []);

  // Обработчики перетаскивания для аудиоплеера
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

  // Предзагрузка аудио
  useEffect(() => {
    const audioMessages = messages.filter((msg) => msg.messageType === 'audio' && msg.fileUrl);
    audioMessages.slice(-10).forEach((msg) => {
      if (
        audioRefs.current[msg.id] &&
        !audioProgress[msg.id]?.isReady &&
        !audioProgress[msg.id]?.isPreloading
      ) {
        preloadAudio(msg.id, msg.fileUrl);
      }
    });
  }, [messages]);

  const scrollToBottom = () => {
    if (messagesEndRef.current && messageListRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  };

  const handleAvatarClick = (senderId) => {
    navigate(`/user/${senderId}`);
  };

  const markAllMessagesAsRead = (updatedMessages) => {
    if (Array.isArray(updatedMessages)) {
      updatedMessages.forEach((msg) => {
        if (msg.sender.id !== userId && (msg.read === false || msg.read === null)) {
          if (client && client.connected) {
            client.publish({ destination: `/app/readMessage/${chatId}`, body: JSON.stringify(msg.id) });
            setMessages((prevMessages) =>
              prevMessages.map((message) =>
                message.id === msg.id ? { ...message, read: true } : message
              )
            );
          }
        }
      });
    }
  };

  const updateMessages = (newMessage) => {
    setMessages((prevMessages) => {
      if (newMessage.content === 'Message deleted') {
        return prevMessages.filter((msg) => msg.id !== newMessage.id);
      }

      let content = newMessage.content;
      let tempMessageKey = null;

      if (newMessage.sender.id === userId && newMessage.messageType === 'text') {
        const newTimestamp = new Date(newMessage.timestamp).getTime();
        const tempId = Object.keys(tempMessageIds).find((id) =>
          prevMessages.some(
            (msg) =>
              msg.id === parseInt(id) &&
              msg.sender.id === userId &&
              Math.abs(new Date(msg.timestamp).getTime() - newTimestamp) < 5000
          )
        );

        if (tempId) {
          tempMessageKey = `${userId}_${tempMessageIds[tempId]}`;
          content = getLocalMessageContent(userId, tempMessageIds[tempId]);
          if (content) {
            const newKey = `${userId}_${newTimestamp}`;
            saveLocalMessageContent(newKey, content);
            setLocalMessageContent((prev) => {
              const newContent = { ...prev };
              delete newContent[tempMessageKey];
              try {
                localStorage.setItem(
                  `localMessageContent_${chatId}_${userId}`,
                  JSON.stringify(newContent)
                );
                console.log(`Updated localMessageContent: removed key ${tempMessageKey}, added key ${newKey}`);
              } catch (error) {
                console.error('Failed to update localMessageContent in localStorage:', error);
              }
              return newContent;
            });
            setTempMessageIds((prev) => {
              const newTempIds = { ...prev };
              delete newTempIds[tempId];
              return newTempIds;
            });
          }
        }
      }

      const existingMessageIndex = prevMessages.findIndex(
        (msg) =>
          msg.id === newMessage.id ||
          (tempMessageKey && msg.sender.id === userId && Math.abs(new Date(msg.timestamp).getTime() - new Date(newMessage.timestamp).getTime()) < 5000)
      );

      if (existingMessageIndex !== -1) {
        const existingMessage = prevMessages[existingMessageIndex];
        const updatedMessages = [...prevMessages];
        updatedMessages[existingMessageIndex] = {
          ...existingMessage,
          ...newMessage,
          content: newMessage.sender.id === userId ? (content || existingMessage.content || 'Content unavailable') : newMessage.content,
          delivered: newMessage.delivered_status ?? existingMessage.delivered,
          read: newMessage.read_status ?? existingMessage.read,
          messageType: newMessage.messageType ?? existingMessage.messageType,
        };
        console.log(`Updated message with ID ${newMessage.id}:`, updatedMessages[existingMessageIndex]);
        return updatedMessages;
      } else {
        const newMessageWithContent = {
          ...newMessage,
          content: newMessage.sender.id === userId ? (content || 'Content unavailable') : newMessage.content,
          delivered: newMessage.delivered_status,
          read: newMessage.read_status,
          messageType: newMessage.messageType,
        };
        console.log(`Added new message with ID ${newMessage.id}:`, newMessageWithContent);
        return [...prevMessages, newMessageWithContent];
      }
    });
  };

  const encryptMessage = async (content, recipientId) => {
    if (!keyPair || !publicKeys[recipientId]) {
      throw new Error('Encryption keys not available');
    }
    const nonce = sodium.randombytes_buf(sodium.crypto_box_NONCEBYTES);
    const encrypted = sodium.crypto_box_easy(
      content,
      nonce,
      sodium.from_base64(publicKeys[recipientId]),
      sodium.from_base64(keyPair.privateKey)
    );
    return {
      encryptedContent: sodium.to_base64(encrypted),
      nonce: sodium.to_base64(nonce)
    };
  };

  const decryptMessage = async (encryptedContent, nonce, senderId) => {
    console.log(`decryptMessage called: encryptedContent=${encryptedContent}, nonce=${nonce}, senderId=${senderId}`);
    await sodium.ready;

    const validateBase64 = (str, name) => {
      try {
        sodium.from_base64(str);
        console.log(`Valid base64: ${name}=${str}`);
        return true;
      } catch (e) {
        console.error(`Invalid base64: ${str}, error: ${e.message}`);
        return false;
      }
    };

    const publicKey = await getPublicKey(senderId);
    const privateKey = keyPair?.privateKey;

    console.log(`Validating base64: encryptedContent=${validateBase64(encryptedContent, 'encryptedContent')}, nonce=${validateBase64(nonce, 'nonce')}, publicKey=${validateBase64(publicKey, 'publicKey')}, privateKey=${validateBase64(privateKey, 'privateKey')}`);

    if (!validateBase64(encryptedContent, 'encryptedContent') || !validateBase64(nonce, 'nonce') || !validateBase64(publicKey, 'publicKey') || !validateBase64(privateKey, 'privateKey')) {
      console.error(`Invalid base64 format: encryptedContent=${encryptedContent}, nonce=${nonce}, publicKey=${publicKey}, privateKey=${privateKey}`);
      throw new Error('Invalid base64 format');
    }

    try {
      const encryptedBytes = sodium.from_base64(encryptedContent);
      const nonceBytes = sodium.from_base64(nonce);
      const publicKeyBytes = sodium.from_base64(publicKey);
      const privateKeyBytes = sodium.from_base64(privateKey);

      console.log(`Decryption input: encryptedBytesLength=${encryptedBytes.length}, nonceBytesLength=${nonceBytes.length}, publicKeyBytesLength=${publicKeyBytes.length}, privateKeyBytesLength=${privateKeyBytes.length}`);
      console.log(`Decryption input hex: encryptedBytes=${sodium.to_hex(encryptedBytes)}, nonceBytes=${sodium.to_hex(nonceBytes)}, publicKeyBytes=${sodium.to_hex(publicKeyBytes)}`);

      const decryptedBytes = sodium.crypto_box_open_easy(encryptedBytes, nonceBytes, publicKeyBytes, privateKeyBytes);
      const decryptedMessage = sodium.to_string(decryptedBytes);
      console.log(`Decryption successful: decryptedMessage=${decryptedMessage}`);
      return decryptedMessage;
    } catch (error) {
      console.error(`Decryption error for senderId: ${senderId}, encryptedContent: ${encryptedContent}, nonce: ${nonce}, error: ${error.message}`);
      throw new Error(`Decryption failed: ${error.message}`);
    }
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
    if ((content || selectedFile || audioBlob) && userId && keyPair) {
      try {
        const recipientName = chatName.split('_').find((name) => name !== username);
        const userResponse = await fetch(
          `https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`
        );
        if (!userResponse.ok) {
          throw new Error('Failed to fetch recipient');
        }
        const recipient = await userResponse.json();
        const recipientId = recipient.id;

        const normalizedChatName = generateChatName(username, recipientName, true); // Добавляем isSecret=true
        const tempId = editingMessageId || Date.now();
        const tempTimestamp = Date.now(); // Временный timestamp

        let messageData = {
          id: tempId,
          sender: { id: userId, username },
          chat: { id: chatId, name: normalizedChatName },
          delivered: false,
          read: false,
          timestamp: new Date().toISOString(),
        };

        if (content) {
          saveLocalMessageContent(`${userId}_${tempTimestamp}`, content);
          setTempMessageIds((prev) => ({ ...prev, [tempId]: tempTimestamp }));
          const { encryptedContent, nonce } = await encryptMessage(content, recipientId);
          messageData = {
            ...messageData,
            encryptedContent,
            nonce,
            messageType: 'text',
          };
        } else if (selectedFile) {
          messageData = {
            ...messageData,
            fileUrl: await uploadFile(selectedFile),
            messageType: 'file',
            contentType: selectedFile.type,
          };
        } else if (audioBlob) {
          const audioFile = new File([audioBlob], `audio_${Date.now()}.mp3`, { type: 'audio/mpeg' });
          messageData = {
            ...messageData,
            fileUrl: await uploadFile(audioFile),
            messageType: 'audio',
            contentType: 'audio/mpeg',
          };
        }

        if (client && client.connected) {
          client.publish({
            destination: `/app/sendMessage/chat/${chatId}`,
            body: JSON.stringify({
              ...messageData,
              content: undefined, // Удаляем content перед отправкой на сервер
            }),
          });
          // Локально добавляем сообщение с контентом
          updateMessages(messageData);
          sendNotification(messageData, content || '');
          resetForm();
        } else {
          console.error('STOMP client is not connected');
          toast.error('Failed to send message: no connection');
        }
      } catch (error) {
        console.error('Error sending message:', error);
        toast.error('Failed to send message');
      }
    } else {
      toast.warn('Enter a message or select a file');
    }
  };

  const sendNotification = async (message, contentForNotification) => {
    setTimeout(async () => {
      try {
        const recipientName = chatName.split('_').find((name) => name !== username);
        const userResponse = await fetch(
          `https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`
        );
        if (!userResponse.ok) {
          throw new Error('Failed to fetch recipient');
        }
        const recipient = await userResponse.json();
        const recipientId = recipient.id;

        // Проверяем статус сообщения
        const statusResponse = await fetch(
          `https://unlimitedpossibilities12.org/api/messages/${message.id}`
        );
        if (statusResponse.ok) {
          const messageStatus = await statusResponse.json();
          if (messageStatus.read_status === false || messageStatus.read_status === null) {
            await fetch(`https://unlimitedpossibilities12.org/api/messages/notify`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({
                userId,
                recipientId,
                content: contentForNotification,
                chatId,
              }),
            });
          }
        }
      } catch (error) {
        console.error('Error in sendNotification:', error);
      }
    }, 3000);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey && (content.trim() || selectedFile)) {
      e.preventDefault();
      sendMessage();
    }
  };

  const resetForm = () => {
    setContent('');
    setSelectedFile(null);
    setEditingMessageId(null);
    setRows(1);
    setAudioBlob(null);
    setMicIconState('mic');
    if (fileInputRef.current) {
      fileInputRef.current.value = null;
    }
    if (textAreaRef.current) {
      textAreaRef.current.style.height = '44px';
      textAreaRef.current.classList.remove('scroll-enabled');
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
    if (action === 'edit' && contextMenu.message.messageType === 'text') {
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
      setMessages((prevMessages) => prevMessages.filter((msg) => msg.id !== id));
      toast.success('Message deleted!');
    } else {
      toast.error('Failed to delete message. Try again.');
    }
  };

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

  const shouldShowAvatar = (msg, index) => {
    if (msg.sender.id === userId) return false;
    if (index === 0) return true;
    const prevMessage = messages[index - 1];
    return prevMessage.sender.id !== msg.sender.id;
  };

  const isFirstMessage = (msg, index) => {
    if (msg.sender.id !== userId) return false;
    if (index === 0) return true;
    const prevMessage = messages[index - 1];
    return prevMessage.sender.id !== msg.sender.id;
  };

  const startRecording = async () => {
    try {
      await loadFFmpeg();
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      let mimeType = 'audio/webm;codecs=opus';
      if (!MediaRecorder.isTypeSupported(mimeType)) {
        mimeType = 'audio/webm';
        if (!MediaRecorder.isTypeSupported(mimeType)) {
          toast.error('Audio recording not supported in this browser');
          return;
        }
      }
      const mediaRecorder = new MediaRecorder(stream, {
        mimeType,
        audioBitsPerSecond: 128000
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
        try {
          const inputName = 'input.webm';
          const outputName = 'output.mp3';
          const arrayBuffer = await webmBlob.arrayBuffer();
          await ffmpeg.current.writeFile(inputName, new Uint8Array(arrayBuffer));
          await ffmpeg.current.exec(['-i', inputName, '-c:a', 'libmp3lame', '-b:a', '128k', outputName]);
          const mp3Data = await ffmpeg.current.readFile(outputName);
          const mp3Blob = new Blob([mp3Data.buffer], { type: 'audio/mpeg' });
          setAudioBlob(mp3Blob);
          stream.getTracks().forEach(track => track.stop());
          setMicIconState('sending');
          sendMessage();
        } catch (error) {
          console.error('Error converting to mp3:', error);
          toast.error('Failed to convert audio');
          stream.getTracks().forEach(track => track.stop());
          setMicIconState('mic');
        }
      };

      setMicIconState('recording');
      mediaRecorder.start(1000);
      setIsRecording(true);
      toast.info('Recording started');
    } catch (error) {
      console.error('Error starting recording:', error);
      toast.error('Failed to start recording');
      setMicIconState('mic');
    }
  };

  const stopRecording = () => {
    if (mediaRecorderRef.current && isRecording) {
      setMicIconState('sending');
      mediaRecorderRef.current.stop();
      setIsRecording(false);
      toast.info('Recording stopped');
    }
  };

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

  const calculateCharPerLine = () => {
    if (textAreaRef.current) {
      const canvas = document.createElement('canvas');
      const context = canvas.getContext('2d');
      context.font = window.innerWidth <= 800 ? '14px Roboto' : '16px Roboto';
      const avgCharWidth = context.measureText('m').width;
      const textareaWidth = textAreaRef.current.clientWidth - (window.innerWidth <= 800 ? 36 : 40);
      const chars = Math.floor(textareaWidth / avgCharWidth);
      setCharPerLine(chars);
    }
  };

  const calculateRows = (text) => {
    if (!text) return 1;
    const lines = text.split('\n');
    let totalRows = lines.length;
    const canvas = document.createElement('canvas');
    const context = canvas.getContext('2d');
    context.font = window.innerWidth <= 800 ? '14px Roboto' : '16px Roboto';
    lines.forEach((line) => {
      if (line.length > 0) {
        const textWidth = context.measureText(line).width;
        const textareaWidth = textAreaRef.current.clientWidth - (window.innerWidth <= 800 ? 36 : 40);
        if (textWidth > textareaWidth) {
          totalRows += Math.floor(textWidth / textareaWidth);
        }
      }
    });
    return Math.max(1, totalRows);
  };

  const handleInputChange = (e) => {
    const newValue = e.target.value;
    setContent(newValue);
    const newRows = Math.min(5, calculateRows(newValue));
    setRows(newRows);
    const lineHeight = window.innerWidth <= 800 ? 20 : 24;
    textAreaRef.current.style.height = `${newRows * lineHeight + 20}px`;
    if (newRows >= 5) {
      textAreaRef.current.classList.add('scroll-enabled');
    } else {
      textAreaRef.current.classList.remove('scroll-enabled');
    }
  };

  const formatAudioTime = (seconds) => {
    if (isNaN(seconds) || seconds === 0 || seconds === Infinity) return '0:00';
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.floor(seconds % 60);
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  };

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

  const handleLoadedMetadata = (msgId, audioElement) => {
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

  const handleCanPlay = (msgId) => {
    const audio = audioRefs.current[msgId];
    if (audio && !audioProgress[msgId]?.isReady) {
      setAudioProgress((prev) => ({
        ...prev,
        [msgId]: {
          ...prev[msgId] || {},
          isReady: true,
          error: null,
        },
      }));
    }
  };

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

  const handleAudioError = (msgId, error) => {
    const audio = audioRefs.current[msgId];
    const errorMsg = error.target.error ? `Error code: ${error.target.error.code}, message: ${error.target.error.message}` : 'Unknown error';
    setAudioProgress((prev) => {
      const prevData = prev[msgId] || {};
      if (!prevData.retryCount || prevData.retryCount < 2) {
        setTimeout(() => {
          audio.src = audio.src.split('?')[0] + `?t=${Date.now()}`;
          audio.load();
        }, 2000);
        return {
          ...prev,
          [msgId]: {
            ...prevData,
            isReady: false,
            isPlaying: false,
            error: 'Retrying to load audio...',
            retryCount: (prevData.retryCount || 0) + 1,
          },
        };
      }
      toast.error('Failed to play audio message');
      return {
        ...prev,
        [msgId]: {
          ...prevData,
          isReady: false,
          isPlaying: false,
          error: `Failed to load audio: ${errorMsg}`,
        },
      };
    });
  };

  const handleAudioEnded = (msgId) => {
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
    }
  };

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
      const cacheBusterUrl = `${url}?t=${Date.now()}`;
      audio.src = cacheBusterUrl;
      audio.type = url.endsWith('.mp3') ? 'audio/mpeg' : 'audio/webm';
      audio.load();
      audio.addEventListener('loadedmetadata', () => {
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
        setAudioProgress((prev) => {
          const prevData = prev[msgId] || {};
          if (!prevData.retryCount || prevData.retryCount < 2) {
            setTimeout(() => {
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

  const handleDragStart = (msgId, e) => {
    e.preventDefault();
    const audio = audioRefs.current[msgId];
    if (!audio || !audioProgress[msgId]?.isReady) {
      return;
    }
    setIsDragging(msgId);
    handleSeek(msgId, e);
  };

  const handleDragMove = (e) => {
    if (isDragging) {
      handleSeek(isDragging, e);
    }
  };

  const handleDragEnd = () => {
    setIsDragging(null);
  };

  return (
    <div className="private-chat secret-chat">
      <div className="private-chat-name">
        <span className="secret-chat-indicator">🔒</span>
        {displayChatName}
      </div>
      <div className="private-message-list" ref={messageListRef}>
        {messages.map((msg, index) => (
          <div
            key={msg.id}
            className={`private-message ${
              msg.sender.id === userId ? 'private-current-user' : 'private-other-user'
            } ${isFirstMessage(msg, index) ? 'first-message' : ''} ${
              msg.messageType === 'audio' ? 'private-audio-message' : ''
            }`}
            onContextMenu={(e) => handleRightClick(e, msg)}
          >
            <div className="private-message-wrapper">
              {shouldShowAvatar(msg, index) && (
                <div className="private-avatar-container">
                  {msg.sender.avatar ? (
                    <div className="private-avatar-wrapper">
                      <img
                        src={`https://unlimitedpossibilities12.org${msg.sender.avatar}`}
                        alt={`${msg.sender.username} avatar`}
                        className="private-message-avatar"
                        onClick={() => handleAvatarClick(msg.sender.id)}
                        style={{ cursor: 'pointer' }}
                      />
                      {usersStatus[msg.sender.id]?.online && (
                        <span className="status-indicator-online-privatechat"></span>
                      )}
                    </div>
                  ) : (
                    <div className="private-avatar-wrapper">
                      <div
                        className="private-message-avatar"
                        onClick={() => handleAvatarClick(msg.sender.id)}
                        style={{ cursor: 'pointer' }}
                      >
                        {msg.sender.username.charAt(0).toUpperCase()}
                      </div>
                      {usersStatus[msg.sender.id]?.online && (
                        <span className="status-indicator-online-privatechat"></span>
                      )}
                    </div>
                  )}
                </div>
              )}
              <div
                className={`private-message-content-container ${
                  shouldShowAvatar(msg, index) ? '' : 'no-avatar'
                }`}
              >
                <div className="private-message-body">
                  <div className="private-message-content-wrapper">
                    {msg.messageType === 'audio' && msg.fileUrl ? (
                      <div className="private-audio-message-container">
                        <div className="private-audio-player-wrapper">
                          <audio
                            ref={(el) => {
                              if (el && audioRefs.current[msg.id] !== el) {
                                audioRefs.current[msg.id] = el;
                              }
                            }}
                            onTimeUpdate={() => handleTimeUpdate(msg.id, audioRefs.current[msg.id])}
                            onLoadedMetadata={() => handleLoadedMetadata(msg.id, audioRefs.current[msg.id])}
                            onCanPlay={() => handleCanPlay(msg.id)}
                            onError={(e) => handleAudioError(msg.id, e)}
                            onEnded={() => handleAudioEnded(msg.id)}
                            className="private-audio-player"
                            preload="auto"
                          >
                            <source src={msg.fileUrl} type={msg.fileUrl.endsWith('.mp3') ? 'audio/mpeg' : 'audio/webm'} />
                            Your browser does not support the audio element.
                          </audio>
                          {audioProgress[msg.id]?.error ? (
                            <span className="private-audio-error">{audioProgress[msg.id].error}</span>
                          ) : (
                            <div className="private-audio-controls">
                              <button
                                className="private-audio-play-pause"
                                onClick={() => {
                                  const audio = audioRefs.current[msg.id];
                                  if (!audio || !audioProgress[msg.id]?.isReady) {
                                    return;
                                  }
                                  if (audio.paused) {
                                    audio.play().catch((e) => handleAudioError(msg.id, e));
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
                                className="private-audio-progress-bar"
                                onClick={(e) => handleSeek(msg.id, e)}
                                onMouseDown={(e) => handleDragStart(msg.id, e)}
                                style={{ cursor: audioProgress[msg.id]?.duration === 0 ? 'not-allowed' : 'pointer' }}
                              >
                                <div
                                  className="private-audio-progress"
                                  style={{
                                    width: `${
                                      audioProgress[msg.id]?.duration && audioProgress[msg.id]?.isReady && audioProgress[msg.id]?.duration !== 0
                                        ? (audioProgress[msg.id].currentTime / audioProgress[msg.id].duration) * 100
                                        : 0
                                    }%`,
                                  }}
                                >
                                  <div className="private-audio-progress-thumb"></div>
                                </div>
                              </div>
                              <span className="private-audio-time">
                                {formatAudioTime(audioProgress[msg.id]?.currentTime || 0)} /{' '}
                                {formatAudioTime(audioProgress[msg.id]?.duration || 0)}
                              </span>
                            </div>
                          )}
                        </div>
                      </div>
                    ) : (
                      <>
                        <span className="private-message-content">
                          {msg.content}
                        </span>
                        {msg.fileUrl && (
                          <a href={msg.fileUrl} download className="private-file-link">
                            📥 Download file
                          </a>
                        )}
                      </>
                    )}
                    <div className="private-message-status">
                      {msg.sender.id === userId && (
                        <>
                          {msg.delivered && <span className="private-delivery-indicator">✔️</span>}
                          {msg.read && <span className="private-read-indicator">✔️</span>}
                        </>
                      )}
                    </div>
                    <div className="private-message-timestamp">{formatTimestamp(msg.timestamp)}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        ))}
        <div ref={messagesEndRef} />
      </div>
      <div className="private-input-container">
        <div className="private-textarea-wrapper">
          <div className="private-textarea-inner">
            <span
              onClick={() => fileInputRef.current.click()}
              className="private-attach-icon"
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
              className="private-input-message"
              rows={rows}
            />
            <input
              type="file"
              onChange={(e) => setSelectedFile(e.target.files[0])}
              className="private-file-input"
              ref={fileInputRef}
              id="private-file-input"
            />
            {content.length === 0 && micIconState !== 'sending' && !selectedFile && (
              <div className="private-mic-icon-container">
                <span
                  onClick={isRecording ? stopRecording : startRecording}
                  className={`private-mic-icon ${micIconState} ${!userId ? 'disabled' : ''}`}
                  role="button"
                  aria-label={micIconState === 'mic' ? 'Start Recording' : micIconState === 'recording' ? 'Stop Recording' : 'Sending Audio'}
                >
                  {micIconState === 'mic' && (
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z" fill="#757575"/>
                      <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z" fill="#757575"/>
                    </svg>
                  )}
                  {micIconState === 'recording' && (
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <circle cx="12" cy="12" r="8" fill="#FF0000"/>
                    </svg>
                  )}
                  {micIconState === 'sending' && (
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" fill="#0288D1"/>
                    </svg>
                  )}
                </span>
              </div>
            )}
            {(content.length > 0 || selectedFile) && (
              <div className="private-send-icon-container">
                <span
                  onClick={sendMessage}
                  className="private-send-icon"
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
            <div className="private-attached-file">
              <span className="private-attached-file-name">{selectedFile.name}</span>
              <button
                className="private-remove-file-button"
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
          className="private-context-menu"
          style={{ top: contextMenu.y, left: contextMenu.x }}
          onMouseLeave={() => setContextMenu({ visible: false, message: null })}
        >
          {contextMenu.message?.messageType === 'text' && (
            <button onClick={() => handleMenuAction('edit')}>Edit</button>
          )}
          <button onClick={() => handleMenuAction('delete')}>Delete</button>
        </div>
      )}
      {incomingCall && (
        <IncomingCallNotification
          callerId={incomingCall.callerId}
          callerName={incomingCall.callerName || 'Unknown'}
          avatar={incomingCall.avatar}
          callType={incomingCall.type}
          onAccept={() => acceptCall(navigate)}
          onReject={rejectCall}
        />
      )}
    </div>
  );
};

export default SecretPrivateChat;