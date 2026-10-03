import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { stompAuthHeaders } from '../authToken';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { toast } from 'react-toastify';
import { notify } from '../desktopNotifications';
import { useNavigate } from 'react-router-dom';
import { useUserInteraction } from './UserInteractionContext';

const PrivateMessageNotificationContext = createContext();

export const PrivateMessageNotificationProvider = ({ children }) => {
  const { userInteracted } = useUserInteraction();
  const [currentUserId, setCurrentUserId] = useState(null);
  const [currentChatId, setCurrentChatId] = useState(null);
  // Чаты, в которых есть непрочитанное. В заголовке вкладки показываем их
  // количество — как в Telegram: «(2) Messenger UP12» значит, что написали
  // из двух чатов, сколько бы сообщений там ни было.
  const [unreadChats, setUnreadChats] = useState(() => new Set());
  const messageAudioRef = useRef(null);
  const stompClientRef = useRef(null);
  const errorToastRef = useRef(null);
  const navigate = useNavigate();

  // Открыли чат — он больше не «непрочитанный»
  useEffect(() => {
    if (!currentChatId) return;
    setUnreadChats((prev) => {
      if (!prev.has(currentChatId)) return prev;
      const next = new Set(prev);
      next.delete(currentChatId);
      return next;
    });
  }, [currentChatId]);

  // Заголовок вкладки: видно, что пришло, не переключаясь на мессенджер
  useEffect(() => {
    const base = 'Messenger UP12';
    document.title = unreadChats.size > 0 ? `(${unreadChats.size}) ${base}` : base;
  }, [unreadChats]);

  useEffect(() => {
    if (!currentUserId) {
      console.log('No currentUserId, skipping private chat notifications subscription');
      return;
    }

    console.log('useWebSocket: userInteracted =', userInteracted);
    console.log('useWebSocket: messageAudioRef =', messageAudioRef);

    const stompClient = new Client({
      connectHeaders: stompAuthHeaders(),
      webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
      connectTimeout: 30000,
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        console.log('Connected to WebSocket for private chat notifications');
        if (errorToastRef.current) {
          toast.dismiss(errorToastRef.current);
          errorToastRef.current = null;
        }

        stompClient.subscribe(`/topic/notifications/${currentUserId}`, (message) => {
          const rawNotification = message.body;
          console.log('Received notification:', rawNotification);

          // Сервер помечает уведомления из секретных чатов маркером [secret] —
          // по нему открываем секретный экран, а не обычный. Пользователю
          // маркер не показываем.
          const isSecret = rawNotification.includes('[secret]');
          const notification = rawNotification.replace(/\s*\[secret\]/, '');
          const chatRoute = isSecret ? 'secret-chat' : 'private-chat';

          const chatIdMatch = notification.match(/\(chatId: (\d+)\)/);
          const chatId = chatIdMatch ? chatIdMatch[1] : null;

          if (chatId && chatId !== currentChatId) {
            if (userInteracted && messageAudioRef.current) {
              console.log('Attempting to play message audio');
              messageAudioRef.current.play()
                .then(() => console.log('Message audio played successfully'))
                .catch((error) => {
                  console.error('Failed to play message audio:', error);
                  toast.warn('Please interact with the page to enable notification sound.');
                });
            } else {
              console.log('Audio not played: userInteracted =', userInteracted, 'messageAudioRef =', messageAudioRef?.current);
            }

            // Вкладка может быть не на виду — тогда показываем уведомление системы.
            // Текст сообщения в нём не приводим: достаточно имени отправителя.
            const fromMatch = notification.match(/from: (\w+)/);
            notify(fromMatch ? fromMatch[1] : 'Messenger UP12', {
              body: isSecret ? 'New secret message' : 'New message',
              tag: `chat-${chatId}`,
              onClick: () => navigate(`/${chatRoute}/${chatId}`),
            });

            setUnreadChats((prev) => {
              if (prev.has(chatId)) return prev;
              const next = new Set(prev);
              next.add(chatId);
              return next;
            });

            const toastId = `private-message-${chatId}-${Date.now()}`;
            console.log('Calling toast.info:', { notification, toastId, currentChatId, chatId });
            toast.info(notification, {
              autoClose: false,
              toastId,
              onClick: async () => {
                console.log('Toast clicked:', toastId);
                if (messageAudioRef.current) {
                    console.log('Pausing message audio on toast click');
                    messageAudioRef.current.pause();
                    messageAudioRef.current.currentTime = 0;
                }

                if (chatId) {
                    try {
                        // Извлечение имени собеседника из текста уведомления
                        const usernameMatch = notification.match(/from: (\w+)/);
                        const recipientName = usernameMatch ? usernameMatch[1] : 'Unknown';

                        const checkResponse = await fetch(
                            `https://unlimitedpossibilities12.org/api/users/${currentUserId}/private-chats/${chatId}/exists`
                        );
                        console.log('checkResponse status:', checkResponse.status);

                        if (checkResponse.ok) {
                            const text = await checkResponse.text();
                            console.log('checkResponse text:', text);
                            const exists = text === "Chat exists in user's private chats";
                            if (exists) {
                                toast.dismiss(toastId);
                                navigate(`/${chatRoute}/${chatId}`);
                                // Отображение тоста с именем собеседника
                                toast.success(`Navigating to chat with ${recipientName}!`, {
                                    toastId: `navigate-chat-${chatId}-${Date.now()}`,
                                    autoClose: 3000,
                                });
                            } else {
                                console.error('Unexpected response for OK status:', text);
                                toast.error('Unexpected server response');
                            }
                        } else if (checkResponse.status === 404) {
                            console.log('Chat does not exist for user, adding it');
                            const addResponse = await fetch(
                                `https://unlimitedpossibilities12.org/api/users/${currentUserId}/private-chats/${chatId}/add`,
                                { method: 'POST' }
                            );
                            console.log('addResponse status:', addResponse.status, 'text:', await addResponse.text());

                            if (addResponse.ok) {
                                toast.dismiss(toastId);
                                navigate(`/${chatRoute}/${chatId}`);
                                // Отображение тоста с именем собеседника
                                toast.success(`Navigating to chat with ${recipientName}!`, {
                                    toastId: `navigate-chat-${chatId}-${Date.now()}`,
                                    autoClose: 3000,
                                });
                            } else {
                                toast.error('Failed to add chat to user');
                            }
                        } else {
                            console.log('checkResponse error text:', await checkResponse.text());
                            toast.error('Error checking chat existence');
                        }
                    } catch (error) {
                        console.error('Error handling notification click:', error);
                        toast.error('Failed to process notification');
                    }
                } else {
                    console.error('chatId not found in notification');
                    toast.error('Invalid notification format');
                }
              },
              onClose: () => {
                console.log('Toast closed:', toastId);
              },
              onOpen: () => {
                console.log('Toast opened:', toastId);
              },
            });

            // Принудительное обновление тоста
            setTimeout(() => {
              console.log('Forcing toast update:', toastId);
              toast.update(toastId, { render: notification });
            }, 100);
          } else {
            console.log('Notification ignored: chatId=', chatId, 'currentChatId=', currentChatId);
          }
        });
      },
      onWebSocketError: (error) => {
        console.warn('WebSocket temporary error in PrivateMessageNotification:', {
          error,
          timestamp: new Date().toISOString(),
          route: window.location.pathname,
        });
        // Тост не показываем, так как ошибка временная и переподключение автоматическое
      },
      onStompError: (frame) => {
        console.error('STOMP error in PrivateMessageNotification:', frame);
        if (!errorToastRef.current) {
          errorToastRef.current = toast.error('STOMP protocol error', {
            autoClose: 5000,
            onClose: () => {
              errorToastRef.current = null;
            },
          });
        }
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected in PrivateMessageNotification');
      },
    });

    stompClientRef.current = stompClient;
    stompClient.activate();

    return () => {
      console.log('Deactivating WebSocket in PrivateMessageNotification');
      if (stompClientRef.current) {
        stompClientRef.current.deactivate();
      }
    };
  }, [currentUserId, userInteracted, currentChatId, navigate]);

  return (
    <PrivateMessageNotificationContext.Provider value={{ setCurrentUserId, setCurrentChatId }}>
      <audio ref={messageAudioRef}>
        <source src="https://igor7070.github.io/Messenger/zvuk-dlya-soobsen.mp3" type="audio/mpeg" />
        Your browser does not support audio.
      </audio>
      {children}
    </PrivateMessageNotificationContext.Provider>
  );
};

export const usePrivateMessageNotification = () => {
  return useContext(PrivateMessageNotificationContext);
};