import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { toast } from 'react-toastify';
import { useUserInteraction } from './UserInteractionContext';

const CallWebSocketContext = createContext();

export const CallWebSocketProvider = ({ children }) => {
  const [incomingCall, setIncomingCall] = useState(null);
  const [currentUserId, setCurrentUserId] = useState(null);
  const stompClient = useRef(null);
  const callRequestRef = useRef(null);
  const roomSubscriptionRef = useRef(null);
  const [signalHandler, setSignalHandler] = useState(null);
  const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/melodiya_kovboy.mp3'));
  audioRef.current.loop = true;
  const { userInteracted } = useUserInteraction();

  const setIncomingCallWithLog = (value) => {
    console.log('CallWebSocketContext: Вызов setIncomingCall:', value);
    console.log('CallWebSocketContext: Текущее incomingCall:', incomingCall);
    console.log('CallWebSocketContext: Текущее callRequestRef:', callRequestRef.current);
    console.log('CallWebSocketContext: Стек вызовов:', new Error().stack);
    callRequestRef.current = value; // Сохраняем в ref перед setState
    setIncomingCall(value);
  };

  useEffect(() => {
    const storedUserId = localStorage.getItem('userId');
    if (storedUserId) {
      setCurrentUserId(Number(storedUserId));
    }
  }, []);

  useEffect(() => {
    console.log('CallWebSocketContext: Состояние incomingCall изменилось:', incomingCall);
  }, [incomingCall]);

  useEffect(() => {
    if (incomingCall && userInteracted) {
      console.log('CallWebSocketContext: Попытка воспроизведения звука, userInteracted:', userInteracted);
      audioRef.current.play().catch((error) => {
        console.error('CallWebSocketContext: Автовоспроизведение заблокировано:', error);
        toast.warn('Please interact with the page to enable call audio.');
      });
    } else {
      console.log('CallWebSocketContext: Звук не воспроизводится, incomingCall:', incomingCall, 'userInteracted:', userInteracted);
      audioRef.current.pause();
      audioRef.current.currentTime = 0;
    }
  }, [incomingCall, userInteracted]);

  useEffect(() => {
    if (!currentUserId) return;

    const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
    stompClient.current = new Client({
      webSocketFactory: () => socket,
      connectTimeout: 10000,
      reconnectDelay: 5000,
      onConnect: () => {
        console.log('CallWebSocketContext: Подключено к WebSocket для звонков, userId:', currentUserId);

        // Подписка на /topic/calls/{currentUserId} для обычных звонков
        const callsSubscription = stompClient.current.subscribe(`/topic/calls/${currentUserId}`, (message) => {
          console.log('CallWebSocketContext: Получено сообщение в /topic/calls/' + currentUserId + ':', message.body);
          handleMessage(message);
        });

        // Подписка на /topic/groupCalls/{currentUserId} для групповых звонков
        const groupCallSubscription = stompClient.current.subscribe(`/topic/groupCalls/${currentUserId}`, (message) => {
          console.log('CallWebSocketContext: Получено сообщение в /topic/groupCalls/' + currentUserId + ':', message.body);
          handleMessage(message);
        });

        return () => {
          console.log('CallWebSocketContext: Отписка от /topic/calls/' + currentUserId + ' и /topic/groupCalls/' + currentUserId);
          callsSubscription.unsubscribe();
          groupCallSubscription.unsubscribe();
        };
      },
      onWebSocketError: (error) => {
        console.error('CallWebSocketContext: Ошибка WebSocket для звонков:', error);
        toast.error('Lost connection to calls. Reconnecting...');
      },
      onDisconnect: () => {
        console.log('CallWebSocketContext: WebSocket для звонков отключён');
      },
    });
    stompClient.current.activate();

    return () => {
      if (stompClient.current) {
        if (roomSubscriptionRef.current) {
          roomSubscriptionRef.current.unsubscribe();
          console.log('CallWebSocketContext: Unsubscribed from room');
        }
        stompClient.current.deactivate();
        console.log('CallWebSocketContext: WebSocket для звонков отключён при размонтировании');
      }
    };
  }, [currentUserId]);

  const handleMessage = (message) => {
    let callRequest;
    try {
      callRequest = JSON.parse(message.body);
      console.log('CallWebSocketContext: Обработанное сообщение:', callRequest);
      console.log('CallWebSocketContext: Тип сообщения:', callRequest.type);
    } catch (error) {
      console.error('CallWebSocketContext: Ошибка парсинга сообщения:', error, message.body);
      return;
    }

    if (callRequest.type === 'endCall') {
      console.log('CallWebSocketContext: Получен сигнал endCall:', callRequest);
      if (
        callRequestRef.current?.roomId === callRequest.roomId ||
        incomingCall?.roomId === callRequest.roomId
      ) {
        console.log('CallWebSocketContext: Закрываем уведомление о звонке для roomId:', callRequest.roomId);
        const callType = incomingCall?.type || callRequestRef.current?.type || 'voice';
        const cancelMessage =
          callType === 'video' ? 'The video call was cancelled.' :
          callType === 'translate' ? 'The translate call was cancelled.' :
          callType === 'group' ? 'The group call was cancelled.' :
          'The voice call was cancelled.';
        setIncomingCallWithLog(null);
        callRequestRef.current = null;
        audioRef.current.pause();
        audioRef.current.currentTime = 0;
        console.log('CallWebSocketContext: Отправляем тост:', cancelMessage);
        toast.info(cancelMessage);
      } else {
        console.log('CallWebSocketContext: Нет активного вызова или звука:', {
          incomingCall,
          callRequestRef: callRequestRef.current,
          callRequest,
          audioSrc: audioRef.current?.src,
        });
      }
    } else if (callRequest.type === 'voice' || callRequest.type === 'video' || callRequest.type === 'translate' || callRequest.type === 'group') {
      console.log('CallWebSocketContext: Получен запрос на звонок:', callRequest);
      if (callRequest.type === 'group') {
        subscribeToRoom(callRequest.roomId);
      }
      handleIncomingCall(callRequest);
    } else {
      console.log('CallWebSocketContext: Получено сообщение с неизвестным типом:', callRequest);
    }
  };

  const handleIncomingCall = async (callRequest) => {
    console.log('CallWebSocketContext: Входящий запрос на звонок:', callRequest);
    callRequestRef.current = {
      roomId: callRequest.roomId,
      callerId: callRequest.callerId || callRequest.initiatorId,
      recipientId: callRequest.recipientId,
      type: callRequest.type,
      initiatorName: callRequest.initiatorName,
      recipientIds: callRequest.recipientIds,
    };
    console.log('CallWebSocketContext: callRequestRef установлен:', callRequestRef.current);
    setIncomingCallWithLog(callRequest);
    try {
      const callerId = callRequest.callerId || callRequest.initiatorId;
      console.log('CallWebSocketContext: Начало fetch для callerId/initiatorId:', callerId);
      const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`);
      console.log('CallWebSocketContext: fetch завершен, status:', response.status);
      if (response.ok) {
        const callerData = await response.json();
        console.log('CallWebSocketContext: Данные звонящего:', callerData);
        setIncomingCallWithLog({
          ...callRequest,
          callerName: callerData.username,
          avatar: callerData.avatar,
        });
      } else {
        console.error('CallWebSocketContext: Ошибка при получении данных звонящего:', response.status);
        setIncomingCallWithLog({
          ...callRequest,
          callerName: 'Unknown',
          avatar: null,
        });
      }
    } catch (error) {
      console.error('CallWebSocketContext: Ошибка при получении данных звонящего:', error);
      setIncomingCallWithLog({
        ...callRequest,
        callerName: 'Unknown',
        avatar: null,
      });
    }
  };

  const subscribeToRoom = (roomId) => {
    if (!roomId) {
      console.error('CallWebSocketContext: Cannot subscribe to room: roomId is null');
      return;
    }
    if (stompClient.current && stompClient.current.connected) {
      if (roomSubscriptionRef.current) {
        console.log('CallWebSocketContext: Already subscribed to /topic/room/' + roomId);
        return;
      }
      roomSubscriptionRef.current = stompClient.current.subscribe(`/topic/room/${roomId}`, (message) => {
        console.log('CallWebSocketContext: Raw message received on /topic/room/' + roomId + ':', message.body);
        try {
          const signalMessage = JSON.parse(message.body);
          console.log('CallWebSocketContext: Parsed signal:', signalMessage);
          if (signalHandler) {
            signalHandler(signalMessage);
          }
        } catch (error) {
          console.error('CallWebSocketContext: Error parsing WebSocket message for room ' + roomId + ':', error, 'Raw message:', message.body);
        }
      });
      console.log('CallWebSocketContext: Subscribed to /topic/room/' + roomId + ' at', new Date().toISOString());
    } else {
      console.warn('CallWebSocketContext: Cannot subscribe to room: STOMP client not connected', {
        connected: stompClient.current?.connected,
        roomId,
      });
    }
  };

  const setSignalHandlerCallback = (handler) => {
    setSignalHandler(() => handler);
  };

  const sendSignal = (signalMessage, roomId) => {
    if (stompClient.current && stompClient.current.connected) {
      console.log('CallWebSocketContext: Отправка сигнала:', JSON.stringify(signalMessage, null, 2));
      let destination;
      if (signalMessage.signal?.type === 'groupCallInvite') {
        destination = `/app/groupCall/${signalMessage.to}`;
      } else if (callRequestRef.current?.type === 'group') {
        destination = `/app/groupSignal/${roomId}`; // Используем /app/groupSignal/ для групповых вызовов
      } else {
        destination = `/app/signal/${roomId}`; // Для обычных вызовов
      }
      stompClient.current.publish({
        destination,
        body: JSON.stringify(signalMessage),
      });
      console.log('CallWebSocketContext: Сигнал успешно отправлен на:', destination);
    } else {
      console.error('CallWebSocketContext: STOMP client is not connected');
      toast.error('Failed to send signal. Please try again.');
    }
  };

  const acceptCall = (navigate) => {
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
    const roomId = callRequestRef.current.roomId; // Используем roomId из callRequestRef для надёжности

    const fetchUserNames = async () => {
      try {
        const [callerResponse, recipientResponse] = await Promise.all([
          fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`),
          fetch(`https://unlimitedpossibilities12.org/api/users/${recipientId}`),
        ]);

        if (callerResponse.ok && recipientResponse.ok) {
          const callerData = await callerResponse.json();
          const recipientData = await recipientResponse.json();

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
              roomId: roomId, // Явно добавляем roomId в state
              participants: [
                { id: callerId, username: callerData.username, avatar: callerData.avatar },
                { id: recipientId, username: recipientData.username, avatar: recipientData.avatar },
              ],
              initiator: { id: callerId, username: callerData.username, avatar: callerData.avatar },
            };
          } else {
            route =
              incomingCall.type === 'video' ? `/video-call/${roomId}` :
              incomingCall.type === 'translate' ? `/voice-call-translate/${roomId}` :
              `/voice-call/${roomId}`;
            state = {
              roomId: roomId, // Явно добавляем roomId
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
            incomingCall.type === 'video' ? 'Video call accepted' :
            incomingCall.type === 'translate' ? 'Translate call accepted' :
            incomingCall.type === 'group' ? 'Group call accepted' :
            'Voice call accepted'
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

    fetchUserNames();
  };

  const rejectCall = () => {
    if (!incomingCall || !callRequestRef.current) {
      console.error('CallWebSocketContext: No incoming call or callRequestRef to reject');
      toast.error('No active call to reject');
      return;
    }

    audioRef.current.pause();
    audioRef.current.currentTime = 0;
    sendSignal(
      { from: currentUserId, signal: { type: 'rejectCall' } },
      callRequestRef.current.roomId
    );
    setIncomingCallWithLog(null);
    callRequestRef.current = null;
    toast.info(
      incomingCall.type === 'video' ? 'Video call rejected' :
      incomingCall.type === 'translate' ? 'Translate call rejected' :
      incomingCall.type === 'group' ? 'Group call rejected' :
      'Voice call rejected'
    );
  };

  return (
    <CallWebSocketContext.Provider
      value={{
        incomingCall,
        acceptCall,
        rejectCall,
        setCurrentUserId,
        setSignalHandlerCallback,
      }}
    >
      {children}
    </CallWebSocketContext.Provider>
  );
};

export const useCallWebSocket = () => {
  const context = useContext(CallWebSocketContext);
  if (!context) {
    throw new Error('useCallWebSocket must be used within a CallWebSocketProvider');
  }
  return context;
};