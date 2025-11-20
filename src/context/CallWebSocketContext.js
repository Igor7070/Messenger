import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { toast } from 'react-toastify';
import { useUserInteraction } from './UserInteractionContext';

const CallWebSocketContext = createContext();

export const CallWebSocketProvider = ({ children }) => {
  const [incomingCall, setIncomingCall] = useState(null);
  const [currentUserId, setCurrentUserId] = useState(null);
  const [isCallEnded, setIsCallEnded] = useState(false); // Новое состояние
  const stompClient = useRef(null);
  const callRequestRef = useRef(null);
  const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/melodiya_kovboy.mp3'));
  const subscriptionRef = useRef(null); // Для подписки на /topic/room/{roomId}
  const signalQueue = useRef([]); // Очередь сигналов для групповых звонков
  const groupSignalHandler = useRef(null); // Для передачи сигналов в GroupCallActivity
  audioRef.current.loop = true;
  const { userInteracted } = useUserInteraction();

  const setIncomingCallWithLog = (value) => {
    console.log('CallWebSocketContext: Вызов setIncomingCall:', value);
    console.log('CallWebSocketContext: Текущее incomingCall:', incomingCall);
    console.log('CallWebSocketContext: Текущее callRequestRef:', callRequestRef.current);
    callRequestRef.current = value;
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

        // Обработка очереди сигналов
        while (signalQueue.current.length > 0 && stompClient.current.connected) {
          const signalMessage = signalQueue.current.shift();
          const roomId = signalMessage.roomId || callRequestRef.current?.roomId;
          if (roomId) {
            stompClient.current.publish({
              destination: `/app/groupSignal/${roomId}`,
              body: JSON.stringify(signalMessage),
            });
            console.log('CallWebSocketContext: Signal sent from queue to /app/groupSignal/' + roomId + ':', signalMessage);
          }
        }
      },
      onWebSocketError: (error) => {
        console.error('CallWebSocketContext: Ошибка WebSocket для звонков:', error);
        toast.error('Lost connection to calls. Reconnecting...');
      },
      onDisconnect: () => {
        console.log('CallWebSocketContext: WebSocket для звонков отключён');
        setIncomingCallWithLog(null);
        callRequestRef.current = null;
        if (subscriptionRef.current) {
          subscriptionRef.current.unsubscribe();
          console.log('CallWebSocketContext: Отписка от /topic/room/' + callRequestRef.current?.roomId);
          subscriptionRef.current = null;
        }
        setIsCallEnded(true);
      },
    });
    stompClient.current.activate();

    return () => {
      if (stompClient.current) {
        stompClient.current.deactivate();
        console.log('CallWebSocketContext: WebSocket для звонков отключён при размонтировании');
        if (subscriptionRef.current) {
          subscriptionRef.current.unsubscribe();
          console.log('CallWebSocketContext: Отписка от /topic/room/' + callRequestRef.current?.roomId);
          subscriptionRef.current = null;
        }
        setIsCallEnded(true);
      }
    };
  }, [currentUserId]);

  const subscribeToRoom = (roomId, onSuccess, onError) => {
    if (!roomId) {
      console.error('CallWebSocketContext: Cannot subscribe to room: roomId is null');
      onError?.(new Error('Room ID is null'));
      return () => {};
    }

    if (isCallEnded) {
      console.log(`CallWebSocketContext: Call already ended, skipping subscription to /topic/room/${roomId}`);
      return () => {};
    }

    let reconnectAttempts = 0;
    const maxReconnectDelay = 10000; // Максимальная задержка 10 секунд
    let hasNotifiedError = false; // Флаг для предотвращения множественных тостов

    const trySubscribe = () => {
      if (!stompClient.current || !stompClient.current.connected) {
        const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), maxReconnectDelay);
        console.warn(`CallWebSocketContext: WebSocket not connected, retrying in ${delay}ms, attempt ${reconnectAttempts + 1}`);
        if (!hasNotifiedError) {
          hasNotifiedError = true;
          onError?.(new Error('WebSocket subscription failed'));
        }
        reconnectAttempts++;
        setTimeout(trySubscribe, delay);
        return;
      }

      subscriptionRef.current = stompClient.current.subscribe(`/topic/room/${roomId}`, (message) => {
        if (isCallEnded) {
          console.log(`CallWebSocketContext: Ignoring message on /topic/room/${roomId} as call is ended`);
          return;
        }
        console.log('CallWebSocketContext: Raw message received on /topic/room/' + roomId + ':', message.body);
        try {
          const signalMessage = JSON.parse(message.body);
          console.log('CallWebSocketContext: Parsed signal:', signalMessage);
          if (groupSignalHandler.current) {
            groupSignalHandler.current(signalMessage);
          }
        } catch (error) {
          console.error('CallWebSocketContext: Error parsing WebSocket message for room ' + roomId + ':', error, 'Raw message:', message.body);
        }
      }, (error) => {
        console.error('CallWebSocketContext: Subscription error for /topic/room/' + roomId + ':', error);
        const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), maxReconnectDelay);
        reconnectAttempts++;
        setTimeout(trySubscribe, delay);
      });

      console.log('CallWebSocketContext: Subscribed to /topic/room/' + roomId);
      hasNotifiedError = false; // Сбрасываем флаг после успешной подписки
      onSuccess?.();

      const unsubscribeTimeout = setTimeout(() => {
        if (subscriptionRef.current) {
          subscriptionRef.current.unsubscribe();
          console.log(`CallWebSocketContext: Unsubscribed from /topic/room/${roomId} due to timeout`);
          subscriptionRef.current = null;
        }
      }, 900000);

      return () => {
        clearTimeout(unsubscribeTimeout);
        if (subscriptionRef.current) {
          subscriptionRef.current.unsubscribe();
          console.log(`CallWebSocketContext: Unsubscribed from /topic/room/${roomId}`);
          subscriptionRef.current = null;
        }
      };
    };

    return trySubscribe();
  };

  const handleMessage = (message) => {
    if (isCallEnded) {
      console.log('CallWebSocketContext: Ignoring message as call is ended:', message.body);
      return;
    }

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
        if (subscriptionRef.current) {
          subscriptionRef.current.unsubscribe();
          console.log('CallWebSocketContext: Отписка от /topic/room/' + callRequest.roomId);
          subscriptionRef.current = null;
        }
        setIsCallEnded(true); // Устанавливаем isCallEnded
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
      handleIncomingCall(callRequest);
    } else {
      console.log('CallWebSocketContext: Получено сообщение с неизвестным типом:', callRequest);
    }
  };

  const handleIncomingCall = async (callRequest) => {
    if (isCallEnded) {
      console.log('CallWebSocketContext: Ignoring incoming call as call is ended:', callRequest);
      return;
    }

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

    if (callRequest.type === 'group') {
      subscribeToRoom(callRequest.roomId, () => {
        console.log('CallWebSocketContext: Successfully subscribed to /topic/room/' + callRequest.roomId);
      }, (error) => {
        console.error('CallWebSocketContext: Failed to subscribe to /topic/room/' + callRequest.roomId + ':', error);
        toast.error('Failed to connect to group call server');
        setIncomingCallWithLog(null);
        callRequestRef.current = null;
        setIsCallEnded(true);
      });
    }

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

  const sendSignal = (signalMessage, roomId, to = null) => {
    if (!stompClient.current) {
      console.error('CallWebSocketContext: STOMP client is not initialized');
      signalQueue.current.push({ signalMessage, roomId, to });
      return;
    }
    if (stompClient.current && stompClient.current.connected) {
      console.log('CallWebSocketContext: Отправка сигнала:', JSON.stringify(signalMessage, null, 2));
      let destination;
      if (signalMessage.type === 'group' && to) {
        destination = `/app/groupCall/${to}`; // Восстанавливаем отправку groupCallInvite на /app/groupCall/{to}
        console.log('CallWebSocketContext: Отправка groupCallInvite на:', destination);
      } else if (
        signalMessage.signal?.type === 'group' ||
        signalMessage.signal?.type === 'acceptCall' ||
        signalMessage.signal?.type === 'ready' ||
        signalMessage.signal?.type === 'offer' ||
        signalMessage.signal?.type === 'answer' ||
        signalMessage.signal?.type === 'candidate' ||
        signalMessage.signal?.type === 'endCall' ||
        signalMessage.signal?.type === 'rejectCall' ||
        callRequestRef.current?.type === 'group'
      ) {
        destination = `/app/groupSignal/${roomId}`;
        console.log('CallWebSocketContext: Отправка группового сигнала на:', destination);
      } else {
        destination = `/app/signal/${roomId}`;
        console.log('CallWebSocketContext: Отправка обычного сигнала на:', destination);
      }
      stompClient.current.publish({
        destination,
        body: JSON.stringify(signalMessage),
      });
      console.log('CallWebSocketContext: Сигнал успешно отправлен на:', destination);
    } else {
      console.warn('CallWebSocketContext: STOMP client is not connected, queuing signal:', signalMessage);
      signalQueue.current.push({ signalMessage, roomId, to });
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
    const roomId = callRequestRef.current.roomId;

    const fetchUserNames = async () => {
      try {
        const [callerResponse, recipientResponse] = await Promise.all([
          fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`),
          fetch(`https://unlimitedpossibilities12.org/api/users/${recipientId}`),
        ]);

        if (callerResponse.ok && recipientResponse.ok) {
          const callerData = await callerResponse.json();
          const recipientData = await recipientResponse.json();

          // Отправляем сигналы для групповых звонков
          if (incomingCall.type === 'group') {
            sendSignal(
              { from: currentUserId, signal: { type: 'acceptCall' } },
              roomId
            );
            sendSignal(
              { from: currentUserId, to: incomingCall.initiatorId, signal: { type: 'ready' } },
              roomId
            );
          } else {
            sendSignal(
              { from: currentUserId, signal: { type: 'acceptCall' } },
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
                  .filter((id) => id !== String(recipientId))
                  .map((id) => ({ id: Number(id), username: 'Unknown', avatar: null })),
              ],
              initiator: { id: callerId, username: callerData.username, avatar: callerData.avatar },
            };
          } else {
            route =
              incomingCall.type === 'video' ? `/video-call/${roomId}` :
              incomingCall.type === 'translate' ? `/voice-call-translate/${roomId}` :
              incomingCall.type === 'voice_secure' ? `/secure-call/${roomId}` :  // ← ЭТА СТРОЧКА!
              `/voice-call/${roomId}`;
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

    // NEW: Сохраняем отклонённый звонок
    const saveRejectedCall = async () => {
      try {
        const callData = {
          caller: incomingCall.callerId || incomingCall.initiatorId,
          direction: 'incoming',
          status: 'rejected',
          callType: incomingCall.type === 'video' ? 'video' :
                    incomingCall.type === 'translate' ? 'voice_with_translate' :
                    incomingCall.type === 'group' ? 'group' :
                    'voice',
          timestamp: new Date().toLocaleString('en-US', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            timeZone: 'Europe/Kiev',
            hour12: false,
          }).replace(/(\d+)\/(\d+)\/(\d+), (\d+:\d+:\d+)/, '$3-$1-$2T$4'),
        };

        // Для группового звонка добавляем участников
        if (incomingCall.type === 'group') {
          callData.participants = [
            incomingCall.initiatorId,
            ...incomingCall.recipientIds.map(id => String(id)),
          ];
        } else {
          callData.receiver = currentUserId;
        }

        await fetch(`https://unlimitedpossibilities12.org/api/calls?userId=${currentUserId}`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(callData),
        });
        console.log('CallWebSocketContext: Rejected call saved to server with callType:', callData.callType);
      } catch (error) {
        console.error('CallWebSocketContext: Error saving rejected call:', error);
        toast.error('Failed to save rejected call', { toastId: 'save-rejected-call-error' });
      }
    };

    // NEW: Вызываем сохранение перед отправкой сигнала
    saveRejectedCall();

    sendSignal(
      { from: currentUserId, signal: { type: 'rejectCall' } },
      callRequestRef.current.roomId
    );
    setIncomingCallWithLog(null);
    callRequestRef.current = null;
    if (subscriptionRef.current) {
      subscriptionRef.current.unsubscribe();
      console.log('CallWebSocketContext: Отписка от /topic/room/' + callRequestRef.current.roomId);
      subscriptionRef.current = null;
    }
    setIsCallEnded(true); // Устанавливаем isCallEnded
    toast.info(
      incomingCall.type === 'video' ? 'Video call rejected' :
      incomingCall.type === 'translate' ? 'Translate call rejected' :
      incomingCall.type === 'group' ? 'Group call rejected' :
      'Voice call rejected'
    );
  };

  const setGroupSignalHandler = (handler) => {
    groupSignalHandler.current = handler;
  };

  return (
    <CallWebSocketContext.Provider
      value={{
        incomingCall,
        acceptCall,
        rejectCall,
        setCurrentUserId,
        sendSignal,
        setGroupSignalHandler,
        subscribeToRoom,
        setIsCallEnded, // Экспортируем setIsCallEnded
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