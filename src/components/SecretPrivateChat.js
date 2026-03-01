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
import AssistantIcon from './AssistantIcon';

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
  const [decryptedAudioBlobs, setDecryptedAudioBlobs] = useState({}); // { [msgId]: { blobUrl: string, mp3Blob: Blob } }
  const [isTyping, setIsTyping] = useState(false); // Для индикатора печатания
  const [typingName, setTypingName] = useState(''); // Для индикатора печатания
  const [partnerId, setPartnerId] = useState(null);
  const [partnerAvatar, setPartnerAvatar] = useState(null);
  const typingTimerRef = useRef(null); // для таймера скрытия
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
  const [fullScreenImage, setFullScreenImage] = useState(null);
  const [decryptedImageBlobs, setDecryptedImageBlobs] = useState({}); // { [msg.id]: blobUrl }

  // Инициализация libsodium и генерация ключей
  useEffect(() => {
    const initializeSodium = async () => {
      await sodium.ready;
      const storedKeyPair = localStorage.getItem(`keyPair_${userId}`);
      console.log(`Checking keyPair for userId: ${userId}, exists: ${!!storedKeyPair}`);

      let parsedKeyPair = null;
      let publicKeyBase64 = '';   // ← Объявляем заранее, чтобы избежать no-undef
      let privateKeyBase64 = '';  // ← Объявляем заранее

      if (storedKeyPair) {
        try {
          parsedKeyPair = JSON.parse(storedKeyPair);
          console.log(`Private key length: ${parsedKeyPair.privateKey?.length}, Public key length: ${parsedKeyPair.publicKey?.length}`);
        } catch (e) {
          console.error(`Failed to parse stored keyPair, regenerating...`, e);
          localStorage.removeItem(`keyPair_${userId}`);
          parsedKeyPair = null;
        }
      }

      if (parsedKeyPair) {
        privateKeyBase64 = parsedKeyPair.privateKey || '';
        publicKeyBase64 = parsedKeyPair.publicKey || '';

        // Проверяем, корректна ли длина ключа
        let privateKeyBytes;
        try {
          privateKeyBytes = sodium.from_base64(privateKeyBase64);
          console.log(`Private key bytes length: ${privateKeyBytes.length}`);
        } catch (e) {
          console.error(`Private key decode error for userId: ${userId}, error: ${e.message}`);
          privateKeyBytes = null;
        }

        // Если длина неверная — чистим строку и пробуем снова
        if (!privateKeyBytes || privateKeyBytes.length !== sodium.crypto_box_SECRETKEYBYTES) {
          console.warn(`Invalid private key length (${privateKeyBytes?.length || 'invalid'}), attempting cleanup`);

          privateKeyBase64 = privateKeyBase64
            .trim()
            .replace(/\s+/g, '')          // убираем все пробелы, табы
            .replace(/[\r\n]+/g, '');     // убираем переносы строк

          publicKeyBase64 = publicKeyBase64
            .trim()
            .replace(/\s+/g, '')
            .replace(/[\r\n]+/g, '');

          try {
            privateKeyBytes = sodium.from_base64(privateKeyBase64);
            console.log(`After cleanup - Private key bytes length: ${privateKeyBytes.length}`);

            if (privateKeyBytes.length === sodium.crypto_box_SECRETKEYBYTES) {
              // Успех — перезаписываем в localStorage чистые строки
              localStorage.setItem(`keyPair_${userId}`, JSON.stringify({
                publicKey: publicKeyBase64,
                privateKey: privateKeyBase64
              }));
              console.log(`Cleaned key pair saved back to localStorage`);
            } else {
              console.warn(`Cleanup failed - length still wrong: ${privateKeyBytes.length}, regenerating key pair`);
              localStorage.removeItem(`keyPair_${userId}`);
              parsedKeyPair = null; // заставим сгенерировать новый
            }
          } catch (cleanError) {
            console.error(`Cleanup attempt failed:`, cleanError);
            localStorage.removeItem(`keyPair_${userId}`);
            parsedKeyPair = null;
          }
        }

        // Если после всех проверок ключ валиден — используем
        if (parsedKeyPair) {
          setKeyPair({
            publicKey: publicKeyBase64,
            privateKey: privateKeyBase64
          });

          setPublicKeys((prev) => ({
            ...prev,
            [userId]: publicKeyBase64,
          }));
        }
      }

      // Если ключа нет или он был удалён — генерируем новый
      if (!parsedKeyPair) {
        console.log(`Generating new key pair for userId: ${userId}`);
        const newKeyPair = sodium.crypto_box_keypair();
        publicKeyBase64 = sodium.to_base64(newKeyPair.publicKey).trim();
        privateKeyBase64 = sodium.to_base64(newKeyPair.privateKey).trim();

        localStorage.setItem(`keyPair_${userId}`, JSON.stringify({
          publicKey: publicKeyBase64,
          privateKey: privateKeyBase64
        }));

        setKeyPair({
          publicKey: publicKeyBase64,
          privateKey: privateKeyBase64
        });

        setPublicKeys((prev) => ({
          ...prev,
          [userId]: publicKeyBase64,
        }));
      }

      // Force-sync key всегда при reload/update (уведомление партнёра)
      const currentPublicKey = keyPair?.publicKey || publicKeyBase64; // ← Теперь publicKeyBase64 всегда определена
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
    };

    initializeSodium();
  }, [userId]);

  // Загрузка данных чата и сообщений
  useEffect(() => {
    const fetchChatData = async () => {
      if (!chatIdParam || !username || !keyPair) return;

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

        const recipientResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${interlocutorName}`);
        const recipient = await recipientResponse.json();
        const recipientId = recipient.id;

        setPartnerId(recipientId);
        setPartnerAvatar(recipient.avatar); // предполагается, что в ответе есть поле avatar (строка типа "/uploads/avatars/xxx.jpg")

        const recipientPublicKey = await getPublicKey(recipientId);
        setPublicKeys(prev => ({ ...prev, [recipientId]: recipientPublicKey }));

        const initialMessages = await getMessages(chatIdParam);
        const senderIds = [...new Set(initialMessages.map(m => m.sender.id))];
        const keyPromises = senderIds
          .filter(id => id !== userId && !publicKeys[id])
          .map(id => getPublicKey(id).then(pk => ({ senderId: id, publicKey: pk })));
        const newKeys = await Promise.all(keyPromises);
        setPublicKeys(prev => ({ ...prev, ...Object.fromEntries(newKeys.map(k => [k.senderId, k.publicKey])) }));

        const decryptedMessages = await Promise.all(
          initialMessages.map(async (msg) => {
            // === ТЕКСТ ===
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

            // === ФАЙЛЫ ===
            if (msg.messageType === 'file') {
              let fileName = 'Unknown file';
              let contentType = 'application/octet-stream';
              let fileUrl = msg.fileUrl || msg.file_url;

              // Свои файлы — из localStorage
              if (msg.sender.id === userId) {
                const key = `fileContent_${chatIdParam}_${userId}_${msg.clientMessageId}`;
                const stored = localStorage.getItem(key);
                if (stored) {
                  const fileData = JSON.parse(stored);
                  return { ...msg, ...fileData, delivered: msg.delivered_status, read: msg.read_status };
                }
                // Попытка по server id
                const key2 = `fileContent_${chatIdParam}_${userId}_${msg.id}`;
                const stored2 = localStorage.getItem(key2);
                if (stored2) {
                  const fileData = JSON.parse(stored2);
                  return { ...msg, ...fileData, delivered: msg.delivered_status, read: msg.read_status };
                }
              }

              // Восстанавливаем превью из base64-кэша для своих отправленных изображений
              if (msg.sender.id === userId && msg.contentType?.startsWith('image/')) {
                const previewKey = `imgPreview_${chatIdParam}_${userId}_${msg.id}`;
                const base64 = localStorage.getItem(previewKey);

                if (base64) {
                  const dataUrl = `data:image/jpeg;base64,${base64}`; // можно уточнить mime по msg.contentType
                  setDecryptedImageBlobs(prev => ({
                    ...prev,
                    [msg.id]: dataUrl
                  }));
                  console.log(`[PREVIEW RESTORE] Loaded base64 preview for msg ${msg.id}`);

                  // Можно сразу вернуть с восстановленным fileUrl, чтобы не обрабатывать дальше
                  return {
                    ...msg,
                    fileUrl: dataUrl,
                    // file_url: dataUrl,  // если где-то используется file_url
                  };
                }
              }

              // Расшифровка метаданных
              if (msg.encryptedContent && msg.nonce) {
                try {
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
                } catch (e) {
                  console.error('File metadata decrypt failed:', e);
                  fileName = 'Decryption failed';
                }
              }

              // ПОЛУЧИТЬ fileUrl, если нет
              if (!fileUrl && msg.id) {
                try {
                  console.log(`[FILE] History load: fetching metadata for ${msg.id}`);
                  const metadata = await fetchFileMetadata(msg.id);
                  fileUrl = metadata.fileUrl;
                  console.log(`[FILE] Got fileUrl: ${fileUrl}`);
                } catch (e) {
                  console.error(`[FILE] Failed to fetch metadata for ${msg.id}:`, e);
                }
              }

              return {
                ...msg,
                fileName,
                contentType,
                fileUrl,
                delivered: msg.delivered_status,
                read: msg.read_status
              };
            }

            // === АУДИО — РАСШИФРОВКА ПРИ ЗАГРУЗКЕ (БЕЗ fetchFileMetadata) ===
            if (msg.messageType === 'audio') {
              console.log('[AUDIO] Raw message:', {
                id: msg.id,
                fileUrl: msg.fileUrl,
                file_url: msg.file_url,
                nonce: msg.nonce,
                encryptedContent: msg.encryptedContent,
                publicKeyId: msg.publicKeyId,
                senderId: msg.sender.id
              });

              let fileName = 'audio.mp3';
              let contentType = 'audio/mpeg';
              let fileUrl = msg.fileUrl || msg.file_url;

              // --- МЕТАДАННЫЕ ---
              if (msg.encryptedContent && msg.nonce) {
                try {
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
                  fileName = meta.fileName || 'audio.mp3';
                  console.log('[AUDIO] Decrypted meta:', meta);
                } catch (e) {
                  console.error('[AUDIO] Meta decrypt failed:', e);
                  fileName = 'decryption_failed.mp3';
                }
              }

              // --- ЕСЛИ fileUrl НЕТ — БЕРЁМ ИЗ fetchFileMetadata ---
              if (!fileUrl && msg.nonce) {
                console.log(`[AUDIO] fileUrl missing → fetching metadata for ${msg.id}`);
                try {
                  const metadata = await fetchFileMetadata(msg.id);
                  fileUrl = metadata.fileUrl;
                  console.log(`[AUDIO] Got fileUrl from metadata: ${fileUrl}`);
                } catch (e) {
                  console.error(`[AUDIO] Failed to get fileUrl from metadata:`, e);
                  fileUrl = null;
                }
              }

              // --- РАСШИФРОВКА АУДИО ---
              if (fileUrl && msg.nonce && !fileUrl.startsWith('blob:')) {
                console.log(`[AUDIO] Decrypting: ${msg.id}`);
                try {
                  const decryptedBlobUrl = await decryptAudioForPlayback(
                    msg.id,
                    fileUrl,
                    msg.nonce,
                    msg.publicKeyId,
                    msg.sender.id
                  );
                  fileUrl = decryptedBlobUrl;
                  console.log(`[AUDIO] SUCCESS: ${msg.id} → ${decryptedBlobUrl}`);
                } catch (e) {
                  console.error(`[AUDIO] decryptAudioForPlayback failed:`, e);
                }
              } else if (!fileUrl) {
                console.warn(`[AUDIO] NO fileUrl for ${msg.id} — cannot decrypt`);
              }

              return {
                ...msg,
                fileName,
                contentType,
                fileUrl,
                file_url: fileUrl,
                delivered: msg.delivered_status,
                read: msg.read_status,
                _audioReady: fileUrl?.startsWith('blob:')
              };
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

          // Skip if already processed (avoid multiples)
          if (processedMessages.has(newMessage.id)) {
            console.log(`Skipped duplicate NewMessage for id=${newMessage.id}`);
            return;
          }
          setProcessedMessages(prev => new Set([...prev, newMessage.id]));

          // ← ОБРАБАТЫВАЕМ УДАЛЕНИЕ
          if (newMessage.content === 'Message deleted') {
            setMessages(prev => prev.filter(m => m.id !== newMessage.id));
            return;
          }

          // ← ЕСЛИ СООБЩЕНИЕ ОТ НАС — обновляем статусы (галочки)
          if (newMessage.sender?.id === userId) {
            console.log('Updating own message status (delivered/read):', newMessage.id);
            setMessages(prev => prev.map(m =>
              m.id === newMessage.id
                ? { ...m, delivered: newMessage.delivered_status ?? m.delivered, read: newMessage.read_status ?? m.read }
                : m
            ));
            // Trigger preload if audio (use current state fileUrl to avoid null)
            if (newMessage.messageType === 'audio' && audioRefs.current[newMessage.id]) {
              const currentMsg = messages.find(m => m.id === newMessage.id);
              if (currentMsg && currentMsg.fileUrl) {
                setTimeout(() => {
                  preloadAudio(newMessage.id, currentMsg.fileUrl, currentMsg);
                }, 100);
              } else {
                console.warn(`No fileUrl in state for own audio ${newMessage.id}`);
              }
            }
            return;
          }

          // ← ТЕКСТ
          if (newMessage.messageType === 'text' && newMessage.encryptedContent && newMessage.nonce) {
            try {
              const cleanedEncryptedContent = newMessage.encryptedContent.replace(/\n/g, '');
              const cleanedNonce = newMessage.nonce.replace(/\n/g, '');

              let publicKey = publicKeys[newMessage.sender.id];
              if (!publicKey || newMessage.publicKeyId) {
                publicKey = await getPublicKey(newMessage.sender.id, newMessage.publicKeyId);
                setPublicKeys((prev) => ({ ...prev, [newMessage.sender.id]: publicKey }));
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
                delivered: newMessage.delivered_status ?? false,
                read: newMessage.read_status ?? false,
              });
            } catch (error) {
              console.error('Decryption failed for new message:', newMessage.id, error);
              updateMessages({
                ...newMessage,
                content: 'Decryption failed',
                delivered: newMessage.delivered_status ?? false,
                read: newMessage.read_status ?? false,
              });
              toast.error(`Failed to decrypt message: ${error.message}`);
            }

          // ← ФАЙЛЫ И АУДИО (add trigger preload)
          } else if (newMessage.messageType === 'file' || newMessage.messageType === 'audio') {
            try {
              let fileName = 'Unknown file';
              let contentType = 'application/octet-stream';
              let fileUrl = newMessage.fileUrl || newMessage.file_url || null;

              if (newMessage.encryptedContent && newMessage.nonce) {  // Now has for audio
                let publicKey = publicKeys[newMessage.sender.id];
                if (!publicKey || newMessage.publicKeyId) {
                  publicKey = await getPublicKey(newMessage.sender.id, newMessage.publicKeyId);
                  setPublicKeys((prev) => ({ ...prev, [newMessage.sender.id]: publicKey }));
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
              }

              if (!fileUrl && newMessage.id) {
                try {
                  const meta = await fetchFileMetadata(newMessage.id);
                  fileUrl = meta.fileUrl;
                } catch (e) {
                  console.error('Failed to fetch fileUrl:', e);
                }
              }

              const updatedNewMessage = {
                ...newMessage,
                fileName,
                contentType,
                fileUrl,
                delivered: newMessage.delivered_status ?? false,
                read: newMessage.read_status ?? false,
                // Ensure sender for preload
                sender: newMessage.sender || { id: newMessage.senderId || 0 },  // Fallback
              };
              updateMessages(updatedNewMessage);

              // Trigger preload/decrypt after update for audio
              if (newMessage.messageType === 'audio' && audioRefs.current[newMessage.id]) {
                setTimeout(() => preloadAudio(newMessage.id, fileUrl, updatedNewMessage), 100);
              }
            } catch (error) {
              console.error(`Failed to decrypt file/audio metadata ID=${newMessage.id}:`, error);
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

          // ← ДРУГИЕ ТИПЫ (например, системные)
          } else {
            updateMessages({
              ...newMessage,
              delivered: newMessage.delivered_status ?? false,
              read: newMessage.read_status ?? false,
            });
          }
        });

        // ← ПОДПИСКА НА СТАТУСЫ ДОСТАВКИ/ПРОЧТЕНИЯ
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

        // ← НОВОЕ: Подписка на typing
        stompClientInstance.subscribe(`/topic/chat/${chatId}/typing`, (message) => {
          try {
            const data = JSON.parse(message.body);
            console.log('Typing event received in SecretChat:', data);

            const type = data.messageType;
            const sender = data.sender;

            if (sender && sender.id !== userId) {
              if (type === 'TYPING') {
                setTypingName(sender.username || 'Собеседник');
                setIsTyping(true);

                if (typingTimerRef.current) {
                  clearTimeout(typingTimerRef.current);
                }
                typingTimerRef.current = setTimeout(() => {
                  setIsTyping(false);
                  console.log('Typing indicator hidden by timeout in SecretChat');
                }, 2500);
              } else if (type === 'STOP_TYPING') {
                setIsTyping(false);
                if (typingTimerRef.current) {
                  clearTimeout(typingTimerRef.current);
                }
                console.log('STOP_TYPING received in SecretChat');
              }
            }
          } catch (err) {
            console.error('Error parsing typing event in SecretChat:', err);
          }
        });

        // ← ПОДПИСКА НА ОБНОВЛЕНИЕ КЛЮЧЕЙ
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
      if (typingTimerRef.current) {
        clearTimeout(typingTimerRef.current);
      }
    };
  }, [chatId, userId, chatName, username]);

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
    audioMessages.slice(-10).forEach(async (msg) => {  // async для preloadAudio
      if (
        audioRefs.current[msg.id] &&
        !audioProgress[msg.id]?.isReady &&
        !audioProgress[msg.id]?.isPreloading
      ) {
        await preloadAudio(msg.id, msg.fileUrl, msg);  // + msg param
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
      // УДАЛЕНИЕ
      if (newMessage.content === 'Message deleted') {
        return prev.filter(m => m.id !== newMessage.id);
      }

      // ОБНОВЛЕНИЕ ПО id
      const existingIdx = prev.findIndex(m => m.id === newMessage.id);
      if (existingIdx !== -1) {
        const oldMsg = prev[existingIdx];
        const oldFileUrl = oldMsg.fileUrl;

        // ← ДЛЯ СВОИХ СООБЩЕНИЙ: заменяем blob: на server URL
        if (newMessage.sender.id === userId && oldFileUrl?.startsWith('blob:')) {
          const updated = [...prev];
          updated[existingIdx] = {
            ...updated[existingIdx],
            ...newMessage,
            fileUrl: newMessage.fileUrl || newMessage.file_url,
            file_url: newMessage.file_url || newMessage.fileUrl,
          };
          URL.revokeObjectURL(oldFileUrl);
          console.log(`Replaced temp blob with server URL for ${newMessage.id}`);
          return updated;
        }

        const updated = [...prev];
        updated[existingIdx] = { ...updated[existingIdx], ...newMessage };
        if (oldFileUrl?.startsWith('blob:')) {
          URL.revokeObjectURL(oldFileUrl);
          console.log(`Revoked temp blob in updateMessages for ${newMessage.id}`);
        }
        return updated;
      }

      // ЗАМЕНА ВРЕМЕННОГО СООБЩЕНИЯ
      if (newMessage.sender.id === userId && newMessage.clientMessageId) {
        const tempIdx = prev.findIndex(m => m.id === newMessage.clientMessageId);
        if (tempIdx !== -1) {
          const tempMsg = prev[tempIdx];

          // [ADD] Переносим blob-URL в decryptedImageBlobs для превью (только для изображений)
          if (
            tempMsg.fileUrl?.startsWith('blob:') &&
            (tempMsg.contentType?.startsWith('image/') || 
            (tempMsg.fileName && /\.(jpg|jpeg|png|gif|webp)$/i.test(tempMsg.fileName)))
          ) {
            setDecryptedImageBlobs(prevBlobs => ({
              ...prevBlobs,
              [newMessage.id]: tempMsg.fileUrl,   // Переносим blob на новый ID
            }));
          }
          
          const oldFileUrl = tempMsg.fileUrl;
          const updated = [...prev];
          updated[tempIdx] = { 
            ...tempMsg,
            ...newMessage,
            id: newMessage.id,
            clientMessageId: undefined,
            fileUrl: newMessage.fileUrl || newMessage.file_url,
            file_url: newMessage.file_url || newMessage.fileUrl,
            delivered: newMessage.delivered_status ?? false,
            read: newMessage.read_status ?? false,
          };
          if (oldFileUrl?.startsWith('blob:')) {
            URL.revokeObjectURL(oldFileUrl);
            console.log(`Revoked temp blob in updateMessages for ${newMessage.id}`);
          }
          setSentMessagesCache(p => { const c = { ...p }; delete c[newMessage.clientMessageId]; return c; });
          return updated;
        }
      }

      return [...prev, { ...newMessage, delivered: false, read: false }];
    });

    // Trigger preload for audio
    if (newMessage.messageType === 'audio' && audioRefs.current[newMessage.id]) {
      const fullMsg = { ...newMessage, sender: { id: newMessage.sender?.id || userId } };
      setTimeout(() => preloadAudio(newMessage.id, newMessage.fileUrl || newMessage.file_url, fullMsg), 100);
    }
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
        console.log(`[METADATA] Fetching for messageId=${messageId}, attempt=${attempt}`);
        const response = await fetch(`https://unlimitedpossibilities12.org/api/messages/file_metadata/${messageId}`);
        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(`HTTP ${response.status}: ${errorText}`);
        }
        const data = await response.json();
        console.log(`[METADATA] SUCCESS for ${messageId}:`, data);
        if (!data.fileUrl || !data.nonce) {
          throw new Error(`Invalid metadata: ${JSON.stringify(data)}`);
        }
        return data;
      } catch (error) {
        console.error(`[METADATA] FAIL attempt ${attempt}:`, error.message);
        if (attempt === retries) throw error;
        await new Promise(r => setTimeout(r, delay * attempt));
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

  const decryptMessage = async (encryptedContent, nonce, senderId, timestamp, publicKey, originalPublicKeyId = null) => {
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
    let privateKeyBytes = sodium.from_base64(keyPair.privateKey);

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

      // ← Добавляем ТОЛЬКО обработку конкретной мобильной проблемы
      const errorMsg = error.message.toLowerCase();
      if (
        errorMsg.includes('invalid') ||
        errorMsg.includes('length') ||
        errorMsg.includes('incorrect key pair') ||
        errorMsg.includes('key')
      ) {
        console.warn('Possible mobile browser key corruption detected — attempting one-time cleanup retry');

        // Чистим строку приватного ключа ТОЛЬКО для этой попытки
        const cleanedPrivateKey = keyPair.privateKey
          .trim()
          .replace(/\s+/g, '')           // убираем все пробелы, табы
          .replace(/[\r\n]+/g, '');      // убираем переносы строк

        try {
          privateKeyBytes = sodium.from_base64(cleanedPrivateKey);
          console.log(`Cleaned private key bytes length: ${privateKeyBytes.length}`);

          // Пробуем ещё раз с очищенным ключом
          const decryptedBytes = sodium.crypto_box_open_easy(
            encryptedBytes,
            nonceBytes,
            publicKeyBytes,
            privateKeyBytes
          );
          const decryptedMessage = sodium.to_string(decryptedBytes);
          console.log(`Retry with cleaned private key SUCCESS: ${decryptedMessage}`);

          // Если успех — можно тихо обновить state (не обязательно, но полезно)
          // setKeyPair(prev => ({ ...prev, privateKey: cleanedPrivateKey }));

          return decryptedMessage;
        } catch (cleanError) {
          console.error('Retry with cleaned key failed:', cleanError);
        }
      }

      // ← Твой существующий fallback на current key (остаётся без изменений)
      if (originalPublicKeyId && error.message.includes('incorrect key pair')) {
        console.log(`Retry with current key for senderId=${senderId}`);
        const currentKey = await getPublicKey(senderId);  // No ID = current
        const currentBytes = sodium.from_base64(currentKey);
        try {
          const decryptedBytes = sodium.crypto_box_open_easy(
            encryptedBytes,
            nonceBytes,
            currentBytes,
            privateKeyBytes
          );
          const decryptedMessage = sodium.to_string(decryptedBytes);
          console.log(`Retry success: ${decryptedMessage}`);
          return decryptedMessage;
        } catch (fallbackErr) {
          console.error('Fallback retry failed:', fallbackErr);
        }
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
        // Эту логику не трогаем, она работает
        publicKey = publicKeys[recipientId];
        if (!publicKey && publicKeyId) {
            console.log(`[OWN FILE] Fetching public key for recipientId=${recipientId}, publicKeyId=${publicKeyId}`);
            publicKey = await getPublicKey(recipientId, publicKeyId);
            setPublicKeys((prev) => ({ ...prev, [recipientId]: publicKey }));
        }
    } else {
        // === ВХОДЯЩИЙ ФАЙЛ — ключ отправителя ===

        // Сначала пытаемся взять ключ по publicKeyId из сообщения (исторический)
        if (publicKeyId) {
            console.log(`[INCOMING FILE] Trying history public key: senderId=${senderId}, publicKeyId=${publicKeyId}`);
            try {
                publicKey = await getPublicKey(senderId, publicKeyId);
            } catch (err) {
                console.warn(`Failed to fetch history key for publicKeyId=${publicKeyId}, falling back to current`);
            }
        }

        // Если не получилось или publicKeyId не было — берём текущий ключ
        if (!publicKey) {
            console.log(`[INCOMING FILE] Fetching current public key for senderId=${senderId}`);
            publicKey = await getPublicKey(senderId); // без publicKeyId → текущий
        }

        setPublicKeys((prev) => ({ ...prev, [senderId]: publicKey }));
    }

    if (!publicKey || !keyPair?.privateKey) {
        throw new Error('Decryption keys not available for file');
    }

    const publicKeyBytes = sodium.from_base64(publicKey);
    const privateKeyBytes = sodium.from_base64(keyPair.privateKey);
    const nonceBytes = sodium.from_base64(nonce);

    try {
        const decryptedBytes = sodium.crypto_box_open_easy(
            encryptedFileBytes,
            nonceBytes,
            publicKeyBytes,
            privateKeyBytes
        );

        console.log(`File decryption successful for senderId=${senderId} (used keyId: ${publicKeyId || 'current'})`);
        return decryptedBytes;
    } catch (error) {
        console.error(`File decryption failed with used public key:`, {
            error: error.message,
            usedPublicKeyId: publicKeyId || 'current',
            publicKeyLength: publicKey.length,
        });

        // Если ошибка именно "incorrect key pair" и мы использовали history-ключ — пробуем current ещё раз явно
        if (
            error.message.includes('incorrect key pair') &&
            publicKeyId && 
            senderId !== userId
        ) {
            console.log(`[INCOMING FILE] Retry decryption with current key for senderId=${senderId}`);
            const currentPublicKey = await getPublicKey(senderId); // без ID → current
            const currentPublicKeyBytes = sodium.from_base64(currentPublicKey);

            try {
                const decryptedBytes = sodium.crypto_box_open_easy(
                    encryptedFileBytes,
                    nonceBytes,
                    currentPublicKeyBytes,
                    privateKeyBytes
                );
                console.log(`File decryption succeeded on retry with current key for senderId=${senderId}`);
                // Сохраняем текущий ключ в state, чтобы в будущем не мучиться
                setPublicKeys((prev) => ({ ...prev, [senderId]: currentPublicKey }));
                return decryptedBytes;
            } catch (retryError) {
                console.error(`Retry also failed:`, retryError);
                throw new Error(`File decryption failed even with current key: ${retryError.message}`);
            }
        }

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

          // Сохраняем base64-превью для своих изображений (чтобы пережило перезагрузку)
          if (selectedFile.type.startsWith('image/')) {
            const reader = new FileReader();
            reader.onload = () => {
              const base64Data = reader.result.split(',')[1]; // убираем префикс data:image/...;base64,
              const cacheKey = `imgPreview_${chatId}_${userId}_${clientMessageId}`;
              localStorage.setItem(cacheKey, base64Data);
              console.log(`[PREVIEW CACHE] Saved base64 for temp msg ${clientMessageId}, key: ${cacheKey}, length: ${base64Data.length}`);

              // Сразу кладём в состояние (для текущей сессии)
              setDecryptedImageBlobs(prev => ({
                ...prev,
                [clientMessageId]: reader.result,          // data: URL
                [savedMessage?.id || clientMessageId]: reader.result  // на случай, если id уже есть
              }));
            };
            reader.readAsDataURL(selectedFile);
          }

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

          // [ADD] Сохраняем временный blob-URL в decryptedImageBlobs для превью (только для изображений)
          if (selectedFile.type.startsWith('image/')) {
            setDecryptedImageBlobs(prev => ({
              ...prev,
              [clientMessageId]: tempFileUrl,  // На временный ID (на всякий случай)
              [savedMessage.id]: tempFileUrl,   // На постоянный серверный ID (основное)
            }));
          }

          serverFileUrl = await uploadFile(encryptedFile, nonce, savedMessage.id);
          if (!serverFileUrl) throw new Error('File upload failed');

          // ────────────────────────────────────────────────
          // ЭТОТ БЛОК ДОБАВИТЬ (3 строки) — и всё
          setDecryptedImageBlobs(prev => ({
            ...prev,
            [savedMessage.id]: tempFileUrl   // сохраняем временный blob как превью
          }));
          // ────────────────────────────────────────────────

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

          // ← ФИКС: ЗАМЕНА TEMP В UI (сразу после upload)
          setMessages((prev) => prev.map((msg) =>
            msg.id === clientMessageId
              ? {
                  ...msg,
                  id: savedMessage.id,
                  fileUrl: serverFileUrl,
                  file_url: serverFileUrl,
                  clientMessageId: undefined,
                  delivered: savedMessage.delivered_status ?? false,
                  read: savedMessage.read_status ?? false,
                  timestamp: savedMessage.timestamp || timestamp,
                }
              : msg
          ));

          // ← ФИКС: WS PUBLISH ДЛЯ FILE (с реальными статусами)
          if (client && client.connected) {
            const wsPayload = {
              ...messageData,
              id: savedMessage.id,
              content: undefined,
              clientMessageId: undefined,
              fileUrl: serverFileUrl,
              file_url: serverFileUrl,
              fileName: selectedFile.name,
              contentType: selectedFile.type,
              encryptedContent,
              nonce,
              delivered: savedMessage.delivered_status ?? false,  // Из сервера
              read: savedMessage.read_status ?? false,
              timestamp: savedMessage.timestamp || timestamp,
            };

            console.log('SENDING FILE VIA WS:', wsPayload);
            client.publish({
              destination: `/app/sendMessage/chat/${chatId}`,
              body: JSON.stringify(wsPayload),
            });
          }

          // ← ОЧИСТКА TEMP BLOB
          //URL.revokeObjectURL(tempFileUrl);

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

        sendTypingEvent(false);

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

    const recipientName = chatName.split('_').find((name) => name !== username);
    if (!recipientName) {
      console.error('Recipient name not found');
      toast.error('Failed to find recipient');
      setMicIconState('mic');
      return;
    }

    // ← ОБЪЯВЛЯЕМ clientMessageId ДО try
    let clientMessageId = `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    let tempFileUrl = null;

    try {
      const userResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`);
      if (!userResponse.ok) throw new Error('Failed to fetch recipient');
      const recipient = await userResponse.json();
      const recipientId = recipient.id;

      const audioFile = new File([audioBlob], `audio_${Date.now()}.mp3`, { type: 'audio/mpeg' });
      const { encryptedFile, nonce, encryptedContent } = await encryptFile(
        audioFile,
        recipientId,
        userId,
        keyPair,
        publicKeys
      );

      tempFileUrl = URL.createObjectURL(audioBlob);

      // ← КЭШИРУЕМ blob + metadata
      const fileContentKey = `fileContent_${chatId}_${userId}_${clientMessageId}`;
      localStorage.setItem(fileContentKey, JSON.stringify({
        fileName: audioFile.name,
        contentType: 'audio/mpeg',
        fileUrl: tempFileUrl,
        nonce,
        encryptedContent,
      }));

      // ← ДОБАВЛЯЕМ В UI С blob URL
      setMessages((prev) => {
        if (prev.some(m => m.id === clientMessageId)) return prev;
        return [...prev, {
          id: clientMessageId,
          clientMessageId,
          sender: { id: userId, username },
          chat: { id: chatId, name: chatName, isSecret: true },
          messageType: 'audio',
          fileUrl: tempFileUrl,
          fileName: audioFile.name,
          contentType: 'audio/mpeg',
          delivered: false,
          read: false,
          timestamp: new Date().toISOString(),
          nonce,
          encryptedContent,
        }];
      });

      // ← 1. СОХРАНЯЕМ СООБЩЕНИЕ
      const postPayload = {
        chat: { id: chatId, name: chatName, isSecret: true },
        sender: { id: userId, username },
        messageType: 'audio',
        encryptedContent,
        nonce,
        clientMessageId,
        contentType: 'audio/mpeg',
        fileUrl: null,
        delivered: false,
        read: false,
        timestamp: new Date().toISOString(),
      };

      console.log('POST /api/messages payload:', postPayload);

      const response = await fetch('https://unlimitedpossibilities12.org/api/messages', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(postPayload),
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Failed to save audio message: ${response.status} - ${errorText}`);
      }

      const savedMessage = await response.json();
      console.log('Saved message from server:', savedMessage);

      // ← 2. ЗАГРУЖАЕМ ФАЙЛ
      const serverFileUrl = await uploadFile(encryptedFile, nonce, savedMessage.id);
      console.log('Encrypted file uploaded, serverFileUrl:', serverFileUrl);

      // ← 3. ЗАМЕНЯЕМ В UI
      setMessages((prev) => prev.map((msg) =>
        msg.id === clientMessageId
          ? {
              ...msg,
              id: savedMessage.id,
              fileUrl: serverFileUrl,
              file_url: serverFileUrl,
              clientMessageId: undefined,
              delivered: savedMessage.delivered_status ?? false,
              read: savedMessage.read_status ?? false,
              timestamp: savedMessage.timestamp,
            }
          : msg
      ));

      // ← ОБНОВЛЯЕМ КЭШ
      localStorage.setItem(fileContentKey, JSON.stringify({
        fileName: audioFile.name,
        contentType: 'audio/mpeg',
        fileUrl: serverFileUrl,
        nonce,
        encryptedContent,
      }));

      // ← 4. ОТПРАВЛЯЕМ ЧЕРЕЗ WS
      if (client && client.connected) {
        const wsPayload = {
          id: savedMessage.id,
          messageType: 'audio',
          fileUrl: serverFileUrl,
          file_url: serverFileUrl,
          fileName: audioFile.name,
          contentType: 'audio/mpeg',
          encryptedContent,
          nonce,
          sender: { id: userId, username },
          chat: { id: chatId, name: chatName, isSecret: true },
          delivered: savedMessage.delivered_status ?? false,
          read: savedMessage.read_status ?? false,
          timestamp: savedMessage.timestamp,
        };

        console.log('SENDING AUDIO VIA WS (FULL):', wsPayload);

        client.publish({
          destination: `/app/sendMessage/chat/${chatId}`,
          body: JSON.stringify(wsPayload),
        });
      }

      // ← ОЧИЩАЕМ
      if (tempFileUrl?.startsWith('blob:')) {
        URL.revokeObjectURL(tempFileUrl);
      }
      localStorage.removeItem(fileContentKey);

      setAudioBlob(null);
      setMicIconState('mic');
      toast.success('Audio message sent!');

    } catch (error) {
      console.error('Error in sendAudioMessage:', error);
      toast.error(`Failed to send audio: ${error.message}`);
      setMicIconState('mic');

      // ← УДАЛЯЕМ temp сообщение — clientMessageId ДОСТУПЕН!
      setMessages((prev) => prev.filter(m => m.id !== clientMessageId));
      localStorage.removeItem(`fileContent_${chatId}_${userId}_${clientMessageId}`);

      // ← ОЧИЩАЕМ blob, если создан
      if (tempFileUrl?.startsWith('blob:')) {
        URL.revokeObjectURL(tempFileUrl);
      }
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

    // ← Для логики печатания
    if (newValue.trim() !== '') {
      sendTypingEvent(true);
    } else {
      sendTypingEvent(false); // если стёрли весь текст — тоже убираем индикатор
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
      console.log(`Audio reset to start for msg ${msgId}`);
    }
  };

  const preloadAudio = async (msgId, url, msg) => {  // Добавлен msg param
    const audio = audioRefs.current[msgId];
    if (!audio || audioProgress[msgId]?.isReady || audioProgress[msgId]?.decrypted) {
      console.log(`Preload skipped for ${msgId}: already ready/decrypted`);
      return;
    }
    if (audioProgress[msgId]?.isPreloading) return;

    setAudioProgress(prev => ({ ...prev, [msgId]: { ...prev[msgId], isPreloading: true } }));

    // ← ЗАЩИТА ОТ NULL
    if (!url || typeof url !== 'string') {
      console.warn(`Invalid URL for preload: ${url}`);
      setAudioProgress(prev => ({ ...prev, [msgId]: { ...prev[msgId], isPreloading: false, error: 'No file URL' } }));
      return;
    }

    let playableUrl = url;

    if (!msg.sender) msg.sender = { id: userId };

    // Если зашифровано — расшифровываем
    if (msg.nonce && !url.startsWith('blob:')) {
      try {
        playableUrl = await decryptAudioForPlayback(msgId, url, msg.nonce, msg.publicKeyId || null, msg.sender.id);
      } catch (e) {
        setAudioProgress(prev => ({ ...prev, [msgId]: { ...prev[msgId], isPreloading: false, error: 'Decrypt failed' } }));
        return;
      }
    }

    // Для своих temp blob — используем как есть
    if (url.startsWith('blob:') && msg.sender.id === userId) {
      console.log(`Using temp blob for own audio ${msgId}`);
    }

    if (audio.src === playableUrl && audio.readyState >= 2) {
      setAudioProgress(prev => ({
        ...prev,
        [msgId]: {
          ...prev[msgId],
          isPreloading: false,
          isReady: true,
          decrypted: true,
          error: audio.duration === Infinity ? 'Duration unavailable' : null,
        },
      }));
      return;
    }

    const cacheBusterUrl = playableUrl.startsWith('http') ? `${playableUrl}?t=${Date.now()}` : playableUrl;
    audio.src = cacheBusterUrl;
    audio.type = playableUrl.endsWith('.mp3') ? 'audio/mpeg' : 'audio/webm';
    audio.load();

    audio.addEventListener('loadedmetadata', () => {
      console.log(`Loaded metadata for msg ${msgId}: duration=${audio.duration}, readyState=${audio.readyState}`);
      const duration = isFinite(audio.duration) && audio.duration > 0 ? audio.duration : 0;
      setAudioProgress(prev => ({
        ...prev,
        [msgId]: {
          ...prev[msgId],
          isPreloading: false,
          isReady: audio.readyState >= 2,
          decrypted: true,
          duration,
          error: duration === 0 ? 'Duration unavailable: server issue' : null,
        },
      }));
    }, { once: true });

    audio.addEventListener('canplay', () => {
      console.log(`Can play msg ${msgId}: readyState=${audio.readyState}`);
      setAudioProgress(prev => ({
        ...prev,
        [msgId]: {
          ...prev[msgId],
          isPreloading: false,
          isReady: true,
          decrypted: true,
        },
      }));
    }, { once: true });

    audio.addEventListener('error', (e) => {
      const errorMsg = e.target.error ? `Error code: ${e.target.error.code}, message: ${e.target.error.message}` : 'Unknown error';
      console.error(`Preload error for msg ${msgId}: ${errorMsg}, URL: ${playableUrl}, readyState: ${audio.readyState}`);
      setAudioProgress(prev => {
        const prevData = prev[msgId] || {};
        if (!prevData.retryCount || prevData.retryCount < 2) {
          setTimeout(async () => {
            console.log(`Retrying preload for msg ${msgId}, attempt ${prevData.retryCount + 1}`);
            let retryUrl = playableUrl;
            if (msg.nonce && !decryptedAudioBlobs[msgId]) {
              // Retry decrypt on error
              try {
                retryUrl = await decryptAudioForPlayback(msgId, url, msg.nonce, msg.publicKeyId || null, msg.sender.id);
              } catch (retryErr) {
                console.error(`Decrypt retry failed:`, retryErr);
                return;  // Don't load
              }
            }
            audio.src = retryUrl.startsWith('http') ? `${retryUrl}?t=${Date.now()}` : retryUrl;
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
            decrypted: false,
            error: `Failed to preload audio: ${errorMsg}`,
          },
        };
      });
    }, { once: true });
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

  const decryptAudioForPlayback = async (msgId, fileUrl, nonce, publicKeyId, senderId) => {
    try {
      console.log(`Decrypting audio for playback: msgId=${msgId}, fileUrl=${fileUrl}`);
      const res = await fetch(fileUrl);
      if (!res.ok) throw new Error(`Fetch failed: ${res.status}`);
      const encryptedBytes = new Uint8Array(await res.arrayBuffer());

      let publicKey;
      let decryptRecipientId;

      if (senderId === userId) {
        // СВОЁ СООБЩЕНИЕ → шифровали на ключ ПОЛУЧАТЕЛЯ
        const recipientName = chatName.split('_').find(name => name !== username);
        const recipientResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${recipientName}`);
        const recipient = await recipientResponse.json();
        decryptRecipientId = recipient.id;
        publicKey = publicKeys[decryptRecipientId] || await getPublicKey(decryptRecipientId, publicKeyId);
      } else {
        // ЧУЖОЕ СООБЩЕНИЕ → шифровали на наш ключ, используем ключ ОТПРАВИТЕЛЯ
        publicKey = publicKeys[senderId] || await getPublicKey(senderId, publicKeyId);
        decryptRecipientId = userId;
      }

      setPublicKeys(prev => ({ ...prev, [decryptRecipientId]: publicKey }));

      const decryptedBytes = await decryptFileContent(
        encryptedBytes,
        nonce,
        senderId,
        publicKeyId,
        publicKeys,
        keyPair,
        decryptRecipientId  // ← ВАЖНО: для своих — recipientId
      );

      const mp3Blob = new Blob([decryptedBytes], { type: 'audio/mpeg' });
      const blobUrl = URL.createObjectURL(mp3Blob);

      setDecryptedAudioBlobs(prev => ({ ...prev, [msgId]: { blobUrl, mp3Blob } }));
      console.log(`Decrypted audio blob created: size=${mp3Blob.size}, url=${blobUrl}`);
      return blobUrl;
    } catch (error) {
      console.error(`Audio decrypt failed for ${msgId}:`, error);
      setAudioProgress(prev => ({
        ...prev,
        [msgId]: { ...prev[msgId], error: `Decrypt failed: ${error.message}` }
      }));
      throw error;
    }
  };

  const sendTypingEvent = (isTyping) => {
    if (!client || !client.connected) {
      console.warn('Cannot send typing in SecretChat: STOMP not connected');
      return;
    }

    const payload = {
      messageType: isTyping ? 'TYPING' : 'STOP_TYPING',
      sender: {
        id: userId,
        username: username,
      },
      chat: {
        id: chatId,
      },
    };

    client.publish({
      destination: `/app/typing/${chatId}`,
      body: JSON.stringify(payload),
    });

    console.log(`Typing event sent in SecretChat: ${isTyping ? 'TYPING' : 'STOP_TYPING'}`);
  };

  const openFullScreenImage = (fileUrl, fileName = 'photo.jpg') => {
    setFullScreenImage({ url: fileUrl, fileName });
  };

  const decryptImageForPreview = async (msgId, fileUrl, nonceFromMsg, publicKeyId, senderId) => {
    try {
      console.log(`Decrypting image preview for msg ${msgId}: ${fileUrl}`);

      // !!! КЛЮЧЕВОЙ ФИКС — берём правильные nonce и fileUrl из метаданных !!!
      const metadata = await fetchFileMetadata(msgId);           // ← здесь правильный nonce и fileUrl
      const correctFileUrl = metadata.fileUrl;
      const correctNonce   = metadata.nonce;

      console.log(`Using metadata nonce for file decryption: ${correctNonce}`);

      const res = await fetch(correctFileUrl);                   // используем правильный URL из metadata
      if (!res.ok) throw new Error(`Fetch failed: ${res.status}`);
      const encryptedBytes = new Uint8Array(await res.arrayBuffer());

      // Публичный ключ отправителя (как и раньше)
      let publicKey = publicKeys[senderId] || await getPublicKey(senderId, publicKeyId);

      setPublicKeys(prev => ({ ...prev, [senderId]: publicKey }));

      // Расшифровываем с правильным nonce !!!
      const decryptedBytes = await decryptFileContent(
        encryptedBytes,
        correctNonce,               // ← !!! здесь самое важное !!!
        senderId,
        publicKeyId,
        publicKeys,
        keyPair,
        userId                      // для входящих — свой id
      );

      const blob = new Blob([decryptedBytes], { type: 'image/jpeg' });
      const blobUrl = URL.createObjectURL(blob);

      setDecryptedImageBlobs(prev => ({ ...prev, [msgId]: blobUrl }));
      console.log(`Image preview decrypted: msg ${msgId} → ${blobUrl}`);

      // Обновляем и fileUrl в сообщении, чтобы плеер/скачивание использовали blob
      setMessages(prev => prev.map(m => 
        m.id === msgId ? { ...m, fileUrl: blobUrl, file_url: blobUrl } : m
      ));

      return blobUrl;
    } catch (error) {
      console.error(`Method decryptImageForPreview. Image decrypt failed for ${msgId}:`, error);
      return null;
    }
  };

  useEffect(() => {
    const imageMessages = messages.filter(msg => 
      msg.messageType === 'file' &&
      msg.fileUrl &&
      !decryptedImageBlobs[msg.id] &&
      !msg.fileUrl.startsWith('blob:') && // ещё не расшифровано
      (msg.contentType?.startsWith('image/') ||
      (msg.fileName && /\.(jpg|jpeg|png|gif|webp)$/i.test(msg.fileName)))
    );

    imageMessages.forEach(msg => {
      if (msg.nonce && msg.fileUrl) {
        console.log(`Auto-decrypting image preview: msg ${msg.id}`);
        decryptImageForPreview(
          msg.id,
          msg.fileUrl,
          msg.nonce,
          msg.publicKeyId || null,
          msg.sender.id
        );
      }
    });
  }, [messages, decryptedImageBlobs]);

  return (
    <div className="secret-chat">
      {/* Большая фиксированная иконка ассистента — только на ПК */}
      <AssistantIcon size="large" className="fixed-app-bottom-right" />
      <div className="secret-chat-name">
        <div className="secret-header-content">
          {/* Маленькая иконка ассистента — ТОЛЬКО на мобильных, слева от замка */}
          <AssistantIcon size="small" className="mobile-assistant-icon secret-mobile-assistant-left" />
          {/* Иконка замка слева */}
          <span className="secret-chat-indicator">🔒</span>

          {/* Аватар + имя + typing — центрировано */}
          <div className="secret-partner-container">
            {/* Круглый аватар с кликабельностью */}
            <div 
              className="secret-partner-avatar-wrapper"
              onClick={() => partnerId && navigate(`/user/${partnerId}`)}
              style={{ cursor: partnerId ? 'pointer' : 'default' }}
            >
              {partnerAvatar ? (
                <img
                  src={`https://unlimitedpossibilities12.org${partnerAvatar}`}
                  alt="Аватар собеседника"
                  className="secret-partner-avatar"
                />
              ) : (
                <div className="secret-partner-avatar secret-partner-avatar-placeholder">
                  {displayChatName
                    .replace('Secret Chat with ', '')
                    .charAt(0)
                    .toUpperCase() || '?'}
                </div>
              )}

              {/* Зелёная точка онлайн-статуса */}
              {partnerId && usersStatus[partnerId]?.online && (
                <span className="secret-status-indicator-online"></span>
              )}
            </div>

            {/* Имя + "печатает…" */}
            <div className="secret-partner-text">
              <div className="secret-partner-name">
                {displayChatName.replace('Secret Chat with ', '')}
              </div>
              {isTyping && (
                <div className="secret-typing-text">{typingName} печатает…</div>
              )}
            </div>
          </div>
        </div>
      </div>
      <div className="secret-message-list" ref={messageListRef}>
        {messages.map((msg, index) => (
          <div
            key={msg.id}
            className={`secret-message ${
              msg.sender.id === userId ? 'secret-current-user' : 'secret-other-user'
            } ${isFirstMessage(msg, index) ? 'first-message' : ''} ${
              msg.messageType === 'audio' ? 'secret-audio-message' : ''
            }`}
            onContextMenu={(e) => handleRightClick(e, msg)}
          >
            <div className="secret-message-wrapper">
              {shouldShowAvatar(msg, index) && (
                <div className="secret-avatar-container">
                  {msg.sender.avatar ? (
                    <div className="secret-avatar-wrapper">
                      <img
                        src={`https://unlimitedpossibilities12.org${msg.sender.avatar}`}
                        alt={`${msg.sender.username} avatar`}
                        className="secret-message-avatar"
                        onClick={() => handleAvatarClick(msg.sender.id)}
                        style={{ cursor: 'pointer' }}
                      />
                      {usersStatus[msg.sender.id]?.online && (
                        <span className="status-indicator-online-secretchat"></span>
                      )}
                    </div>
                  ) : (
                    <div className="secret-avatar-wrapper">
                      <div
                        className="secret-message-avatar"
                        onClick={() => handleAvatarClick(msg.sender.id)}
                        style={{ cursor: 'pointer' }}
                      >
                        {msg.sender.username.charAt(0).toUpperCase()}
                      </div>
                      {usersStatus[msg.sender.id]?.online && (
                        <span className="status-indicator-online-secretchat"></span>
                      )}
                    </div>
                  )}
                </div>
              )}
              <div
                className={`secret-message-content-container ${
                  shouldShowAvatar(msg, index) ? '' : 'no-avatar'
                }`}
              >
                <div className="secret-message-body">
                  <div className="secret-message-content-wrapper">
                    {msg.messageType === 'audio' && msg.fileUrl ? (
                      <div className="secret-audio-message-container">
                        <div className="secret-audio-player-wrapper">
                          <audio
                            ref={(el) => {
                              if (el && audioRefs.current[msg.id] !== el) {
                                audioRefs.current[msg.id] = el;
                                console.log(`Audio ref set for msg ${msg.id}, readyState: ${el?.readyState}`);
                              }
                            }}
                            src={decryptedAudioBlobs[msg.id]?.blobUrl || msg.fileUrl} // Fallback: decrypted или original (encrypted/temp)
                            onTimeUpdate={() => handleTimeUpdate(msg.id, audioRefs.current[msg.id])}
                            onLoadedMetadata={() => handleLoadedMetadata(msg.id, audioRefs.current[msg.id])}
                            onCanPlay={() => handleCanPlay(msg.id)}
                            onError={async (e) => {
                              const audio = audioRefs.current[msg.id];
                              if (!decryptedAudioBlobs[msg.id] && msg.nonce && msg.fileUrl) { // Retry decrypt on error, if encrypted
                                console.log(`Audio error ${msg.id}: retrying decrypt`);
                                try {
                                  const newUrl = await decryptAudioForPlayback(msg.id, msg.fileUrl, msg.nonce, msg.publicKeyId || null, msg.sender.id);
                                  audio.src = newUrl;
                                  audio.load();
                                  setAudioProgress(prev => ({ ...prev, [msg.id]: { ...prev[msg.id], error: null } }));
                                } catch (retryErr) {
                                  console.error(`Decrypt retry on error failed:`, retryErr);
                                  handleAudioError(msg.id, e);
                                }
                              } else {
                                handleAudioError(msg.id, e);
                              }
                            }}
                            onEnded={() => handleAudioEnded(msg.id)}
                            className="secret-audio-player"
                            preload="auto"
                          >
                            <source src={decryptedAudioBlobs[msg.id]?.blobUrl || msg.fileUrl} type={msg.fileUrl?.endsWith('.mp3') ? 'audio/mpeg' : 'audio/webm'} />
                            Your browser does not support the audio element.
                          </audio>
                          {audioProgress[msg.id]?.error ? (
                            <span className="secret-audio-error">{audioProgress[msg.id].error}</span>
                          ) : (
                            <div className="secret-audio-controls">
                              <button
                                className="secret-audio-play-pause"
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
                                className="secret-audio-progress-bar"
                                onClick={(e) => handleSeek(msg.id, e)}
                                onMouseDown={(e) => handleDragStart(msg.id, e)}
                                style={{ cursor: audioProgress[msg.id]?.duration === 0 ? 'not-allowed' : 'pointer' }}
                              >
                                <div
                                  className="secret-audio-progress"
                                  style={{
                                    width: `${
                                      audioProgress[msg.id]?.duration && audioProgress[msg.id]?.isReady && audioProgress[msg.id]?.duration !== 0
                                        ? (audioProgress[msg.id].currentTime / audioProgress[msg.id].duration) * 100
                                        : 0
                                    }%`,
                                  }}
                                >
                                  <div className="secret-audio-progress-thumb"></div>
                                </div>
                              </div>
                              <span className="secret-audio-time">
                                {formatAudioTime(audioProgress[msg.id]?.currentTime || 0)} /{' '}
                                {formatAudioTime(audioProgress[msg.id]?.duration || 0)}
                              </span>
                            </div>
                          )}
                        </div>
                      </div>
                    ) : (
                      <>
                        <span className="secret-message-content">
                          {msg.content}
                        </span>

                        {/* Превью изображения — используем расшифрованный blob или локальный */}
                        {msg.messageType === 'file' && msg.fileUrl && (
                          (decryptedImageBlobs[msg.id] || msg.fileUrl.startsWith('blob:')) && (
                            <div className="image-preview-container">
                              <img
                                key={decryptedImageBlobs[msg.id] || msg.fileUrl}   // ← ЭТО САМОЕ ГЛАВНОЕ
                                src={decryptedImageBlobs[msg.id] || msg.fileUrl}
                                alt="Изображение в сообщении"
                                className="message-image-preview"
                                onClick={() => openFullScreenImage(
                                  decryptedImageBlobs[msg.id] || msg.fileUrl,
                                  msg.fileName || 'photo.jpg'   // ← оригинальное имя с расширением!
                                )}
                                loading="lazy"
                                onError={(e) => {
                                  e.target.style.display = 'none';
                                  console.warn('Ошибка загрузки превью:', msg.fileUrl);
                                }}
                              />
                            </div>
                          )
                        )}

                        {/* Ссылка на скачивание — всегда */}
                        {msg.messageType === 'file' && msg.fileUrl && (
                          <a
                            href="#"
                            className="secret-file-link"
                            onClick={async (e) => {
                              e.preventDefault();
                              if (msg.sender.id === userId) {
                                await downloadOwnFile(msg);
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
                    <div className="secret-message-status">
                      {msg.sender.id === userId && (
                        <>
                          {msg.delivered && <span className="secret-delivery-indicator">✔️</span>}
                          {msg.read && <span className="secret-read-indicator">✔️</span>}
                        </>
                      )}
                    </div>
                    <div className="secret-message-timestamp">{formatTimestamp(msg.timestamp)}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        ))}
        <div ref={messagesEndRef} />
      </div>
      <div className="secret-input-container">
        <div className="secret-textarea-wrapper">
          <div className="secret-textarea-inner">
            <span
              onClick={() => fileInputRef.current.click()}
              className="secret-attach-icon"
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
              className="secret-input-message"
              rows={rows}
            />
            <input
              type="file"
              onChange={(e) => setSelectedFile(e.target.files[0])}
              className="secret-file-input"
              ref={fileInputRef}
              id="secret-file-input"
            />
            {content.length === 0 && micIconState !== 'sending' && !selectedFile && (
              <div className="secret-mic-icon-container">
                <span
                  onClick={isRecording ? stopRecording : startRecording}
                  className={`secret-mic-icon ${micIconState} ${!userId ? 'disabled' : ''}`}
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
              <div className="secret-send-icon-container">
                <span
                  onClick={sendMessage}
                  className="secret-send-icon"
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
            <div className="secret-attached-file">
              <span className="secret-attached-file-name">{selectedFile.name}</span>
              <button
                className="secret-remove-file-button"
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
          className="secret-context-menu"
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
      {/* Полноэкранное фото */}
      {fullScreenImage && (
        <div 
          className="full-screen-image-overlay"
          onClick={() => setFullScreenImage(null)}
        >
          <div 
            className="full-screen-image-content"
            onClick={(e) => e.stopPropagation()}
          >
            <button 
              className="full-screen-close-btn"
              onClick={() => setFullScreenImage(null)}
            >
              ×
            </button>

            <img 
              src={fullScreenImage.url} 
              alt="Full screen image" 
              className="full-screen-image"
            />

            <button
              className="full-screen-download-btn"
              title="Скачать фото"
              onClick={async () => {
                try {
                  const response = await fetch(fullScreenImage.url);
                  if (!response.ok) throw new Error('Не удалось загрузить фото');
                  const blob = await response.blob();

                  const url = URL.createObjectURL(blob);
                  const link = document.createElement('a');
                  link.href = url;
                  link.download = fullScreenImage.fileName;  // ← оригинальное имя с расширением!
                  document.body.appendChild(link);
                  link.click();
                  document.body.removeChild(link);
                  URL.revokeObjectURL(url);
                } catch (err) {
                  console.error('Ошибка скачивания из полноэкранного:', err);
                  toast.error('Не удалось скачать фото');
                }
              }}
            >
              ↓
            </button>
          </div>
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