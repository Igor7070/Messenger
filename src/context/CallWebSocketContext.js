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
  const audioRef = useRef(new Audio('https://igor7070.github.io/Messenger/melodiya_kovboy.mp3'));
  audioRef.current.loop = true;
  const { userInteracted } = useUserInteraction();

  // Логирование для отладки
  const setIncomingCallWithLog = (value) => {
    console.log('CallWebSocketContext: Вызов setIncomingCall:', value);
    console.log('CallWebSocketContext: Текущее incomingCall:', incomingCall);
    console.log('CallWebSocketContext: Текущее callRequestRef:', callRequestRef.current);
    console.log('CallWebSocketContext: Стек вызовов:', new Error().stack);
    setIncomingCall(value);
  };

  // Инициализация currentUserId из localStorage
  useEffect(() => {
    const storedUserId = localStorage.getItem('userId');
    if (storedUserId) {
      setCurrentUserId(Number(storedUserId));
    }
  }, []);

  // Логирование изменений incomingCall
  useEffect(() => {
    console.log('CallWebSocketContext: Состояние incomingCall изменилось:', incomingCall);
  }, [incomingCall]);

  // Управление звуком
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

  // Инициализация WebSocket для звонков
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

        // Подписка на /topic/groupCall/{currentUserId} для групповых звонков
        const groupCallSubscription = stompClient.current.subscribe(`/topic/groupCall/${currentUserId}`, (message) => {
          console.log('CallWebSocketContext: Получено сообщение в /topic/groupCall/' + currentUserId + ':', message.body);
          handleMessage(message);
        });

        return () => {
          console.log('CallWebSocketContext: Отписка от /topic/calls/' + currentUserId + ' и /topic/groupCall/' + currentUserId);
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
        stompClient.current.deactivate();
        console.log('CallWebSocketContext: WebSocket для звонков отключён при размонтировании');
      }
    };
  }, [currentUserId]);

  // Обработка сообщений (общая для /topic/calls и /topic/groupCall)
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
      handleIncomingCall(callRequest);
    } else {
      console.log('CallWebSocketContext: Получено сообщение с неизвестным типом:', callRequest);
    }
  };

  // Обработка входящего звонка
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

  // Отправка сигнала
  const sendSignal = (signalMessage, roomId) => {
    if (stompClient.current && stompClient.current.connected) {
      console.log('CallWebSocketContext: Отправка сигнала:', JSON.stringify(signalMessage, null, 2));
      const destination = signalMessage.signal.type === 'groupCallInvite' ? `/app/groupCall/${signalMessage.to}` : `/app/signal/${roomId}`;
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

  // Принятие звонка
  const acceptCall = (navigate) => {
    if (!incomingCall) return;

    audioRef.current.pause();
    audioRef.current.currentTime = 0;
    console.log('CallWebSocketContext: Функция acceptCall работает...');
    console.log('CallWebSocketContext: Входящий звонок принят:', incomingCall);

    const callerId = incomingCall.callerId || incomingCall.initiatorId;
    const recipientId = currentUserId;

    const fetchUserNames = async () => {
      try {
        const [callerResponse, recipientResponse] = await Promise.all([
          fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`),
          fetch(`https://unlimitedpossibilities12.org/api/users/${recipientId}`),
        ]);

        if (callerResponse.ok && recipientResponse.ok) {
          const callerData = await callerResponse.json();
          const recipientData = await recipientResponse.json();

          sendSignal(
            { from: currentUserId, signal: { type: 'acceptCall' } },
            incomingCall.roomId
          );
          if (incomingCall.type === 'group') {
            sendSignal(
              { from: currentUserId, to: incomingCall.initiatorId, signal: { type: 'ready' } },
              incomingCall.roomId
            );
          }

          setIncomingCallWithLog(null);
          callRequestRef.current = null;

          let route;
          let state;
          if (incomingCall.type === 'group') {
            route = `/group-call`;
            state = {
              participants: [
                { id: callerId, username: callerData.username, avatar: callerData.avatar },
                { id: recipientId, username: recipientData.username, avatar: recipientData.avatar },
              ],
              initiator: { id: callerId, username: callerData.username, avatar: callerData.avatar },
            };
          } else {
            route =
              incomingCall.type === 'video' ? `/video-call/${incomingCall.roomId}` :
              incomingCall.type === 'translate' ? `/voice-call-translate/${incomingCall.roomId}` :
              `/voice-call/${incomingCall.roomId}`;
            state = {
              incomingCall,
              isCaller: false,
              currentUserId,
              callerName: callerData.username,
              recipientName: recipientData.username,
            };
          }

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

  // Отклонение звонка
  const rejectCall = () => {
    if (!incomingCall) return;

    audioRef.current.pause();
    audioRef.current.currentTime = 0;
    setIncomingCallWithLog(null);
    callRequestRef.current = null;
    sendSignal(
      { from: currentUserId, signal: { type: 'rejectCall' } },
      incomingCall.roomId
    );
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