import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { toast } from 'react-toastify';

const CallWebSocketContext = createContext();

export const useCallWebSocket = () => useContext(CallWebSocketContext);

export const CallWebSocketProvider = ({ children }) => {
  const stompClient = useRef(null);
  const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));
  const [incomingCall, setIncomingCall] = useState(null);
  const [userInteracted, setUserInteracted] = useState(false);
  const callRequestRef = useRef(null);
  const currentUserId = localStorage.getItem('userId');
  const signalHandlerRef = useRef(null);

  const setSignalHandlerCallback = (handler) => {
    console.log('CallWebSocketContext: Setting signal handler callback at', new Date().toISOString());
    signalHandlerRef.current = handler;
  };

  const setIncomingCallWithLog = (call) => {
    console.log('CallWebSocketContext: Вызов setIncomingCall:', call);
    console.log('CallWebSocketContext: Текущее incomingCall:', incomingCall);
    console.log('CallWebSocketContext: Текущее callRequestRef:', callRequestRef.current);
    console.error('CallWebSocketContext: Стек вызовов:', new Error().stack);
    setIncomingCall(call);
  };

  const initializeWebSocket = () => {
    if (stompClient.current && stompClient.current.active) {
      console.log('CallWebSocketContext: WebSocket already active');
      return;
    }

    stompClient.current = new Client({
      webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        console.log('CallWebSocketContext: WebSocket connected at', new Date().toISOString());
        if (currentUserId) {
          stompClient.current.subscribe(`/topic/groupCalls/${currentUserId}`, (message) => {
            console.log('CallWebSocketContext: Получено сообщение в /topic/groupCalls/' + currentUserId + ':', message.body);
            try {
              const callRequest = JSON.parse(message.body);
              console.log('CallWebSocketContext: Обработанное сообщение:', callRequest);
              console.log('CallWebSocketContext: Тип сообщения:', callRequest.type);

              if (callRequest.type === 'group') {
                console.log('CallWebSocketContext: Получен запрос на звонок:', callRequest);
                console.log('CallWebSocketContext: Входящий запрос на звонок:', callRequest);
                callRequestRef.current = {
                  roomId: callRequest.roomId,
                  callerId: callRequest.initiatorId,
                  recipientId: undefined,
                  type: callRequest.type,
                  initiatorName: callRequest.initiatorName,
                };
                console.log('CallWebSocketContext: callRequestRef установлен:', callRequestRef.current);

                // Подписываемся на комнату
                stompClient.current.subscribe(`/topic/room/${callRequest.roomId}`, (roomMessage) => {
                  console.log('CallWebSocketContext: Raw message received on /topic/room/' + callRequest.roomId + ':', roomMessage.body);
                  try {
                    const signalMessage = JSON.parse(roomMessage.body);
                    console.log('CallWebSocketContext: Parsed signal:', signalMessage);
                    if (signalHandlerRef.current) {
                      signalHandlerRef.current(signalMessage);
                    } else {
                      console.warn('CallWebSocketContext: No signal handler set for message:', signalMessage);
                    }
                  } catch (error) {
                    console.error('CallWebSocketContext: Error parsing WebSocket message:', error);
                  }
                });
                console.log('CallWebSocketContext: Subscribed to /topic/room/' + callRequest.roomId + ' at', new Date().toISOString());

                fetch(`https://unlimitedpossibilities12.org/api/users/${callRequest.initiatorId}`, {
                  headers: { 'Authorization': `Bearer ${localStorage.getItem('token')}` },
                })
                  .then((response) => {
                    console.log('CallWebSocketContext: fetch завершен, status:', response.status);
                    if (response.ok) {
                      return response.json();
                    }
                    throw new Error('Failed to fetch caller data');
                  })
                  .then((callerData) => {
                    console.log('CallWebSocketContext: Данные звонящего:', callerData);
                    setIncomingCallWithLog({
                      ...callRequest,
                      callerData,
                    });
                  })
                  .catch((error) => {
                    console.error('CallWebSocketContext: Ошибка при получении данных звонящего:', error);
                    toast.error('Failed to fetch caller data');
                  });
              }
            } catch (error) {
              console.error('CallWebSocketContext: Ошибка при разборе сообщения:', error);
            }
          });
        }
      },
      onStompError: (frame) => {
        console.error('CallWebSocketContext: STOMP error:', frame);
        toast.error('STOMP protocol error.');
      },
      onWebSocketError: (error) => {
        console.error('CallWebSocketContext: WebSocket error:', error);
        toast.error('WebSocket connection error.');
      },
      onDisconnect: () => {
        console.log('CallWebSocketContext: WebSocket disconnected');
      },
    });

    stompClient.current.activate();
  };

  useEffect(() => {
    const handleInteraction = () => {
      setUserInteracted(true);
      window.removeEventListener('click', handleInteraction);
      window.removeEventListener('keydown', handleInteraction);
    };

    window.addEventListener('click', handleInteraction);
    window.addEventListener('keydown', handleInteraction);

    initializeWebSocket();

    return () => {
      window.removeEventListener('click', handleInteraction);
      window.removeEventListener('keydown', handleInteraction);
      if (stompClient.current) {
        stompClient.current.deactivate();
      }
    };
  }, []);

  useEffect(() => {
    console.log('CallWebSocketContext: Состояние incomingCall изменилось:', incomingCall);
    if (incomingCall && userInteracted) {
      console.log('CallWebSocketContext: Попытка воспроизведения звука, userInteracted:', userInteracted);
      audioRef.current.loop = true;
      audioRef.current.play().catch((error) => {
        console.error('CallWebSocketContext: Ошибка воспроизведения звука:', error);
      });
    } else {
      console.log('CallWebSocketContext: Звук не воспроизводится, incomingCall:', incomingCall, 'userInteracted:', userInteracted);
      audioRef.current.pause();
      audioRef.current.currentTime = 0;
    }
  }, [incomingCall, userInteracted]);

  const sendSignal = (signalData, roomId) => {
    if (!stompClient.current) {
      console.error('CallWebSocketContext: STOMP client is not initialized');
      return;
    }
    if (stompClient.current.connected) {
      stompClient.current.publish({
        destination: `/app/groupSignal/${roomId}`,
        body: JSON.stringify(signalData),
      });
      console.log('CallWebSocketContext: Сигнал успешно отправлен на: /app/groupSignal/' + roomId, signalData);
    } else {
      console.error('CallWebSocketContext: STOMP client not connected for room:', roomId);
      toast.error('Failed to send signal: WebSocket not connected');
    }
  };

  const acceptCall = async (navigate) => {
    if (!incomingCall || !callRequestRef.current) {
      console.error('CallWebSocketContext: No incoming call or callRequestRef to accept');
      toast.error('No active call to accept');
      return;
    }

    audioRef.current.pause();
    audioRef.current.currentTime = 0;
    console.log('CallWebSocketContext: Функция acceptCall работает...');
    console.log('CallWebSocketContext: Входящий звонок принят:', incomingCall);

    const callerId = incomingCall.callerId || incomingCall.initiatorId;
    const recipientId = currentUserId;
    const roomId = callRequestRef.current.roomId;

    try {
      const [callerResponse, recipientResponse] = await Promise.all([
        fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`, {
          headers: { 'Authorization': `Bearer ${localStorage.getItem('token')}` },
        }),
        fetch(`https://unlimitedpossibilities12.org/api/users/${recipientId}`, {
          headers: { 'Authorization': `Bearer ${localStorage.getItem('token')}` },
        }),
      ]);

      if (callerResponse.ok && recipientResponse.ok) {
        const callerData = await callerResponse.json();
        const recipientData = await recipientResponse.json();

        // Ожидаем подключения WebSocket
        let attempts = 0;
        const maxAttempts = 50;
        while (!stompClient.current?.connected && attempts < maxAttempts) {
          console.log('CallWebSocketContext: Waiting for WebSocket connection in acceptCall... Attempt:', attempts + 1);
          await new Promise((resolve) => setTimeout(resolve, 100));
          attempts++;
        }

        if (!stompClient.current?.connected) {
          console.error('CallWebSocketContext: WebSocket not connected after', maxAttempts, 'attempts');
          toast.error('Failed to connect to WebSocket');
          return;
        }

        // Отправляем сигналы
        sendSignal(
          { from: currentUserId, signal: { type: 'acceptCall' } },
          roomId
        );
        if (incomingCall.type === 'group') {
          sendSignal(
            { from: currentUserId, to: incomingCall.initiatorId, signal: { type: 'ready' } },
            roomId
          );
        }

        setIncomingCallWithLog(null);
        callRequestRef.current = null;

        let route;
        let state;
        if (incomingCall.type === 'group') {
          route = '/group-call';
          state = {
            roomId: roomId,
            participants: [
              { id: callerId, username: callerData.username, avatar: callerData.avatar },
              { id: recipientId, username: recipientData.username, avatar: recipientData.avatar },
              ...(incomingCall.recipientIds || [])
                .filter((id) => id !== recipientId)
                .map((id) => ({ id: Number(id), username: `User ${id}`, avatar: null })),
            ],
            initiator: { id: callerId, username: callerData.username, avatar: callerData.avatar },
          };
        } else {
          route =
            incomingCall.type === 'video'
              ? `/video-call/${roomId}`
              : incomingCall.type === 'translate'
              ? `/voice-call-translate/${roomId}`
              : `/voice-call/${roomId}`;
          state = {
            roomId: roomId,
            incomingCall,
            isCaller: false,
            currentUserId,
            callerName: callerData.username,
            recipientName: recipientData.username,
          };
        }

        console.log('CallWebSocketContext: Navigating to:', route, 'with state:', state);
        navigate(route, { state });
        toast.success(
          incomingCall.type === 'video'
            ? 'Video call accepted'
            : incomingCall.type === 'translate'
            ? 'Translate call accepted'
            : incomingCall.type === 'group'
            ? 'Group call accepted'
            : 'Voice call accepted'
        );
      } else {
        console.error('CallWebSocketContext: Ошибка при получении имен пользователей');
        toast.error('Failed to fetch user data');
      }
    } catch (error) {
      console.error('CallWebSocketContext: Ошибка при получении имен пользователей:', error);
      toast.error('Error fetching user data');
    }
  };

  const rejectCall = () => {
    if (!incomingCall || !callRequestRef.current) {
      console.error('CallWebSocketContext: No incoming call or callRequestRef to reject');
      toast.error('No active call to reject');
      return;
    }

    audioRef.current.pause();
    audioRef.current.currentTime = 0;

    const roomId = callRequestRef.current.roomId;
    const signalData = {
      from: currentUserId,
      to: incomingCall.initiatorId || incomingCall.callerId,
      signal: { type: 'rejectCall' },
    };

    sendSignal(signalData, roomId);
    setIncomingCallWithLog(null);
    callRequestRef.current = null;
    toast.info('Call rejected');
  };

  const endCall = (roomId, recipientId, callerId) => {
    if (!stompClient.current) {
      console.error('CallWebSocketContext: STOMP client is not initialized');
      return;
    }
    if (stompClient.current.connected) {
      const endCallMessage = {
        type: 'endCall',
        roomId: roomId,
        initiatorId: callerId,
        recipientId: recipientId,
      };
      stompClient.current.publish({
        destination: `/app/call/${recipientId}`,
        body: JSON.stringify(endCallMessage),
      });
      console.log('CallWebSocketContext: Sent endCall to /app/call/' + recipientId + ':', endCallMessage);
    }
    setIncomingCallWithLog(null);
    callRequestRef.current = null;
  };

  return (
    <CallWebSocketContext.Provider
      value={{
        incomingCall,
        acceptCall,
        rejectCall,
        endCall,
        setSignalHandlerCallback,
      }}
    >
      {children}
    </CallWebSocketContext.Provider>
  );
};