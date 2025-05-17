import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const useWebSocketStatus = (userId, username) => {
  const [usersStatus, setUsersStatus] = useState({});
  const stompClientRef = useRef(null);
  const heartbeatIntervalRef = useRef(null);
  const isTabActiveRef = useRef(true);

  useEffect(() => {
    console.log('useWebSocketStatus: userId=', userId, 'username=', username); // Отладка
    if (!userId) {
      console.log('useWebSocketStatus: No userId, skipping');
      return;
    }

    const socket = new SockJS('https://unlimitedpossibilities12.org/ws/websocket');
    const client = new Client({
      webSocketFactory: () => socket,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        console.log(`STOMP connected for userId: ${userId}`);
        stompClientRef.current = client;

        client.subscribe('/topic/users/status', (message) => {
          try {
            const payload = JSON.parse(message.body);
            console.log('Received status update:', payload);
            if (payload.userId !== userId) {
              setUsersStatus((prev) => ({
                ...prev,
                [payload.userId]: { username: payload.username, online: payload.online },
              }));
              console.log(`Status update: userId=${payload.userId}, online=${payload.online}`);
            } else {
              console.log(`Ignoring own status: userId=${payload.userId}`);
            }
          } catch (error) {
            console.error('Error parsing status message:', error, message.body);
          }
        });

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
      onWebSocketError: (error) => {
        console.error('WebSocket error:', error);
      },
    });

    console.log('Activating STOMP client...');
    try {
      client.activate();
    } catch (error) {
      console.error('Failed to activate STOMP client:', error);
    }

    return () => {
      console.log('Cleaning up STOMP client...');
      stopHeartbeat();
      if (client && client.connected) {
        try {
          client.deactivate();
        } catch (error) {
          console.error('Failed to deactivate STOMP client:', error);
        }
      }
    };
  }, [userId]);

  const sendHeartbeat = () => {
    if (stompClientRef.current && stompClientRef.current.connected) {
      const message = { userId, username: username || 'Unknown' };
      stompClientRef.current.publish({
        destination: '/app/heartbeat',
        body: JSON.stringify(message),
      });
      console.log(`Heartbeat sent for userId: ${userId}, username: ${username || 'Unknown'}`);
    } else {
      console.log('Cannot send heartbeat: STOMP client not connected');
    }
  };

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

  const stopHeartbeat = () => {
    if (heartbeatIntervalRef.current) {
      clearInterval(heartbeatIntervalRef.current);
      heartbeatIntervalRef.current = null;
      console.log('Heartbeat stopped');
    }
  };

  const sendOfflineStatus = () => {
    if (stompClientRef.current && stompClientRef.current.connected) {
      const message = { userId, username: username || 'Unknown' };
      stompClientRef.current.publish({
        destination: '/app/setOffline',
        body: JSON.stringify(message),
      });
      console.log(`Offline status sent for userId: ${userId}, username: ${username || 'Unknown'}`);
    } else {
      console.log('Cannot send offline status: STOMP client not connected');
    }
  };

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