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

const getPublicKey = async (userId, publicKeyId = null) => { // [CHANGE] Добавлен параметр publicKeyId
  console.log(`Fetching public key for userId: ${userId}, publicKeyId: ${publicKeyId}`);
  try {
    const url = publicKeyId
      ? `https://unlimitedpossibilities12.org/api/public_keys/history/${publicKeyId}` // [ADD] Новый эндпоинт для получения ключа по publicKeyId
      : `https://unlimitedpossibilities12.org/api/public_keys/${userId}`;
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error(`Failed to fetch public key: ${response.status}`);
    }
    const publicKeyData = await response.json();
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
      /*Object.keys(localStorage)
        .filter((key) => key.startsWith('public_key_'))
        .forEach((key) => localStorage.removeItem(key));
      console.log('Cleared public key cache in localStorage');*/
      // Очистка старых данных file_ в localStorage
      Object.keys(localStorage)
        .filter((key) => key.startsWith(`file_${chatIdParam}_${userId}_`))
        .forEach((key) => {
          console.log(`Removing old cache key: ${key}`);
          localStorage.removeItem(key);
        });

      if (!chatIdParam || !username || !keyPair) {
        if (!keyPair) {
          console.warn('fetchChatData skipped: keyPair not ready');
          return;
        }
        toast.error('Invalid chat ID or user is not authorized');
        navigate('/');
        return;
      }

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

        const recipientPublicKey = await getPublicKey(recipientId); // [CHANGE] Без изменений, использует getPublicKey без publicKeyId
        setPublicKeys((prev) => ({
          ...prev,
          [recipientId]: recipientPublicKey,
        }));

        const initialMessages = await getMessages(chatIdParam);
        const senderIds = [...new Set(initialMessages.map((msg) => msg.sender.id))];
        const publicKeysPromises = senderIds
          .filter((senderId) => senderId !== userId && !publicKeys[senderId])
          .map(async (senderId) => {
            const publicKey = await getPublicKey(senderId); // [CHANGE] Без изменений, использует getPublicKey без publicKeyId
            return { senderId, publicKey };
          });
        const newPublicKeys = await Promise.all(publicKeysPromises);
        setPublicKeys((prev) => ({
          ...prev,
          ...Object.fromEntries(newPublicKeys.map(({ senderId, publicKey }) => [senderId, publicKey])),
        }));

        const decryptedMessages = await Promise.all(
          initialMessages.map(async (msg) => {
            if (msg.messageType === 'text' && msg.encryptedContent && msg.nonce) {
              if (msg.sender.id === userId) {
                const clientMessageId = msg.clientMessageId;
                const localContent = clientMessageId ? getOwnMessageContent(chatIdParam, userId, clientMessageId) : null;
                console.log(`Own message id: ${msg.id}, clientMessageId: ${clientMessageId}, localContent: ${localContent || 'not found'}, delivered: ${msg.delivered_status}, read: ${msg.read_status}`);
                return {
                  ...msg,
                  content: localContent || 'Content unavailable',
                  delivered: msg.delivered_status,
                  read: msg.read_status,
                };
              }
              try {
                const publicKey = await getPublicKey(msg.sender.id, msg.publicKeyId);
                const decryptedContent = await decryptMessage(
                  msg.encryptedContent,
                  msg.nonce,
                  msg.sender.id,
                  msg.timestamp,
                  publicKey
                );
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
            } else if (msg.messageType === 'file') {
              if (msg.sender.id === userId) {
                const fileContentKey = `fileContent_${chatIdParam}_${userId}_${msg.clientMessageId}`;
                const storedFileData = localStorage.getItem(fileContentKey);
                let fileData = {};
                if (storedFileData) {
                  fileData = JSON.parse(storedFileData);
                }
                return {
                  ...msg,
                  fileUrl: fileData.fileUrl || 'File unavailable',
                  fileName: fileData.fileName || 'File unavailable',
                  contentType: fileData.contentType || null,
                  delivered: msg.delivered_status,
                  read: msg.read_status,
                };
              }
              try {
                let publicKey = publicKeys[msg.sender.id];
                if (!publicKey || msg.publicKeyId) {
                  publicKey = await getPublicKey(msg.sender.id, msg.publicKeyId);
                  setPublicKeys((prev) => ({
                    ...prev,
                    [msg.sender.id]: publicKey,
                  }));
                }

                // Расшифровка метаданных
                let fileName, contentType;
                if (msg.encryptedContent && msg.nonce) {
                  const decryptedMetadata = await decryptMessage(
                    msg.encryptedContent,
                    msg.nonce,
                    msg.sender.id,
                    msg.timestamp,
                    publicKey
                  );
                  const metadata = JSON.parse(decryptedMetadata);
                  fileName = metadata.fileName;
                  contentType = metadata.contentType;
                } else {
                  throw new Error('Missing encryptedContent or nonce for file metadata');
                }

                // Проверка кэша
                const cachedFile = getFileFromCache(chatIdParam, userId, msg.id);
                let fileUrl;
                if (cachedFile) {
                  fileName = cachedFile.fileName;
                  contentType = cachedFile.contentType;
                  fileUrl = cachedFile.fileUrl;
                } else {
                  // Запрос FileMetadata
                  const fileMetadata = await fetchFileMetadata(msg.id);
                  if (!fileMetadata.fileUrl || !fileMetadata.nonce) {
                    throw new Error('Missing fileUrl or nonce in FileMetadata');
                  }

                  // Загрузка и расшифровка файла
                  const response = await fetch(fileMetadata.fileUrl);
                  if (!response.ok) {
                    throw new Error(`Failed to fetch file: ${response.status}`);
                  }
                  const encryptedFileBytes = new Uint8Array(await response.arrayBuffer());
                  const decryptedFileBytes = await decryptFileContent(
                    encryptedFileBytes,
                    fileMetadata.nonce,
                    msg.sender.id,
                    fileMetadata.publicKeyId,
                    publicKeys,
                    keyPair
                  );
                  const file = new Blob([decryptedFileBytes], { type: contentType });
                  fileUrl = URL.createObjectURL(file);

                  // Сохранение в кэш
                  saveFileToCache(chatIdParam, userId, msg.id, {
                    fileName,
                    contentType,
                    fileUrl,
                  });
                }

                return {
                  ...msg,
                  fileUrl,
                  fileName,
                  contentType,
                  delivered: msg.delivered_status,
                  read: msg.read_status,
                };
              } catch (error) {
                console.error('Decryption failed for file message:', msg.id, error);
                return {
                  ...msg,
                  fileName: 'Decryption failed',
                  contentType: null,
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
  }, [chatIdParam, username, setContextUserId, setNotificationUserId, setCurrentChatId, navigate, keyPair]);

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

        // Подписка на новые сообщения
        stompClientInstance.subscribe(`/topic/chat/${chatId}/messages`, async (message) => {
          const newMessage = JSON.parse(message.body);
          console.log('NewMessage:', newMessage);

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
                content: sentMessagesCache[newMessage.clientMessageId] || getOwnMessageContent(chatId, userId, newMessage.clientMessageId) || 'Content unavailable',
                delivered: newMessage.delivered_status,
                read: newMessage.read_status,
              });
            }
          } else if (newMessage.messageType === 'file') {
            // [CHANGE] Обрабатываем файловое сообщение
            if (newMessage.sender.id !== userId) {
              try {
                let publicKey = publicKeys[newMessage.sender.id];
                if (!publicKey || newMessage.publicKeyId) {
                  console.log(`Public key for senderId: ${newMessage.sender.id} not found or publicKeyId present, fetching...`);
                  publicKey = await getPublicKey(newMessage.sender.id, newMessage.publicKeyId);
                  setPublicKeys((prev) => ({
                    ...prev,
                    [newMessage.sender.id]: publicKey,
                  }));
                }

                // Расшифровка метаданных
                let fileName, contentType;
                if (newMessage.encryptedContent && newMessage.nonce) {
                  const decryptedMetadata = await decryptMessage(
                    newMessage.encryptedContent,
                    newMessage.nonce,
                    newMessage.sender.id,
                    newMessage.timestamp,
                    publicKey
                  );
                  const metadata = JSON.parse(decryptedMetadata);
                  fileName = metadata.fileName;
                  contentType = metadata.mimeType || metadata.contentType;
                  console.log(`Decrypted file metadata: fileName=${fileName}, contentType=${contentType}`);
                } else {
                  throw new Error('Missing encryptedContent or nonce for file metadata');
                }

                // Проверка кэша
                const cachedFile = getFileFromCache(chatId, userId, newMessage.id);
                let fileUrl;
                if (cachedFile) {
                  fileName = cachedFile.fileName;
                  contentType = cachedFile.contentType;
                  fileUrl = cachedFile.fileUrl;
                  console.log(`Using cached file: messageId=${newMessage.id}, fileName=${fileName}, fileUrl=${fileUrl}`);
                } else {
                  // Запрос FileMetadata
                  const fileMetadata = await fetchFileMetadata(newMessage.id);
                  if (!fileMetadata.fileUrl || !fileMetadata.nonce) {
                    throw new Error('Missing fileUrl or nonce in FileMetadata');
                  }

                  // Загрузка и расшифровка файла
                  const response = await fetch(fileMetadata.fileUrl);
                  if (!response.ok) {
                    throw new Error(`Failed to fetch file: ${response.status}`);
                  }
                  const encryptedFileBytes = new Uint8Array(await response.arrayBuffer());
                  const decryptedFileBytes = await decryptFileContent(
                    encryptedFileBytes,
                    fileMetadata.nonce,
                    newMessage.sender.id,
                    fileMetadata.publicKeyId,
                    publicKeys,
                    keyPair
                  );
                  const file = new Blob([decryptedFileBytes], { type: contentType });
                  fileUrl = URL.createObjectURL(file);

                  // Сохранение в кэш
                  saveFileToCache(chatId, userId, newMessage.id, {
                    fileName,
                    contentType,
                    fileUrl,
                  });
                  console.log(`Saved file to cache: messageId=${newMessage.id}, fileName=${fileName}, fileUrl=${fileUrl}`);
                }

                updateMessages({
                  ...newMessage,
                  fileUrl,
                  fileName,
                  contentType,
                  delivered: newMessage.delivered_status ?? false,
                  read: newMessage.read_status ?? false,
                });
              } catch (error) {
                console.error(`Failed to process file message ID=${newMessage.id}:`, error);
                updateMessages({
                  ...newMessage,
                  fileName: 'Decryption failed',
                  contentType: null,
                  delivered: newMessage.delivered_status ?? false,
                  read: newMessage.read_status ?? false,
                });
                toast.error(`Failed to process file: ${error.message}`);
              }
            } else {
              // Собственное файловое сообщение
              const fileContentKey = `fileContent_${chatId}_${userId}_${newMessage.clientMessageId}`;
              const storedFileData = localStorage.getItem(fileContentKey);
              let fileData = {};
              if (storedFileData) {
                fileData = JSON.parse(storedFileData);
                console.log(`Retrieved file data for own message: clientMessageId=${newMessage.clientMessageId}, fileName=${fileData.fileName}`);
              }
              let fileUrl = newMessage.fileUrl || fileData.fileUrl || 'File unavailable';
              let fileName = fileData.fileName || 'File unavailable';
              let contentType = fileData.contentType || null;

              // Сохраняем метаданные в кэш для нового messageId
              saveFileToCache(chatId, userId, newMessage.id, {
                fileName,
                contentType,
                fileUrl,
              });

              updateMessages({
                ...newMessage,
                fileUrl,
                fileName,
                contentType,
                delivered: newMessage.delivered_status ?? false,
                read: newMessage.read_status ?? false,
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

        // Подписка на обновления статуса сообщений
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

        // Подписка на обновления публичного ключа
        if (chatName) {
          const recipientName = chatName.replace('_secret', '').split('_').find((name) => name !== username);
          if (recipientName) {
            try {
              const response = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`);
              if (!response.ok) {
                throw new Error(`Failed to fetch recipient: ${response.status}`);
              }
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
  }, [chatId, userId, publicKeys, chatName, username]);

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
        console.log(`Deleting message with ID ${newMessage.id}`);
        return prevMessages.filter((msg) => msg.id !== newMessage.id);
      }

      console.log(`Processing new message: id=${newMessage.id}, senderId=${newMessage.sender.id}, messageType=${newMessage.messageType}, clientMessageId=${newMessage.clientMessageId}, timestamp=${newMessage.timestamp}`);

      // Пропускаем дубликат, если сообщение уже обработано с корректными метаданными
      const existingMessage = prevMessages.find((msg) => msg.id === newMessage.id);
      if (existingMessage && existingMessage.fileName !== 'File unavailable' && existingMessage.fileUrl !== 'File unavailable') {
        console.log(`Skipping duplicate message processing for id=${newMessage.id}`);
        return prevMessages;
      }

      // Проверяем, существует ли сообщение с таким же id
      const existingMessageIndex = prevMessages.findIndex((msg) => msg.id === newMessage.id);
      if (existingMessageIndex !== -1) {
        console.log(`Updating existing message with ID=${newMessage.id}`);
        const cachedFile = getFileFromCache(chatId, userId, newMessage.id) || getFileFromCache(chatId, userId, newMessage.clientMessageId);
        console.log(`Cached file for messageId=${newMessage.id}:`, cachedFile);
        const updatedMessages = [...prevMessages];
        updatedMessages[existingMessageIndex] = {
          ...prevMessages[existingMessageIndex],
          ...newMessage,
          content: newMessage.content || prevMessages[existingMessageIndex].content,
          fileName: cachedFile?.fileName || newMessage.fileName || prevMessages[existingMessageIndex].fileName || 'File unavailable',
          fileUrl: cachedFile?.fileUrl || newMessage.fileUrl || prevMessages[existingMessageIndex].fileUrl || 'File unavailable',
          contentType: cachedFile?.contentType || newMessage.contentType || prevMessages[existingMessageIndex].contentType || null,
          delivered: newMessage.delivered_status ?? prevMessages[existingMessageIndex].delivered,
          read: newMessage.read_status ?? prevMessages[existingMessageIndex].read,
          messageType: newMessage.messageType ?? prevMessages[existingMessageIndex].messageType,
        };
        console.log(`Updated message:`, updatedMessages[existingMessageIndex]);

        // Сохраняем метаданные в кэш только если они корректны
        if (newMessage.messageType === 'file' && cachedFile && cachedFile.fileName !== 'File unavailable') {
          saveFileToCache(chatId, userId, newMessage.id, {
            fileName: cachedFile.fileName,
            contentType: cachedFile.contentType,
            fileUrl: cachedFile.fileUrl,
          });
          console.log(`Saved file metadata to cache: messageId=${newMessage.id}, fileName=${cachedFile.fileName}`);
        }
        return updatedMessages;
      }

      // Для собственных сообщений с clientMessageId
      if (newMessage.sender.id === userId && newMessage.clientMessageId) {
        const tempMessageIndex = prevMessages.findIndex((msg) => msg.id === newMessage.clientMessageId);
        if (tempMessageIndex !== -1) {
          console.log(`Replacing temp message with ID=${newMessage.clientMessageId} with server message ID=${newMessage.id}`);
          const cachedFile = getFileFromCache(chatId, userId, newMessage.clientMessageId);
          console.log(`Cached file for clientMessageId=${newMessage.clientMessageId}:`, cachedFile);
          const updatedMessages = [...prevMessages];
          updatedMessages[tempMessageIndex] = {
            ...newMessage,
            content: newMessage.content || cachedFile?.content || 'Content unavailable',
            fileName: cachedFile?.fileName || newMessage.fileName || 'File unavailable',
            fileUrl: cachedFile?.fileUrl || newMessage.fileUrl || 'File unavailable',
            contentType: cachedFile?.contentType || newMessage.contentType || null,
            delivered: newMessage.delivered_status ?? false,
            read: newMessage.read_status ?? false,
            messageType: newMessage.messageType,
          };
          console.log(`Replaced message:`, updatedMessages[tempMessageIndex]);

          // Освобождаем старый blob-URL
          if (prevMessages[tempMessageIndex].fileUrl?.startsWith('blob:')) {
            URL.revokeObjectURL(prevMessages[tempMessageIndex].fileUrl);
            console.log(`Revoked old blob-URL: ${prevMessages[tempMessageIndex].fileUrl}`);
          }

          // Удаляем из кэша sentMessagesCache
          setSentMessagesCache((prev) => {
            const newCache = { ...prev };
            delete newCache[newMessage.clientMessageId];
            console.log(`Removed clientMessageId=${newMessage.clientMessageId} from sentMessagesCache`);
            return newCache;
          });

          // Сохраняем метаданные в кэш
          if (newMessage.messageType === 'file' && cachedFile && cachedFile.fileName !== 'File unavailable') {
            saveFileToCache(chatId, userId, newMessage.id, {
              fileName: cachedFile.fileName,
              contentType: cachedFile.contentType,
              fileUrl: cachedFile.fileUrl,
            });
            console.log(`Saved file metadata to cache: messageId=${newMessage.id}, fileName=${cachedFile.fileName}`);
          }

          return updatedMessages;
        }
      }

      // Для входящих файловых сообщений
      if (newMessage.messageType === 'file' && newMessage.sender.id !== userId) {
        console.log(`Processing incoming file message: id=${newMessage.id}`);
        const cachedFile = getFileFromCache(chatId, userId, newMessage.id);
        return [
          ...prevMessages,
          {
            ...newMessage,
            fileName: cachedFile?.fileName || newMessage.fileName || 'File unavailable',
            fileUrl: cachedFile?.fileUrl || newMessage.fileUrl || 'File unavailable',
            contentType: cachedFile?.contentType || newMessage.contentType || null,
            delivered: newMessage.delivered_status ?? false,
            read: newMessage.read_status ?? false,
          },
        ];
      }

      // Добавляем новое сообщение
      console.log(`Adding new message: id=${newMessage.id}, content=${newMessage.content || newMessage.fileName}`);
      const cachedFile = getFileFromCache(chatId, userId, newMessage.id) || getFileFromCache(chatId, userId, newMessage.clientMessageId);
      return [
        ...prevMessages,
        {
          ...newMessage,
          content: newMessage.content,
          fileName: cachedFile?.fileName || newMessage.fileName || 'File unavailable',
          fileUrl: cachedFile?.fileUrl || newMessage.fileUrl || 'File unavailable',
          contentType: cachedFile?.contentType || newMessage.contentType || null,
          delivered: newMessage.delivered_status ?? false,
          read: newMessage.read_status ?? false,
          messageType: newMessage.messageType,
        },
      ];
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
          headers: {
            'Content-Type': 'application/json',
          },
        });
        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(`Failed to fetch file metadata: ${response.status} - ${errorText}`);
        }
        const data = await response.json();
        console.log(`Fetched FileMetadata for messageId=${messageId}:`, data);

        if (!data.fileUrl && !data.fileUrlSelf) {
          throw new Error('Invalid file metadata: missing fileUrl or fileUrlSelf');
        }
        if (!data.nonce) {
          throw new Error('Invalid file metadata: missing nonce');
        }

        // Используем fileName из кэша, если сервер возвращает 'file'
        const cachedFile = getFileFromCache(chatId, userId, messageId);
        const fileName = cachedFile?.fileName || data.fileName || 'file';
        const fileUrlSelf = data.fileUrlSelf || data.fileUrl.replace('/encrypted/', '/encrypted_self/').replace('_encrypted_blob', `_encrypted_self_${fileName}`);
        return {
          fileUrl: data.fileUrl,
          fileUrlSelf: fileUrlSelf,
          nonce: data.nonce,
          fileName: fileName,
          publicKeyId: data.publicKeyId,
          contentType: data.contentType || cachedFile?.contentType || null,
        };
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

  const decryptMessage = async (encryptedContent, nonce, senderId, timestamp, publicKey) => { // [CHANGE] Добавлен параметр publicKey
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

    try {
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
      console.error(
        `Decryption error for senderId: ${senderId}, encryptedContent: ${encryptedContent}, nonce: ${nonce}, error: ${error.message}`
      );
      throw new Error(`Decryption failed: ${error.message}`);
    }
  };

  const encryptFile = async (file, recipientId, userId, keyPair, publicKeys) => {
    await sodium.ready;

    if (!keyPair || !publicKeys[recipientId] || !publicKeys[userId]) {
      console.error(`Encryption keys not available: publicKeyRecipient=${!!publicKeys[recipientId]}, publicKeySelf=${!!publicKeys[userId]}, privateKey=${!!keyPair?.privateKey}, recipientId=${recipientId}, userId=${userId}`);
      throw new Error('Encryption keys not available for file');
    }

    const privateKey = sodium.from_base64(keyPair.privateKey);
    const publicKeyRecipient = sodium.from_base64(publicKeys[recipientId]);
    const publicKeySelf = sodium.from_base64(publicKeys[userId]);

    // Читаем файл как массив байтов
    const fileBytes = new Uint8Array(await file.arrayBuffer());
    const nonce = sodium.randombytes_buf(sodium.crypto_box_NONCEBYTES);

    // Шифруем файл для получателя
    const fileRecipient = sodium.crypto_box_easy(fileBytes, nonce, publicKeyRecipient, privateKey);
    // Шифруем файл для отправителя
    const fileSelf = sodium.crypto_box_easy(fileBytes, nonce, publicKeySelf, privateKey);

    // Шифруем метаданные
    const metadata = {
      fileName: file.name,
      contentType: file.type,
    };
    const metadataBytes = sodium.from_string(JSON.stringify(metadata));
    const encryptedMetadataRecipient = sodium.crypto_box_easy(metadataBytes, nonce, publicKeyRecipient, privateKey);
    const encryptedMetadataSelf = sodium.crypto_box_easy(metadataBytes, nonce, publicKeySelf, privateKey);

    return {
      fileRecipient: sodium.to_base64(fileRecipient),
      fileSelf: sodium.to_base64(fileSelf),
      nonce: sodium.to_base64(nonce),
      encryptedMetadataRecipient: sodium.to_base64(encryptedMetadataRecipient),
      encryptedMetadataSelf: sodium.to_base64(encryptedMetadataSelf),
      originalFileName: file.name,
      contentType: file.type,
    };
  };

  const decryptFileContent = async (encryptedFileBytes, nonce, senderId, publicKeyId, publicKeys, keyPair) => {
    await sodium.ready;

    let publicKey = publicKeys[senderId];
    if (!publicKey && publicKeyId) {
      console.log(`Public key for senderId=${senderId} not found, fetching with publicKeyId=${publicKeyId}`);
      publicKey = await getPublicKey(senderId, publicKeyId);
      setPublicKeys((prev) => ({
        ...prev,
        [senderId]: publicKey,
      }));
    }
    if (!publicKey || !keyPair?.privateKey) {
      console.error(`Decryption keys not available: publicKey=${!!publicKey}, privateKey=${!!keyPair?.privateKey}, senderId=${senderId}`);
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
      console.error(`File decryption failed for senderId=${senderId}:`, error);
      throw new Error(`File decryption failed: ${error.message}`);
    }
  };

  const uploadFile = async (fileRecipient, fileSelf, nonce, messageId, fileName) => {
    console.log('Uploading files with messageId:', messageId, 'Type:', typeof messageId);

    // Загрузка файла для получателя
    const formDataRecipient = new FormData();
    formDataRecipient.append('file', new Blob([sodium.from_base64(fileRecipient)], { type: 'application/octet-stream' }));
    formDataRecipient.append('nonce', nonce);
    formDataRecipient.append('messageId', messageId);
    formDataRecipient.append('fileName', fileName); // Добавляем fileName

    const responseRecipient = await fetch('https://unlimitedpossibilities12.org/api/messages/upload_encrypted_file', {
      method: 'POST',
      body: formDataRecipient,
    });

    if (!responseRecipient.ok) {
      const errorText = await responseRecipient.text();
      throw new Error(`Recipient file upload failed: ${responseRecipient.status} - ${errorText}`);
    }
    const dataRecipient = await responseRecipient.json();
    console.log('Recipient file upload response:', dataRecipient);

    // Загрузка файла для отправителя
    const formDataSelf = new FormData();
    formDataSelf.append('file', new Blob([sodium.from_base64(fileSelf)], { type: 'application/octet-stream' }));
    formDataSelf.append('nonce', nonce);
    formDataSelf.append('messageId', messageId);
    formDataSelf.append('fileName', fileName); // Добавляем fileName

    const responseSelf = await fetch('https://unlimitedpossibilities12.org/api/messages/upload_encrypted_file_self', {
      method: 'POST',
      body: formDataSelf,
    });

    if (!responseSelf.ok) {
      const errorText = await responseSelf.text();
      throw new Error(`Self file upload failed: ${responseSelf.status} - ${errorText}`);
    }
    const dataSelf = await responseSelf.json();
    console.log('Self file upload response:', dataSelf);

    return {
      fileUrlRecipient: dataRecipient.fileUrl,
      fileUrlSelf: dataSelf.fileUrl,
    };
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

        const normalizedChatName = generateChatName(username, recipientName, true);
        const clientMessageId = `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
        const timestamp = new Date().toISOString();

        let messageData = {
          sender: { id: userId, username },
          chat: { id: chatId, name: normalizedChatName, isSecret: true },
          delivered: false,
          read: false,
          timestamp,
        };

        let savedMessage;
        if (content) {
          setSentMessagesCache((prev) => ({
            ...prev,
            [clientMessageId]: content,
          }));
          console.log(`Saved to sentMessagesCache: clientMessageId=${clientMessageId}, content=${content}`);
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

          // Отправка текстового сообщения
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
          console.log('Saved message:', savedMessage);
          messageData = { ...messageData, id: savedMessage.id };
        } else if (selectedFile) {
          const { fileRecipient, fileSelf, nonce, encryptedMetadataRecipient, encryptedMetadataSelf, originalFileName, contentType } = await encryptFile(
            selectedFile,
            recipientId,
            userId,
            keyPair,
            publicKeys
          );
          messageData = {
            ...messageData,
            messageType: 'file',
            contentType: contentType,
            id: clientMessageId,
            clientMessageId,
            encryptedContent: encryptedMetadataRecipient,
            nonce,
            fileName: originalFileName,
          };

          // Отправка сообщения
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
            throw new Error(`Failed to send message: ${response.status} - ${errorText}`);
          }
          savedMessage = await response.json();
          console.log('Saved message:', savedMessage);

          // Загрузка файлов
          const { fileUrlRecipient, fileUrlSelf } = await uploadFile(fileRecipient, fileSelf, nonce, savedMessage.id, originalFileName);
          console.log('Received file URLs:', { fileUrlRecipient, fileUrlSelf });
          if (!fileUrlRecipient || !fileUrlSelf) {
            throw new Error('File upload returned no fileUrl');
          }
          messageData.fileUrl = fileUrlRecipient;
          messageData.file_url = fileUrlRecipient;

          // Сохраняем метаданные в кэш
          saveFileToCache(chatId, userId, savedMessage.id, {
            fileName: originalFileName,
            contentType: contentType,
            fileUrl: fileUrlSelf, // Сохраняем fileUrlSelf для отправителя
          });
          saveFileToCache(chatId, userId, clientMessageId, {
            fileName: originalFileName,
            contentType: contentType,
            fileUrl: fileUrlSelf,
          });
          console.log(`Saved file metadata to cache for messageId=${savedMessage.id} and clientMessageId=${clientMessageId}`);
        } else if (audioBlob) {
          const audioFile = new File([audioBlob], `audio_${Date.now()}.mp3`, {
            type: 'audio/mpeg',
          });
          messageData = {
            ...messageData,
            messageType: 'audio',
            contentType: 'audio/mpeg',
            id: clientMessageId,
            clientMessageId,
          };

          // Отправка сообщения
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
            throw new Error(`Failed to send message: ${response.status} - ${errorText}`);
          }
          savedMessage = await response.json();
          console.log('Saved message:', savedMessage);

          // Загрузка аудио с messageId
          const fileUrl = await uploadFile(audioFile, null, savedMessage.id);
          console.log('Received fileUrl:', fileUrl);
          if (!fileUrl) {
            throw new Error('File upload returned no fileUrl');
          }
          messageData.fileUrl = fileUrl;
          messageData.file_url = fileUrl;
        }

        // Отправка WebSocket-сообщения ПОСЛЕ всех операций
        if (client && client.connected) {
          const websocketMessage = {
            ...messageData,
            id: savedMessage.id,
            content: undefined,
            fileName: undefined,
            clientMessageId: messageData.clientMessageId || undefined,
            fileUrl: messageData.fileUrl,
            file_url: messageData.file_url,
            contentType: messageData.contentType,
          };
          console.log('Sending WebSocket message:', JSON.stringify(websocketMessage));
          client.publish({
            destination: `/app/sendMessage/chat/${chatId}`,
            body: JSON.stringify(websocketMessage),
          });

          // Добавляем временное сообщение
          setMessages((prevMessages) => {
            if (prevMessages.some((msg) => msg.id === clientMessageId)) {
              console.log(`Temporary message with clientMessageId=${clientMessageId} already exists, skipping`);
              return prevMessages;
            }
            console.log(`Adding temporary message: clientMessageId=${clientMessageId}`);
            const tempMessage = {
              ...messageData,
              id: clientMessageId,
              fileUrl: selectedFile ? messageData.fileUrl : messageData.fileUrl, // Используем fileUrlRecipient для отображения
              fileName: selectedFile ? messageData.fileName : undefined,
              contentType: messageData.contentType,
              delivered: false,
              read: false,
              messageType: messageData.messageType,
              timestamp: messageData.timestamp,
              sender: messageData.sender,
              chat: messageData.chat,
            };
            console.log('Temporary message data:', tempMessage);
            return [...prevMessages, tempMessage];
          });

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

  const saveFileToCache = (chatId, userId, messageId, fileData) => {
    try {
      const key = `fileCache_${chatId}_${userId}_${messageId}`;
      const cacheData = {
        fileName: fileData.fileName,
        contentType: fileData.contentType,
        fileUrl: fileData.fileUrl || null,
      };
      localStorage.setItem(key, JSON.stringify(cacheData));
      console.log(`Saved file metadata to cache: messageId=${messageId}, fileName=${fileData.fileName}, fileUrl=${fileData.fileUrl}`);
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
      // Проверяем кэш
      const cachedFile = getFileFromCache(chatId, userId, msg.id) || getFileFromCache(chatId, userId, msg.clientMessageId);
      console.log(`Cached file for messageId=${msg.id}, clientMessageId=${msg.clientMessageId}:`, cachedFile);

      // Запрашиваем метаданные файла
      const fileMetadata = await fetchFileMetadata(msg.id);
      console.log(`Fetched self file metadata for messageId=${msg.id}:`, fileMetadata);
      if (!fileMetadata.fileUrlSelf || !fileMetadata.nonce) {
        throw new Error('Missing fileUrlSelf or nonce in self FileMetadata');
      }

      // Проверяем наличие ключей
      let publicKey = publicKeys[userId];
      if (!publicKey && fileMetadata.publicKeyId) {
        console.log(`Public key for userId=${userId} not found, fetching with publicKeyId=${fileMetadata.publicKeyId}`);
        publicKey = await getPublicKey(userId, fileMetadata.publicKeyId);
        setPublicKeys((prev) => ({
          ...prev,
          [userId]: publicKey,
        }));
      }
      if (!publicKey || !keyPair?.privateKey) {
        throw new Error('Decryption keys not available for file');
      }

      // Загружаем зашифрованный файл
      console.log(`Fetching file from: ${fileMetadata.fileUrlSelf}`);
      const response = await fetch(fileMetadata.fileUrlSelf);
      if (!response.ok) {
        throw new Error(`Failed to fetch file from server: ${response.status}`);
      }
      const encryptedFileBytes = new Uint8Array(await response.arrayBuffer());

      // Расшифровываем файл
      const decryptedFileBytes = await decryptFileContent(
        encryptedFileBytes,
        fileMetadata.nonce,
        userId,
        fileMetadata.publicKeyId,
        publicKeys,
        keyPair
      );

      // Создаем Blob и скачиваем
      const contentType = cachedFile?.contentType || msg.contentType || fileMetadata.contentType || 'application/octet-stream';
      const fileName = cachedFile?.fileName || msg.fileName || fileMetadata.fileName || 'file';
      console.log(`Creating Blob with contentType=${contentType}, fileName=${fileName}`);
      const blob = new Blob([decryptedFileBytes], { type: contentType });
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(blobUrl);
      console.log(`Downloaded own file from server: messageId=${msg.id}, fileName=${fileName}`);

      // Сохраняем метаданные в кэш, если они отсутствуют или некорректны
      if (!cachedFile || cachedFile.fileName === 'File unavailable') {
        saveFileToCache(chatId, userId, msg.id, {
          fileName: fileName,
          contentType: contentType,
          fileUrl: fileMetadata.fileUrlSelf,
        });
        console.log(`Saved file metadata to cache after download: messageId=${msg.id}, fileName=${fileName}`);
      }
    } catch (error) {
      console.error('Error downloading own file:', error);
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
                            href={msg.fileUrl}
                            download={msg.fileName || 'file'}
                            className="private-file-link"
                            onClick={async (e) => {
                              e.preventDefault(); // Предотвращаем стандартное скачивание
                              if (msg.sender.id === userId) {
                                await downloadOwnFile(msg); // Используем асинхронную функцию для собственных файлов
                              } else {
                                // Для входящих файлов используем стандартное скачивание
                                const link = document.createElement('a');
                                link.href = msg.fileUrl;
                                link.download = msg.fileName || 'file';
                                document.body.appendChild(link);
                                link.click();
                                document.body.removeChild(link);
                              }
                            }}
                          >
                            📥 {msg.fileName || 'Download file'}
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