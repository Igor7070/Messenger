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

const saveOwnMessageContent = (chatId, userId, clientMessageId, content) => {
  try {
    const key = `ownMessageContent_${chatId}_${userId}`;
    const stored = localStorage.getItem(key);
    const messages = stored ? JSON.parse(stored) : {};
    messages[clientMessageId] = content;
    localStorage.setItem(key, JSON.stringify(messages));
    console.log(`Saved own message content to localStorage: clientMessageId=${clientMessageId}, content=${content}`);
  } catch (error) {
    console.error('Failed to save own message content to localStorage:', error);
  }
};

const getOwnMessageContent = (chatId, userId, clientMessageId) => {
  try {
    const key = `ownMessageContent_${chatId}_${userId}`;
    const stored = localStorage.getItem(key);
    if (stored) {
      const messages = JSON.parse(stored);
      const content = messages[clientMessageId];
      console.log(`Retrieved own message content from localStorage: clientMessageId=${clientMessageId}, content=${content || 'not found'}`);
      return content || null;
    }
    return null;
  } catch (error) {
    console.error('Failed to get own message content from localStorage:', error);
    return null;
  }
};

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

const getPublicKey = async (userId, publicKeyId = null) => {
  console.log(`Fetching public key for userId: ${userId}, publicKeyId: ${publicKeyId}`);
  try {
    const url = publicKeyId
      ? `https://unlimitedpossibilities12.org/api/public_keys/history/${publicKeyId}` // [ADD] Новый эндпоинт
      : `https://unlimitedpossibilities12.org/api/public_keys/${userId}`;
    const response = await fetch(url);
    if (!response.ok) {
      if (publicKeyId) {
        console.warn(`History fetch failed for publicKeyId=${publicKeyId}, fallback to current for userId=${userId}`);
        const fallbackUrl = `https://unlimitedpossibilities12.org/api/public_keys/${userId}`;  // Current key
        const fallbackResponse = await fetch(fallbackUrl);
        if (!fallbackResponse.ok) throw new Error(`Fallback failed: ${fallbackResponse.status}`);
        const fallbackData = await fallbackResponse.json();
        const fallbackKey = fallbackData.publicKey;
        console.log(`Fallback to current key for userId=${userId}: ${fallbackKey}`);
        // Кэш fallback as history
        localStorage.setItem(`history_key_${publicKeyId}`, fallbackKey);
        return fallbackKey;
      }
      throw new Error(`Failed to fetch public key: ${response.status}`);
    }
    const publicKeyData = await response.json();
    const key = publicKeyData.publicKey;
    if (publicKeyId) localStorage.setItem(`history_key_${publicKeyId}`, key);  // Кэш history
    console.log(`Fetched public key for userId: ${userId}, publicKeyId: ${publicKeyId}, value: ${publicKeyData.publicKey}`);
    try {
      const publicKeyBytes = sodium.from_base64(publicKeyData.publicKey);
      console.log(`Public key bytes length for userId: ${userId}: ${publicKeyBytes.length}, hex: ${sodium.to_hex(publicKeyBytes)}`);
    } catch (e) {
      console.error(`Invalid fetched public key for userId: ${userId}, publicKeyId: ${publicKeyId}, error: ${e.message}`);
    }
    return publicKeyData.publicKey;
  } catch (error) {
    console.error(`Error fetching public key for userId: ${userId}, publicKeyId: ${publicKeyId}, error: ${error.message}`);
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
  const contextMenuRef = useRef(null);
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
  const [sentMessagesCache, setSentMessagesCache] = useState({}); // Кэш отправленных сообщений: { clientMessageId: content }
  const [processedMessages, setProcessedMessages] = useState(new Set());
  const messagesEndRef = useRef(null);
  const messageListRef = useRef(null);
  const fileInputRef = useRef(null);
  const mediaRecorderRef = useRef(null);
  const audioRefs = useRef({});
  const textAreaRef = useRef(null);
  const ffmpeg = useRef(new FFmpeg());
  const audioBlobRef = useRef(null);  // ← Добавление в начало компонента
  const { chatId: chatIdParam } = useParams();
  const navigate = useNavigate();
  const { userInteracted, handleInteraction } = useUserInteraction();
  const { usersStatus } = useUserStatusContext();
  const { incomingCall, acceptCall, rejectCall, setCurrentUserId: setContextUserId } = useCallWebSocket();
  const { setCurrentUserId: setNotificationUserId, setCurrentChatId } = usePrivateMessageNotification();

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
        // Добавляем собственный публичный ключ в publicKeys
        setPublicKeys((prev) => ({
          ...prev,
          [userId]: parsedKeyPair.publicKey,
        }));
        // Force-sync key всегда при reload/update (уведомление партнёра)
        const currentPublicKey = parsedKeyPair.publicKey;
        try {
          console.log(`Syncing public key for userId: ${userId}`);
          const syncResponse = await fetch(`https://unlimitedpossibilities12.org/api/public_keys`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId, publicKey: currentPublicKey }),
          });
          if (!syncResponse.ok) throw new Error(`Sync failed: ${syncResponse.status}`);
          console.log(`Key synced for userId: ${userId}`);

          // WS-broadcast для партнёра (он получит в /topic/publicKey/{recipientId})
          if (client && client.connected) {
            client.publish({
              destination: `/app/publicKey/${userId}`,
              body: JSON.stringify({ userId, publicKey: currentPublicKey }),
            });
            console.log('WS broadcast sent to partner');
          }

          toast.info('Key updated and synced with partner');  // Уведомление юзеру
        } catch (syncError) {
          console.error('Sync error:', syncError);
          toast.error('Key sync failed — retry chat');
        }
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
        // Добавляем собственный публичный ключ в publicKeys
        setPublicKeys((prev) => ({
          ...prev,
          [userId]: publicKeyBase64,
        }));
        // Force-sync key всегда при reload/update (уведомление партнёра)
        const currentPublicKey = publicKeyBase64;
        try {
          console.log(`Syncing public key for userId: ${userId}`);
          const syncResponse = await fetch(`https://unlimitedpossibilities12.org/api/public_keys`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId, publicKey: currentPublicKey }),
          });
          if (!syncResponse.ok) throw new Error(`Sync failed: ${syncResponse.status}`);
          console.log(`Key synced for userId: ${userId}`);

          // WS-broadcast для партнёра (он получит в /topic/publicKey/{recipientId})
          if (client && client.connected) {
            client.publish({
              destination: `/app/publicKey/${userId}`,
              body: JSON.stringify({ userId, publicKey: currentPublicKey }),
            });
            console.log('WS broadcast sent to partner');
          }

          toast.info('Key updated and synced with partner');  // Уведомление юзеру
        } catch (syncError) {
          console.error('Sync error:', syncError);
          toast.error('Key sync failed — retry chat');
        }
      }
    };
    initializeSodium();
  }, [userId]);

  // Загрузка данных чата и сообщений
  useEffect(() => {
    const fetchChatData = async () => {
      if (!chatIdParam || !username || !keyPair) return;

      try {
        const chatData = await getChatById(chatIdParam);
        if (!chatData.isSecret) { toast.error('This is not a secret chat'); navigate('/'); return; }

        const names = chatData.name.replace('_secret', '').split('_');
        const interlocutorName = names.find((name) => name !== username);
        const normalizedChatName = generateChatName(username, interlocutorName, true);
        setChatName(normalizedChatName);
        setDisplayChatName(`Secret Chat with ${interlocutorName}`);
        setChatId(chatIdParam);
        setCurrentChatId(chatIdParam);

        const recipientResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${interlocutorName}`);
        const recipient = await recipientResponse.json();
        const recipientId = recipient.id;

        // Загружаем ключи
        const recipientPublicKey = await getPublicKey(recipientId);
        setPublicKeys(prev => ({ ...prev, [recipientId]: recipientPublicKey }));

        const initialMessages = await getMessages(chatIdParam);
        const senderIds = [...new Set(initialMessages.map(m => m.sender.id))];
        const keyPromises = senderIds
          .filter(id => id !== userId && !publicKeys[id])
          .map(id => getPublicKey(id).then(pk => ({ senderId: id, publicKey: pk })));
        const newKeys = await Promise.all(keyPromises);
        setPublicKeys(prev => ({ ...prev, ...Object.fromEntries(newKeys.map(k => [k.senderId, k.publicKey])) }));

        // Расшифровка сообщений
        const decryptedMessages = await Promise.all(
          initialMessages.map(async (msg) => {
            if (msg.messageType === 'text' && msg.encryptedContent && msg.nonce) {
              if (msg.sender.id === userId) {
                const localContent = getOwnMessageContent(chatIdParam, userId, msg.clientMessageId);
                return { ...msg, content: localContent || 'Content unavailable', delivered: msg.delivered_status, read: msg.read_status };
              }
              try {
                const publicKey = await getPublicKey(msg.sender.id, msg.publicKeyId);
                const decrypted = await decryptMessage(msg.encryptedContent, msg.nonce, msg.sender.id, msg.timestamp, publicKey);
                return { ...msg, content: decrypted, delivered: msg.delivered_status, read: msg.read_status };
              } catch (e) {
                return { ...msg, content: 'Decryption failed', delivered: msg.delivered_status, read: msg.read_status };
              }
            }

            if (msg.messageType === 'file') {
              if (msg.sender.id === userId) {
                const stored = localStorage.getItem(`fileContent_${chatIdParam}_${userId}_${msg.clientMessageId}`);
                const fileData = stored ? JSON.parse(stored) : {};
                return { ...msg, ...fileData, delivered: msg.delivered_status, read: msg.read_status };
              }

              // === ВХОДЯЩИЕ ФАЙЛЫ — без кэша, только имя и тип ===
              try {
                let fileName = 'Unknown file';
                let contentType = 'application/octet-stream';

                if (msg.encryptedContent && msg.nonce) {
                  let publicKey = publicKeys[msg.sender.id];
                  if (!publicKey || msg.publicKeyId) {
                    publicKey = await getPublicKey(msg.sender.id, msg.publicKeyId);
                    setPublicKeys(p => ({ ...p, [msg.sender.id]: publicKey }));
                  }

                  const decryptedMeta = await decryptMessage(
                    msg.encryptedContent,
                    msg.nonce,
                    msg.sender.id,
                    msg.timestamp,
                    publicKey
                  );
                  const meta = JSON.parse(decryptedMeta);
                  fileName = meta.fileName;
                  contentType = meta.contentType || meta.mimeType || 'application/octet-stream';
                }

                return {
                  ...msg,
                  fileName,
                  contentType,
                  delivered: msg.delivered_status,
                  read: msg.read_status
                };
              } catch (e) {
                console.error('File metadata decryption failed on load:', e);
                return {
                  ...msg,
                  fileName: 'Decryption failed',
                  delivered: msg.delivered_status,
                  read: msg.read_status
                };
              }
            }

            return { ...msg, delivered: msg.delivered_status, read: msg.read_status };
          })
        );

        setMessages(decryptedMessages);
        setTimeout(scrollToBottom, 0);
      } catch (error) {
        console.error('Error fetching chat data:', error);
        toast.error('Failed to load chat');
        navigate('/');
      }
    };

    fetchChatData();

    const storedUserId = localStorage.getItem('userId');
    if (storedUserId) {
      const uid = parseInt(storedUserId, 10);
      setContextUserId(uid);
      setNotificationUserId(uid);
    }

    return () => setCurrentChatId(null);
  }, [chatIdParam, username, keyPair, navigate, setContextUserId, setNotificationUserId, setCurrentChatId]);

  // WebSocket-подключение
  useEffect(() => {
    if (!chatId || !userId) {
      console.warn('WebSocket setup skipped: chatId or userId missing', { chatId, userId });
      return;
    }

    let reconnectTimeout;
    const stompClientInstance = new Client({
      webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
      connectTimeout: 30000,
      reconnectDelay: 0,
      onConnect: async () => {
        console.log('Connected to WebSocket for SecretPrivateChat at:', new Date().toISOString());
        setClient(stompClientInstance);

        stompClientInstance.subscribe(`/topic/chat/${chatId}/messages`, async (message) => {
          const newMessage = JSON.parse(message.body);
          console.log('NewMessage:', newMessage);

          // === ТЕКСТОВЫЕ СООБЩЕНИЯ ===
          if (newMessage.messageType === 'text' && newMessage.encryptedContent && newMessage.nonce) {
            if (newMessage.sender.id !== userId) {
              try {
                const cleanedEncryptedContent = newMessage.encryptedContent.replace(/\n/g, '');
                const cleanedNonce = newMessage.nonce.replace(/\n/g, '');

                let publicKey = publicKeys[newMessage.sender.id];
                if (!publicKey || newMessage.publicKeyId) {
                  console.log(`Public key for senderId: ${newMessage.sender.id} not found or publicKeyId present, fetching...`);
                  publicKey = await getPublicKey(newMessage.sender.id, newMessage.publicKeyId);
                  setPublicKeys((prev) => ({
                    ...prev,
                    [newMessage.sender.id]: publicKey,
                  }));
                }

                const decryptedContent = await decryptMessage(
                  cleanedEncryptedContent,
                  cleanedNonce,
                  newMessage.sender.id,
                  newMessage.timestamp,
                  publicKey
                );
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
                content: sentMessagesCache[newMessage.clientMessageId] || 
                        getOwnMessageContent(chatId, userId, newMessage.clientMessageId) || 
                        'Content unavailable',
                delivered: newMessage.delivered_status,
                read: newMessage.read_status,
              });
            }

          // === ФАЙЛОВЫЕ СООБЩЕНИЯ ===
          } else if (newMessage.messageType === 'file') {
            if (newMessage.sender.id === userId) {
              // === СВОИ ФАЙЛЫ — из localStorage (как было) ===
              const fileContentKey = `fileContent_${chatId}_${userId}_${newMessage.clientMessageId}`;
              const storedFileData = localStorage.getItem(fileContentKey);
              let fileData = {};
              if (storedFileData) {
                fileData = JSON.parse(storedFileData);
                console.log(`Retrieved file data for own message: clientMessageId=${newMessage.clientMessageId}, fileName=${fileData.fileName}`);
              }

              updateMessages({
                ...newMessage,
                fileUrl: fileData.fileUrl || 'File unavailable',
                fileName: fileData.fileName || 'File unavailable',
                contentType: fileData.contentType || null,
                delivered: newMessage.delivered_status ?? false,
                read: newMessage.read_status ?? false,
              });
            } else {
              // === ВХОДЯЩИЕ ФАЙЛЫ ===
              try {
                let fileName = 'Unknown file';
                let contentType = 'application/octet-stream';
                let fileUrl = newMessage.fileUrl || newMessage.file_url || null; // ← Сначала из сообщения

                if (newMessage.encryptedContent && newMessage.nonce) {
                  let publicKey = publicKeys[newMessage.sender.id];
                  if (!publicKey || newMessage.publicKeyId) {
                    console.log(`Public key for senderId: ${newMessage.sender.id} not found or publicKeyId present, fetching...`);
                    publicKey = await getPublicKey(newMessage.sender.id, newMessage.publicKeyId);
                    setPublicKeys((prev) => ({
                      ...prev,
                      [newMessage.sender.id]: publicKey,
                    }));
                  }

                  const decryptedMetadata = await decryptMessage(
                    newMessage.encryptedContent,
                    newMessage.nonce,
                    newMessage.sender.id,
                    newMessage.timestamp,
                    publicKey
                  );
                  const metadata = JSON.parse(decryptedMetadata);
                  fileName = metadata.fileName;
                  contentType = metadata.mimeType || metadata.contentType || 'application/octet-stream';
                  console.log(`Decrypted file metadata: fileName=${fileName}, contentType=${contentType}`);
                }

                // ← ДОБАВЬ: если fileUrl нет — подгружаем из API
                if (!fileUrl && newMessage.id) {
                  try {
                    const meta = await fetchFileMetadata(newMessage.id);
                    fileUrl = meta.fileUrl;
                    console.log(`Fetched fileUrl from API for message ${newMessage.id}: ${fileUrl}`);
                  } catch (e) {
                    console.error('Failed to fetch fileUrl:', e);
                  }
                }

                updateMessages({
                  ...newMessage,
                  fileName,
                  contentType,
                  fileUrl, // ← Теперь fileUrl из API (если не было)
                  delivered: newMessage.delivered_status ?? false,
                  read: newMessage.read_status ?? false,
                });
              } catch (error) {
                console.error(`Failed to decrypt file metadata ID=${newMessage.id}:`, error);
                updateMessages({
                  ...newMessage,
                  fileName: 'Decryption failed',
                  contentType: null,
                  fileUrl: null,
                  delivered: newMessage.delivered_status ?? false,
                  read: newMessage.read_status ?? false,
                });
                toast.error('Failed to decrypt file name');
              }
            }

          // === ДРУГИЕ ТИПЫ СООБЩЕНИЙ ===
          } else {
            updateMessages({
              ...newMessage,
              delivered: newMessage.delivered_status,
              read: newMessage.read_status,
            });
          }
        });

        // === ПОДПИСКИ НА СТАТУСЫ И КЛЮЧИ ===
        stompClientInstance.subscribe(`/topic/messageStatus/${chatId}`, (statusMessage) => {
          const statusUpdate = JSON.parse(statusMessage.body);
          console.log('Received status update:', statusUpdate);
          setMessages((prevMessages) =>
            prevMessages.map((msg) =>
              msg.id === statusUpdate.messageId
                ? {
                    ...msg,
                    delivered: statusUpdate.delivered_status ?? msg.delivered,
                    read: statusUpdate.read_status ?? msg.read,
                  }
                : msg
            )
          );
        });

        if (chatName) {
          const recipientName = chatName.replace('_secret', '').split('_').find((name) => name !== username);
          if (recipientName) {
            try {
              const response = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`);
              if (!response.ok) throw new Error(`Failed to fetch recipient: ${response.status}`);
              const recipient = await response.json();
              const recipientId = recipient.id;

              stompClientInstance.subscribe(`/topic/publicKey/${recipientId}`, (message) => {
                try {
                  const updatedPublicKey = JSON.parse(message.body);
                  console.log(`Received updated public key for recipientId=${recipientId}: ${updatedPublicKey.publicKey}`);
                  setPublicKeys((prev) => ({
                    ...prev,
                    [recipientId]: updatedPublicKey.publicKey,
                  }));
                  localStorage.setItem(`public_key_${recipientId}`, updatedPublicKey.publicKey);
                  toast.info(`Public key updated for user ${recipientName}`);
                } catch (error) {
                  console.error(`Error processing public key update for recipientId=${recipientId}:`, error);
                  toast.error('Failed to process public key update');
                }
              });
              console.log(`Subscribed to public key updates for recipientId=${recipientId}`);
            } catch (error) {
              console.error('Failed to fetch recipientId for public key subscription:', error);
              toast.error('Failed to subscribe to public key updates');
            }
          }
        }
      },

      onWebSocketError: (error) => {
        console.error('WebSocket error in SecretPrivateChat at:', new Date().toISOString(), error);
        toast.error('WebSocket connection error');
      },
      onStompError: (frame) => {
        console.error('STOMP error in SecretPrivateChat:', frame);
        toast.error('STOMP protocol error');
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected in SecretPrivateChat');
        setClient(null);
        reconnectTimeout = setTimeout(() => {
          console.log('Reconnecting WebSocket for SecretPrivateChat');
          stompClientInstance.activate();
        }, 2000);
      },
    });

    stompClientInstance.activate();

    return () => {
      console.log('Deactivating WebSocket in SecretPrivateChat');
      if (stompClientInstance.active) {
        stompClientInstance.deactivate();
      }
      if (reconnectTimeout) {
        clearTimeout(reconnectTimeout);
      }
    };
  }, [chatId, userId, publicKeys, chatName, username, keyPair]);

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

  useEffect(() => {
    const handleClickOutside = (e) => {
      if (contextMenuRef.current && !contextMenuRef.current.contains(e.target)) {
        setContextMenu({ visible: false });
      }
    };

    if (contextMenu.visible) {
      document.addEventListener('mousedown', handleClickOutside);
      document.addEventListener('touchstart', handleClickOutside);
    }

    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('touchstart', handleClickOutside);
    };
  }, [contextMenu.visible]);

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
    setMessages(prev => {
      // 1. Ищем по clientMessageId (своё временное)
      if (newMessage.sender.id === userId && newMessage.clientMessageId) {
        const tempIdx = prev.findIndex(m => m.clientMessageId === newMessage.clientMessageId);
        if (tempIdx !== -1) {
          const oldUrl = prev[tempIdx].fileUrl;
          const updated = [...prev];
          updated[tempIdx] = {
            ...newMessage,
            fileUrl: newMessage.fileUrl || prev[tempIdx].fileUrl,
            delivered: false,
            read: false
          };
          // Отзываем blob ТОЛЬКО если есть server URL
          if (oldUrl?.startsWith('blob:') && newMessage.fileUrl) {
            setTimeout(() => URL.revokeObjectURL(oldUrl), 100);
          }
          return updated;
        }
      }

      // 2. Ищем по id
      const existingIdx = prev.findIndex(m => m.id === newMessage.id);
      if (existingIdx !== -1) {
        const oldUrl = prev[existingIdx].fileUrl;
        const updated = [...prev];
        updated[existingIdx] = { ...updated[existingIdx], ...newMessage };
        if (oldUrl?.startsWith('blob:') && newMessage.fileUrl) {
          setTimeout(() => URL.revokeObjectURL(oldUrl), 100);
        }
        return updated;
      }

      // 3. Новое сообщение
      return [...prev, { ...newMessage, delivered: false, read: false }];
    });
  };

  /*const fetchFileMetadata = async (messageId) => {
    try {
      const response = await fetch(`https://unlimitedpossibilities12.org/api/messages/file_metadata/${messageId}`);
      if (!response.ok) {
        throw new Error(`Failed to fetch file metadata: ${response.status}`);
      }
      const data = await response.json();
      console.log(`Fetched FileMetadata for messageId=${messageId}:`, data);
      return data; // { fileUrl, nonce, publicKeyId }
    } catch (error) {
      console.error(`Error fetching file metadata for messageId=${messageId}:`, error);
      throw error;
    }
  };*/

  const fetchFileMetadata = async (messageId, retries = 5, delay = 2000) => {
    for (let attempt = 1; attempt <= retries; attempt++) {
      try {
        console.log(`Fetching file metadata for messageId=${messageId}, attempt=${attempt}`);
        const response = await fetch(`https://unlimitedpossibilities12.org/api/messages/file_metadata/${messageId}`, {
          headers: { 'Content-Type': 'application/json' },
        });
        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(`Failed to fetch file metadata: ${response.status} - ${errorText}`);
        }
        const data = await response.json();
        console.log(`Fetched FileMetadata for messageId=${messageId}:`, data);
        if (!data.fileUrl || !data.nonce) {
          throw new Error('Invalid file metadata: missing fileUrl or nonce');
        }
        return data;
      } catch (error) {
        console.error(`Error fetching file metadata for messageId=${messageId}, attempt=${attempt}:`, error);
        if (attempt === retries) {
          toast.error(`Failed to load file metadata after ${retries} attempts`);
          throw error;
        }
        await new Promise((resolve) => setTimeout(resolve, delay * attempt));
      }
    }
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

  const decryptMessage = async (encryptedContent, nonce, senderId, timestamp, publicKey, originalPublicKeyId = null) => { // [CHANGE] Добавлен параметр publicKey, originalPublicKeyId = null
    console.log(
      `decryptMessage called: senderId=${senderId}, timestamp=${timestamp}`
    );
    await sodium.ready;

    if (!publicKey || !keyPair?.privateKey) {
      throw new Error('Encryption keys not available');
    }

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

    console.log(
      `Validating base64: encryptedContent=${validateBase64(
        encryptedContent,
        'encryptedContent'
      )}, nonce=${validateBase64(nonce, 'nonce')}, publicKey=${validateBase64(
        publicKey,
        'publicKey'
      )}, privateKey=${validateBase64(keyPair.privateKey, 'privateKey')}`
    );

    if (
      !validateBase64(encryptedContent, 'encryptedContent') ||
      !validateBase64(nonce, 'nonce') ||
      !validateBase64(publicKey, 'publicKey') ||
      !validateBase64(keyPair.privateKey, 'privateKey')
    ) {
      console.error(
        `Invalid base64 format: encryptedContent=${encryptedContent}, nonce=${nonce}, publicKey=${publicKey}, privateKey=${keyPair.privateKey}`
      );
      throw new Error('Invalid base64 format');
    }

    // [FIX] Вынести vars из try, чтобы они были доступны в catch
    const encryptedBytes = sodium.from_base64(encryptedContent);
    const nonceBytes = sodium.from_base64(nonce);
    const publicKeyBytes = sodium.from_base64(publicKey);
    const privateKeyBytes = sodium.from_base64(keyPair.privateKey);

    console.log(
      `Decryption input: encryptedBytesLength=${encryptedBytes.length}, nonceBytesLength=${nonceBytes.length}, publicKeyBytesLength=${publicKeyBytes.length}, privateKeyBytesLength=${privateKeyBytes.length}`
    );
    console.log(
      `Decryption input hex: encryptedBytes=${sodium.to_hex(
        encryptedBytes
      )}, nonceBytes=${sodium.to_hex(nonceBytes)}, publicKeyBytes=${sodium.to_hex(
        publicKeyBytes
      )}`
    );

    try {
      const decryptedBytes = sodium.crypto_box_open_easy(
        encryptedBytes,
        nonceBytes,
        publicKeyBytes,
        privateKeyBytes
      );
      const decryptedMessage = sodium.to_string(decryptedBytes);
      console.log(`Decryption successful: decryptedMessage=${decryptedMessage}`);
      return decryptedMessage;
    } catch (error) {
      console.error(`Decryption error for senderId: ${senderId}, encryptedContent: ${encryptedContent}, nonce: ${nonce}, error: ${error.message}`);
      // [FIX] Retry with current key if history fail (vars теперь доступны)
      if (originalPublicKeyId && error.message.includes('incorrect key pair')) {
        console.log(`Retry with current key for senderId=${senderId}`);
        const currentKey = await getPublicKey(senderId);  // No ID = current
        const currentBytes = sodium.from_base64(currentKey);
        const decryptedBytes = sodium.crypto_box_open_easy(encryptedBytes, nonceBytes, currentBytes, privateKeyBytes);
        const decryptedMessage = sodium.to_string(decryptedBytes);
        console.log(`Retry success: ${decryptedMessage}`);
        return decryptedMessage;
      }
      throw new Error(`Decryption failed: ${error.message}`);
    }
  };

  const encryptFile = async (file, recipientId, userId, keyPair, publicKeys) => {
    await sodium.ready;

    if (!keyPair || !publicKeys[recipientId]) {
      throw new Error('Encryption keys not available for file');
    }

    const privateKey = sodium.from_base64(keyPair.privateKey);
    const publicKey = sodium.from_base64(publicKeys[recipientId]);

    // Читаем файл как массив байтов
    const fileBytes = new Uint8Array(await file.arrayBuffer());
    const nonce = sodium.randombytes_buf(sodium.crypto_box_NONCEBYTES);
    const encryptedFile = sodium.crypto_box_easy(fileBytes, nonce, publicKey, privateKey);

    // Шифруем метаданные (имя файла и MIME-тип)
    const metadata = {
      fileName: file.name,
      contentType: file.type,
    };
    const metadataBytes = sodium.from_string(JSON.stringify(metadata));
    const encryptedContent = sodium.crypto_box_easy(metadataBytes, nonce, publicKey, privateKey);

    return {
      encryptedFile: sodium.to_base64(encryptedFile),
      nonce: sodium.to_base64(nonce),
      encryptedContent: sodium.to_base64(encryptedContent),
    };
  };

  const decryptFileContent = async (encryptedFileBytes, nonce, senderId, publicKeyId, publicKeys, keyPair, recipientId) => {
    await sodium.ready;

    let publicKey;

    if (senderId === userId) {
      // === СВОЙ ФАЙЛ — ключ получателя ===
      publicKey = publicKeys[recipientId];
      if (!publicKey && publicKeyId) {
        console.log(`[OWN FILE] Fetching public key for recipientId=${recipientId}, publicKeyId=${publicKeyId}`);
        publicKey = await getPublicKey(recipientId, publicKeyId);
        setPublicKeys((prev) => ({ ...prev, [recipientId]: publicKey }));
      }
    } else {
      // === ВХОДЯЩИЙ ФАЙЛ — ключ отправителя ===
      publicKey = publicKeys[senderId];
      if (!publicKey) {
        console.log(`[INCOMING FILE] Public key missing for senderId=${senderId}, fetching...`);
        publicKey = await getPublicKey(senderId, publicKeyId);
        setPublicKeys((prev) => ({ ...prev, [senderId]: publicKey }));
      }
    }

    if (!publicKey || !keyPair?.privateKey) {
      throw new Error('Decryption keys not available for file');
    }

    try {
      const publicKeyBytes = sodium.from_base64(publicKey);
      const privateKeyBytes = sodium.from_base64(keyPair.privateKey);
      const nonceBytes = sodium.from_base64(nonce);

      const decryptedBytes = sodium.crypto_box_open_easy(
        encryptedFileBytes,
        nonceBytes,
        publicKeyBytes,
        privateKeyBytes
      );

      console.log(`File decryption successful for senderId=${senderId}`);
      return decryptedBytes;
    } catch (error) {
      console.error(`File decryption failed:`, error);
      throw new Error(`File decryption failed: ${error.message}`);
    }
  };

  const uploadFile = async (file, nonce, messageId) => {
    console.log('Uploading file with messageId:', messageId, 'Type:', typeof messageId);
    const formData = new FormData();
    formData.append('file', new Blob([sodium.from_base64(file)], { type: 'application/octet-stream' }));
    formData.append('nonce', nonce);
    formData.append('messageId', messageId);

    const response = await fetch('https://unlimitedpossibilities12.org/api/messages/upload_encrypted_file', {
      method: 'POST',
      body: formData,
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`File upload failed: ${response.status} - ${errorText}`);
    }
    const data = await response.json();
    console.log('File upload response:', data);
    return data.fileUrl;
  };

  const sendMessage = async () => {
    if ((content || selectedFile || audioBlob) && userId && keyPair) {
      try {
        // [ADD] Получаем ID получателя
        const recipientName = chatName.split('_').find((name) => name !== username);
        const userResponse = await fetch(
          `https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`
        );
        if (!userResponse.ok) throw new Error('Failed to fetch recipient');
        const recipient = await userResponse.json();
        const recipientId = recipient.id;

        // [ADD] Генерируем ID временного сообщения
        const clientMessageId = `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
        const timestamp = new Date().toISOString();

        // [ADD] Базовые данные сообщения
        let messageData = {
          sender: { id: userId, username },
          chat: { id: chatId, name: generateChatName(username, recipientName, true), isSecret: true },
          delivered: false,
          read: false,
          timestamp,
        };

        let savedMessage = null;
        let serverFileUrl = null;

        // === ТЕКСТОВОЕ СООБЩЕНИЕ ===
        if (content) {
          setSentMessagesCache((prev) => ({ ...prev, [clientMessageId]: content }));
          saveOwnMessageContent(chatId, userId, clientMessageId, content);

          const { encryptedContent, nonce } = await encryptMessage(content, recipientId);
          messageData = {
            ...messageData,
            encryptedContent,
            nonce,
            messageType: 'text',
            content,
            id: clientMessageId,
            clientMessageId,
          };

          const response = await fetch('https://unlimitedpossibilities12.org/api/messages', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              ...messageData,
              id: undefined,
              content: undefined,
            }),
          });
          if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Failed to send message: ${response.status} - ${errorText}`);
          }
          savedMessage = await response.json();
          messageData = { ...messageData, id: savedMessage.id };

          setMessages((prev) => {
            if (prev.some(m => m.id === clientMessageId)) return prev;
            return [...prev, { ...messageData, delivered: false, read: false }];
          });

        // === ФАЙЛОВОЕ СООБЩЕНИЕ ===
        } else if (selectedFile) {
          const { encryptedFile, nonce, encryptedContent } = await encryptFile(
            selectedFile,
            recipientId,
            userId,
            keyPair,
            publicKeys
          );

          const tempFileUrl = URL.createObjectURL(selectedFile);

          messageData = {
            ...messageData,
            messageType: 'file',
            contentType: selectedFile.type,
            id: clientMessageId,
            clientMessageId,
            encryptedContent,
            nonce,
            fileName: selectedFile.name,
            fileUrl: tempFileUrl,
          };

          const fileContentKey = `fileContent_${chatId}_${userId}_${clientMessageId}`;
          localStorage.setItem(fileContentKey, JSON.stringify({
            fileName: selectedFile.name,
            contentType: selectedFile.type,
            fileUrl: tempFileUrl,
          }));

          setMessages((prev) => {
            if (prev.some(m => m.id === clientMessageId)) return prev;
            return [...prev, {
              ...messageData,
              delivered: false,
              read: false,
              timestamp,
              sender: messageData.sender,
              chat: messageData.chat,
            }];
          });

          const response = await fetch('https://unlimitedpossibilities12.org/api/messages', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              ...messageData,
              id: undefined,
              content: undefined,
              fileName: undefined,
              fileUrl: undefined,
              file_url: undefined,
            }),
          });
          if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Failed to send file message: ${response.status} - ${errorText}`);
          }
          savedMessage = await response.json();

          serverFileUrl = await uploadFile(encryptedFile, nonce, savedMessage.id);
          if (!serverFileUrl) throw new Error('File upload failed');

          // ← ДОБАВЬ: обнови messageData
          messageData.fileUrl = serverFileUrl;
          messageData.file_url = serverFileUrl;
          messageData.fileName = selectedFile.name;
          messageData.contentType = selectedFile.type;

          localStorage.setItem(fileContentKey, JSON.stringify({
            fileName: selectedFile.name,
            contentType: selectedFile.type,
            fileUrl: serverFileUrl,
          }));

          saveFileToCache(chatId, userId, savedMessage.id, {
            fileName: selectedFile.name,
            contentType: selectedFile.type,
            fileUrl: serverFileUrl,
          });
          saveFileToCache(chatId, userId, clientMessageId, {
            fileName: selectedFile.name,
            contentType: selectedFile.type,
            fileUrl: tempFileUrl,
          });

          const arrayBuffer = await selectedFile.arrayBuffer();
          const base64File = sodium.to_base64(new Uint8Array(arrayBuffer));
          saveFileToCache(chatId, userId, clientMessageId, {
            fileName: selectedFile.name,
            contentType: selectedFile.type,
            fileData: base64File,
          });

        // === АУДИО СООБЩЕНИЕ ===
        } else if (audioBlob) {
          const audioFile = new File([audioBlob], `audio_${Date.now()}.mp3`, { type: 'audio/mpeg' });

          // === ШИФРУЕМ АУДИО КАК ОБЫЧНЫЙ ФАЙЛ ===
          const { encryptedFile, nonce, encryptedContent } = await encryptFile(
            audioFile,
            recipientId,
            userId,
            keyPair,
            publicKeys
          );

          // === СОЗДАЁМ СООБЩЕНИЕ ===
          messageData = {
            ...messageData,
            messageType: 'audio',
            contentType: 'audio/mpeg',
            id: clientMessageId,
            clientMessageId,
            encryptedContent,
            nonce,
            fileName: audioFile.name,
          };

          // === СОХРАНЯЕМ В БД ===
          const response = await fetch('https://unlimitedpossibilities12.org/api/messages', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              ...messageData,
              id: undefined,
              content: undefined,
              fileName: undefined,
              fileUrl: undefined,
              file_url: undefined,
            }),
          });

          if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Failed to send audio message: ${response.status} - ${errorText}`);
          }

          const savedMessage = await response.json();

          // === ЗАГРУЖАЕМ ЗАШИФРОВАННЫЙ ФАЙЛ ===
          serverFileUrl = await uploadFile(encryptedFile, nonce, savedMessage.id);
          if (!serverFileUrl) throw new Error('Audio upload failed');

          // === ОБНОВЛЯЕМ messageData ===
          messageData.fileUrl = serverFileUrl;
          messageData.file_url = serverFileUrl;
          messageData.fileName = audioFile.name;

          // === КЭШИРУЕМ (для своих сообщений) ===
          const fileContentKey = `fileContent_${chatId}_${userId}_${clientMessageId}`;
          localStorage.setItem(fileContentKey, JSON.stringify({
            fileName: audioFile.name,
            contentType: 'audio/mpeg',
            fileUrl: serverFileUrl,
          }));

          // === ДОБАВЛЯЕМ В UI ===
          setMessages((prev) => {
            if (prev.some(m => m.id === clientMessageId)) return prev;
            return [...prev, { ...messageData, delivered: false, read: false }];
          });
        }

        // === ОТПРАВКА ЧЕРЕЗ WebSocket (ТОЛЬКО ПОСЛЕ СОХРАНЕНИЯ) ===
        if (client && client.connected && savedMessage) {
          const websocketMessage = {
            ...messageData,
            id: savedMessage.id,
            content: undefined,
            clientMessageId: messageData.clientMessageId || undefined,
            fileUrl: messageData.fileUrl,
            file_url: messageData.file_url,
            fileName: messageData.fileName,
            contentType: messageData.contentType,
          };

          console.log('Sending via WebSocket:', websocketMessage);
          client.publish({
            destination: `/app/sendMessage/chat/${chatId}`,
            body: JSON.stringify(websocketMessage),
          });
        }

        // === Очистка формы ===
        resetForm();

      } catch (error) {
        console.error('Error sending message:', error);
        toast.error(`Failed to send message: ${error.message}`);
      }
    } else {
      toast.warn('Enter a message or select a file');
    }
  };

  const sendAudioMessage = async (audioBlob) => {
    if (!audioBlob || !userId || !keyPair) {
      console.warn('Audio, userId, or keyPair is missing');
      toast.error('Failed to send audio message');
      setMicIconState('mic');
      return;
    }

    console.log('Starting audio message send...');  // ← Лог 1

    // Сохраняем blob в ref для стабильности
    audioBlobRef.current = audioBlob;

    // === ПОЛУЧАЕМ recipientId ===
    const recipientName = chatName.split('_').find((name) => name !== username);
    if (!recipientName) {
      console.error('Recipient name not found');
      toast.error('Failed to find recipient');
      setMicIconState('mic');
      return;
    }

    try {
      const userResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`);
      if (!userResponse.ok) throw new Error('Failed to fetch recipient');
      const recipient = await userResponse.json();
      const recipientId = recipient.id;

      console.log(`Recipient ID: ${recipientId}`);  // ← Лог 2

      // === ШИФРУЕМ ===
      const audioFile = new File([audioBlob], `audio_${Date.now()}.mp3`, { type: 'audio/mpeg' });
      const { encryptedFile, nonce, encryptedContent } = await encryptFile(
        audioFile,
        recipientId,
        userId,
        keyPair,
        publicKeys
      );

      console.log('Audio encrypted successfully');  // ← Лог 3

      const clientMessageId = `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
      const timestamp = new Date().toISOString();

      const messageData = {
        sender: { id: userId, username },
        chat: { id: chatId, name: chatName, isSecret: true },
        messageType: 'audio',
        contentType: 'audio/mpeg',
        id: clientMessageId,
        clientMessageId,
        encryptedContent,
        nonce,
        fileName: audioFile.name,
      };

      // === ВРЕМЕННЫЙ UI ===
      const tempFileUrl = URL.createObjectURL(audioBlob);
      const fileContentKey = `fileContent_${chatId}_${userId}_${clientMessageId}`;
      localStorage.setItem(fileContentKey, JSON.stringify({
        fileName: audioFile.name,
        contentType: 'audio/mpeg',
        fileUrl: tempFileUrl,
      }));

      setMessages((prev) => {
        if (prev.some(m => m.id === clientMessageId)) return prev;
        return [...prev, { ...messageData, fileUrl: tempFileUrl, delivered: false, read: false }];
      });

      console.log('Temporary message added to UI');  // ← Лог 4

      // === СОХРАНЯЕМ В БД ===
      const response = await fetch('https://unlimitedpossibilities12.org/api/messages', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ...messageData,
          id: undefined,
          content: undefined,
          fileName: undefined,
          fileUrl: undefined,
          file_url: undefined,
        }),
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Failed to save audio message: ${response.status} - ${errorText}`);
      }

      const savedMessage = await response.json();
      console.log('Audio message saved to DB:', savedMessage.id);  // ← Лог 5 (исправь "waisted" на "saved")

      // === ЗАГРУЖАЕМ ФАЙЛ ===
      const serverFileUrl = await uploadFile(encryptedFile, nonce, savedMessage.id);
      if (!serverFileUrl) throw new Error('Audio upload failed');

      console.log('Audio file uploaded:', serverFileUrl);  // ← Лог 6

      // === ОБНОВЛЯЕМ ВРЕМЕННОЕ СООБЩЕНИЕ НА СЕРВЕРНОЕ ===
      setMessages((prev) => {
        return prev.map((msg) => {
          if (msg.id === clientMessageId) {
            return {
              ...msg,
              id: savedMessage.id,
              fileUrl: serverFileUrl,
              file_url: serverFileUrl,
              clientMessageId: undefined,  // Убираем temp ID
            };
          }
          return msg;
        });
      });

      // Кэш с серверным URL
      localStorage.setItem(fileContentKey, JSON.stringify({
        fileName: audioFile.name,
        contentType: 'audio/mpeg',
        fileUrl: serverFileUrl,
      }));

      // === WEBSOCKET ===
      if (client && client.connected) {
        const websocketMessage = {
          ...messageData,
          id: savedMessage.id,
          fileUrl: serverFileUrl,
          file_url: serverFileUrl,
          content: undefined,
          clientMessageId: undefined,
        };
        client.publish({
          destination: `/app/sendMessage/chat/${chatId}`,
          body: JSON.stringify({
            ...messageData,
            id: savedMessage.id,
            fileUrl: serverFileUrl,
            file_url: serverFileUrl,
            content: undefined,
            clientMessageId: messageData.clientMessageId  // ← ЭТО ДОЛЖНО БЫТЬ
          }),
        });
        console.log('Audio message sent via WebSocket');  // ← Лог 7
      }

      // === ОЧИСТКА ===
      setAudioBlob(null);
      setMicIconState('mic');
      //URL.revokeObjectURL(tempFileUrl);  // ← Теперь revoke после обновления UI
      toast.success('Audio message sent!');  // ← Этот toast теперь сработает
    } catch (error) {
      console.error('Error in sendAudioMessage:', error);
      toast.error(`Failed to send audio: ${error.message}`);
      setMicIconState('mic');
    }
  };

  const sendNotification = async (message, contentForNotification) => {
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
              content: contentForNotification, // "New message"
              chatId,
            }),
          });
          console.log(`Notification sent for message ${message.id} with content: ${contentForNotification}`);
        }
      } else {
        console.error(`Failed to fetch message status for ID ${message.id}: ${statusResponse.status}`);
      }
    } catch (error) {
      console.error('Error in sendNotification:', error);
    }
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

    if (message.sender.id !== userId) return;

    const messageEl = e.currentTarget;
    const rect = messageEl.getBoundingClientRect();

    const x = rect.left + window.scrollX - 12;
    const y = rect.bottom + window.scrollY - 18;

    setContextMenu({ visible: true, x, y, message });
  };

  const handleMenuAction = (action) => {
    const msg = contextMenu.message;
    if (!msg || msg.sender.id !== userId) return;

    if (action === 'edit' && msg.messageType === 'text') {
      setContent(msg.content);
      setEditingMessageId(msg.id);
      textAreaRef.current?.focus();
    } else if (action === 'delete') {
      deleteMessage(msg.id);
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
        console.log(`Recorded audio: size=${webmBlob.size}, type=${webmBlob.type}`);  // ← Добавь лог для отладки
        try {
          const inputName = 'input.webm';
          const outputName = 'output.mp3';
          const arrayBuffer = await webmBlob.arrayBuffer();
          await ffmpeg.current.writeFile(inputName, new Uint8Array(arrayBuffer));
          await ffmpeg.current.exec(['-i', inputName, '-c:a', 'libmp3lame', '-b:a', '128k', outputName]);
          const mp3Data = await ffmpeg.current.readFile(outputName);
          const mp3Blob = new Blob([mp3Data.buffer], { type: 'audio/mpeg' });
          console.log(`Converted audio: size=${mp3Blob.size}, type=${mp3Blob.type}`);  // ← Добавь лог
          stream.getTracks().forEach(track => track.stop());
          setMicIconState('sending');
          
          // ← ИЗМЕНИ: Не sendMessage(), а sendAudioMessage(mp3Blob)
          await sendAudioMessage(mp3Blob);
        } catch (error) {
          console.error('Error converting to mp3:', error);  // ← Этот лог должен появиться, если ошибка
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

  const saveFileToCache = (chatId, userId, messageId, fileData) => {
    try {
      const key = `fileCache_${chatId}_${userId}_${messageId}`;
      const cacheData = {
        fileName: fileData.fileName,
        contentType: fileData.contentType,
        fileUrl: fileData.fileUrl || null, // Сохраняем серверный fileUrl
      };
      localStorage.setItem(key, JSON.stringify(cacheData));
      console.log(`Saved file metadata to cache: messageId=${messageId}, fileName=${fileData.fileName}`);
    } catch (error) {
      console.error(`Failed to save file metadata to cache for messageId=${messageId}:`, error);
    }
  };

  const getFileFromCache = (chatId, userId, messageId) => {
    try {
      const key = `fileCache_${chatId}_${userId}_${messageId}`;
      const stored = localStorage.getItem(key);
      if (stored) {
        const fileData = JSON.parse(stored);
        console.log(`Retrieved file metadata from cache: messageId=${messageId}, fileName=${fileData.fileName}`);
        return fileData;
      }
      return null;
    } catch (error) {
      console.error(`Failed to get file metadata from cache for messageId=${messageId}:`, error);
      return null;
    }
  };

  // [ADD] Функция для скачивания собственного файла из кэша
  const downloadOwnFile = async (msg) => {
    try {
      const fileContentKey = `fileContent_${chatId}_${userId}_${msg.clientMessageId}`;
      const storedFileData = localStorage.getItem(fileContentKey);
      let fileData = {};
      if (storedFileData) {
        fileData = JSON.parse(storedFileData);
      }

      // [OPTIMIZE] Используем кэшированные расшифрованные данные
      if (fileData.fileData) {
        const decryptedFileBytes = sodium.from_base64(fileData.fileData);
        const blob = new Blob([decryptedFileBytes], { type: fileData.contentType || msg.contentType });
        const blobUrl = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = blobUrl;
        link.download = msg.fileName || 'file';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(blobUrl);
        return;
      }

      const fileUrl = fileData.fileUrl || msg.fileUrl;
      if (!fileUrl) throw new Error('File URL not available');

      const fileMetadata = await fetchFileMetadata(msg.id);
      const response = await fetch(fileUrl);
      if (!response.ok) throw new Error(`Failed to fetch file: ${response.status}`);
      const encryptedFileBytes = new Uint8Array(await response.arrayBuffer());

      const recipientName = chatName.split('_').find((name) => name !== username);
      const recipientResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`);
      const recipient = await recipientResponse.json();
      const recipientId = recipient.id;

      const decryptedFileBytes = await decryptFileContent(
        encryptedFileBytes,
        fileMetadata.nonce,
        userId,
        fileMetadata.publicKeyId,
        publicKeys,
        keyPair,
        recipientId
      );

      const blob = new Blob([decryptedFileBytes], { type: fileData.contentType || msg.contentType });
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = msg.fileName || 'file';
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(blobUrl);
    } catch (error) {
      console.error('Error downloading own file:', error);
      toast.error(`Failed to download file: ${error.message}`);
    }
  };

  const downloadFile = async (msg) => {
    try {
      const metadata = await fetchFileMetadata(msg.id);
      const { fileUrl, nonce, publicKeyId } = metadata;

      let fileName = 'file';
      let contentType = 'application/octet-stream';

      // === Расшифровка метаданных ===
      if (msg.encryptedContent && msg.nonce) {
        let senderPublicKey = publicKeys[msg.sender.id];
        if (!senderPublicKey) {
          console.log(`[downloadFile] Public key missing for senderId=${msg.sender.id}, fetching...`);
          senderPublicKey = await getPublicKey(msg.sender.id, msg.publicKeyId);
          setPublicKeys((prev) => ({ ...prev, [msg.sender.id]: senderPublicKey }));
        }

        const decryptedMeta = await decryptMessage(
          msg.encryptedContent,
          msg.nonce,
          msg.sender.id,
          msg.timestamp,
          senderPublicKey
        );
        const meta = JSON.parse(decryptedMeta);
        fileName = meta.fileName || 'file';
        contentType = meta.contentType || meta.mimeType || 'application/octet-stream';
      }

      // === Скачивание и расшифровка файла ===
      const res = await fetch(fileUrl);
      if (!res.ok) throw new Error(`Failed to download file: ${res.status}`);
      const encrypted = new Uint8Array(await res.arrayBuffer());

      // === ГАРАНТИРОВАННАЯ ЗАГРУЗКА КЛЮЧА ПЕРЕД РАСШИФРОВКОЙ ===
      let senderPublicKey = publicKeys[msg.sender.id];
      if (!senderPublicKey) {
        console.log(`[downloadFile] Forcing public key fetch for senderId=${msg.sender.id}`);
        senderPublicKey = await getPublicKey(msg.sender.id, publicKeyId);
        setPublicKeys((prev) => ({ ...prev, [msg.sender.id]: senderPublicKey }));
      }

      const decrypted = await decryptFileContent(
        encrypted,
        nonce,
        msg.sender.id,
        publicKeyId,
        publicKeys,
        keyPair,
        userId
      );

      const blob = new Blob([decrypted], { type: contentType });
      const blobUrl = URL.createObjectURL(blob);

      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(blobUrl);
    } catch (error) {
      console.error('Error downloading incoming file:', error);
      toast.error(`Failed to download file: ${error.message}`);
    }
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
                        {msg.messageType === 'file' && msg.fileUrl && (
                          <a
                            href="#"
                            className="private-file-link"
                            onClick={async (e) => {
                              e.preventDefault();
                              if (msg.sender.id === userId) {
                                await downloadOwnFile(msg); // [CHANGE] Используем новую функцию
                              } else {
                                await downloadFile(msg);
                              }
                            }}
                          >
                            Download {msg.fileName || 'Download file'}
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
      {contextMenu.visible && contextMenu.message && (
        <div
          ref={contextMenuRef}
          className="private-context-menu"
          style={{
            position: 'fixed',
            top: contextMenu.y + 'px',
            left: contextMenu.x + 'px',
            zIndex: 10000,
          }}
        >
          {contextMenu.message.messageType === 'text' && (
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