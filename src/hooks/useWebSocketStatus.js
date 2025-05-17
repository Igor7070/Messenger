import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const useWebSocketStatus = (userId, username) => {
  const [usersStatus, setUsersStatus] = useState({}); // Статусы других пользователей: { userId: { username, online } }
  const stompClientRef = useRef(null);
  const heartbeatIntervalRef = useRef(null);
  const isTabActiveRef = useRef(true); // Отслеживаем активность вкладки

  // Настройка WebSocket и STOMP
  useEffect(() => {
    if (!userId) return; // Не подключаемся, если userId отсутствует

    const socket = new SockJS('wss://unlimitedpossibilities12.org/ws/websocket');
    const client = new Client({
      webSocketFactory: () => socket,
      reconnectDelay: 5000, // Переподключение через 5 секунд
      heartbeatIncoming: 10000, // Серверный heartbeat
      heartbeatOutgoing: 10000, // Клиентский heartbeat
      onConnect: () => {
        console.log(`STOMP connected for userId: ${userId}`);
        stompClientRef.current = client;

        // Подписка на статусы пользователей
        client.subscribe('/topic/users/status', (message) => {
          const payload = JSON.parse(message.body);
          if (payload.userId !== userId) { // Игнорируем собственный статус
            setUsersStatus((prev) => ({
              ...prev,
              [payload.userId]: { username: payload.username, online: payload.online },
            }));
            console.log(`Status update: userId=${payload.userId}, online=${payload.online}`);
          }
        });

        // Отправка heartbeat, если вкладка активна
        if (isTabActiveRef.current) {
          sendHeartbeat();
          startHeartbeat();
        }
      },
      onStompError: (error) => {
        console.error('STOMP error:', error);
      },
      onWebSocketClose: () => {
        console.log(`WebSocket closed for userId: ${userId}`);
        stopHeartbeat();
      },
    });

    client.activate();

    return () => {
      stopHeartbeat();
      if (client && client.connected) {
        client.deactivate();
      }
    };
  }, [userId]);

  // Отправка heartbeat
  const sendHeartbeat = () => {
    if (stompClientRef.current && stompClientRef.current.connected) {
      const message = { userId };
      stompClientRef.current.publish({
        destination: '/app/heartbeat',
        body: JSON.stringify(message),
      });
      console.log(`Heartbeat sent for userId: ${userId}`);
    }
  };

  // Запуск heartbeat
  const startHeartbeat = () => {
    if (!heartbeatIntervalRef.current) {
      heartbeatIntervalRef.current = setInterval(() => {
        if (isTabActiveRef.current) {
          sendHeartbeat();
        } else {
          sendOfflineStatus();
        }
      }, 10000);
      console.log('Heartbeat started');
    }
  };

  // Остановка heartbeat
  const stopHeartbeat = () => {
    if (heartbeatIntervalRef.current) {
      clearInterval(heartbeatIntervalRef.current);
      heartbeatIntervalRef.current = null;
      console.log('Heartbeat stopped');
    }
  };

  // Отправка статуса offline
  const sendOfflineStatus = () => {
    if (stompClientRef.current && stompClientRef.current.connected) {
      const message = { userId };
      stompClientRef.current.publish({
        destination: '/app/setOffline',
        body: JSON.stringify(message),
      });
      console.log(`Offline status sent for userId: ${userId}`);
    }
  };

  // Отслеживание активности вкладки
  useEffect(() => {
    const handleVisibilityChange = () => {
      isTabActiveRef.current = !document.hidden;
      console.log(`Tab active: ${isTabActiveRef.current}`);
      if (isTabActiveRef.current) {
        sendHeartbeat();
        startHeartbeat();
      } else {
        sendOfflineStatus();
        stopHeartbeat();
      }
    };

    const handleFocus = () => {
      isTabActiveRef.current = true;
      console.log('Window focused');
      sendHeartbeat();
      startHeartbeat();
    };

    const handleBlur = () => {
      isTabActiveRef.current = false;
      console.log('Window blurred');
      sendOfflineStatus();
      stopHeartbeat();
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    window.addEventListener('focus', handleFocus);
    window.addEventListener('blur', handleBlur);

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      window.removeEventListener('focus', handleFocus);
      window.removeEventListener('blur', handleBlur);
      stopHeartbeat();
    };
  }, []);

  return { usersStatus };
};

export default useWebSocketStatus;