import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { stompAuthHeaders } from '../authToken';
import { Client } from '@stomp/stompjs';

const UserStatusContext = createContext();

export const UserStatusProvider = ({ children }) => {
  const [usersStatus, setUsersStatus] = useState({});
  const stompClientRef = useRef(null);
  const heartbeatIntervalRef = useRef(null);
  const isTabActiveRef = useRef(true);
  const userIdRef = useRef(null);
  const usernameRef = useRef('');

  const initializeWebSocket = (userId, username) => {
    console.log('initializeWebSocket: userId=', userId, 'username=', username);
    if (!userId) {
      console.log('No userId, skipping WebSocket initialization');
      return;
    }

    userIdRef.current = userId;
    usernameRef.current = username;

    if (stompClientRef.current && stompClientRef.current.connected) {
      console.log('WebSocket already connected');
      return;
    }

    const client = new Client({
      connectHeaders: stompAuthHeaders(),
      brokerURL: 'wss://unlimitedpossibilities12.org/ws/websocket',
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
            setUsersStatus((prev) => ({
              ...prev,
              [payload.userId]: { username: payload.username, online: payload.online },
            }));
            console.log(`Status update: userId=${payload.userId}, username=${payload.username}, online=${payload.online}`);
          } catch (error) {
            console.error('Error parsing status message:', error, message.body);
          }
        });

        if (isTabActiveRef.current) {
          //sendHeartbeat();
          startHeartbeat();
          sendOnlineStatus();
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
    client.activate();
  };

  const disconnectWebSocket = () => {
    stopHeartbeat();
    if (stompClientRef.current && stompClientRef.current.connected) {
      sendOfflineStatus();
      stompClientRef.current.deactivate();
    }
    stompClientRef.current = null;
    userIdRef.current = null;
    usernameRef.current = '';
    console.log('WebSocket disconnected');
  };

  const sendHeartbeat = () => {
    if (stompClientRef.current && stompClientRef.current.connected && userIdRef.current) {
      const message = { userId: userIdRef.current, username: usernameRef.current };
      stompClientRef.current.publish({
        destination: '/app/heartbeat',
        body: JSON.stringify(message),
      });
      console.log(`Heartbeat sent for userId: ${userIdRef.current}, username: ${usernameRef.current}`);
    } else {
      console.log('Cannot send heartbeat: STOMP client not connected or no userId');
    }
  };

  const startHeartbeat = () => {
    if (!heartbeatIntervalRef.current) {
      heartbeatIntervalRef.current = setInterval(() => {
        if (isTabActiveRef.current) {
          sendHeartbeat();
          sendOnlineStatus();
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

  const sendOnlineStatus = () => {
    if (stompClientRef.current && stompClientRef.current.connected && userIdRef.current) {
      const message = { userId: userIdRef.current, username: usernameRef.current };
      stompClientRef.current.publish({
        destination: '/app/setOnline',
        body: JSON.stringify(message),
      });
      console.log(`Online status sent for userId: ${userIdRef.current}, username: ${usernameRef.current}`);
    } else {
      console.log('Cannot send online status: STOMP client not connected or no userId');
    }
  };

  const sendOfflineStatus = () => {
    if (stompClientRef.current && stompClientRef.current.connected && userIdRef.current) {
      const message = { userId: userIdRef.current, username: usernameRef.current };
      stompClientRef.current.publish({
        destination: '/app/setOffline',
        body: JSON.stringify(message),
      });
      console.log(`Offline status sent for userId: ${userIdRef.current}, username: ${usernameRef.current}`);
    } else {
      console.log('Cannot send offline status: STOMP client not connected or no userId');
    }
  };

  useEffect(() => {
    const handleVisibilityChange = () => {
      isTabActiveRef.current = !document.hidden;
      console.log(`Tab active: ${isTabActiveRef.current}`);
      if (isTabActiveRef.current) {
        //sendHeartbeat();
        startHeartbeat();
        sendOnlineStatus();
      } else {
        sendOfflineStatus();
        stopHeartbeat();
      }
    };

    const handleFocus = () => {
      isTabActiveRef.current = true;
      console.log('Window focused');
      //sendHeartbeat();
      startHeartbeat();
      sendOnlineStatus();
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
      if (stompClientRef.current && stompClientRef.current.connected) {
        sendOfflineStatus();
        stompClientRef.current.deactivate();
      }
    };
  }, []);

  return (
    <UserStatusContext.Provider
      value={{
        usersStatus,
        initializeWebSocket,
        disconnectWebSocket,
        sendHeartbeat,
        sendOnlineStatus,
        sendOfflineStatus,
      }}
    >
      {children}
    </UserStatusContext.Provider>
  );
};

export const useUserStatusContext = () => {
  const context = useContext(UserStatusContext);
  if (!context) {
    throw new Error('useUserStatusContext must be used within a UserStatusProvider');
  }
  return context;
};