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
        console.log('Вызов setIncomingCall:', value);
        console.log('Текущее incomingCall:', incomingCall);
        console.log('Текущее callRequestRef:', callRequestRef.current);
        console.log('Стек вызовов:', new Error().stack);
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
        console.log('Состояние incomingCall изменилось:', incomingCall);
    }, [incomingCall]);

    // Управление звуком
    useEffect(() => {
        if (incomingCall && userInteracted) {
            console.log('Попытка воспроизведения звука, userInteracted:', userInteracted);
            audioRef.current.play().catch(error => {
                console.error('Автовоспроизведение заблокировано:', error);
                toast.warn('Please interact with the page to enable call audio.');
            });
        } else {
            console.log('Звук не воспроизводится, incomingCall:', incomingCall, 'userInteracted:', userInteracted);
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
            reconnectDelay: 5000, // Повторная попытка через 5 секунд
            onConnect: () => {
                console.log('Подключено к WebSocket для звонков в CallWebSocketContext');
                stompClient.current.subscribe(`/topic/calls/${currentUserId}`, (message) => {
                    console.log('Получено сообщение в /topic/calls:', message.body);
                    let callRequest;
                    try {
                        callRequest = JSON.parse(message.body);
                        console.log('Обработанное сообщение:', callRequest);
                        console.log('Тип сообщения:', callRequest.type);
                    } catch (error) {
                        console.error('Ошибка парсинга сообщения:', error, message.body);
                        return;
                    }

                    if (callRequest.type === 'endCall') {
                        console.log('Получен сигнал endCall:', callRequest);
                        if (
                            callRequestRef.current?.roomId === callRequest.roomId ||
                            incomingCall?.roomId === callRequest.roomId
                        ) {
                            console.log('Закрываем уведомление о звонке для roomId:', callRequest.roomId);
                            // Используем тип звонка из incomingCall или callRequestRef
                            const callType = incomingCall?.type || callRequestRef.current?.type || 'voice'; // Запасной вариант — 'voice'
                            const cancelMessage = callType === 'video' ? 'The video call was cancelled.' :
                                callType === 'translate' ? 'The translate call was cancelled.' :
                                callType === 'group' ? 'The group call was cancelled.' :
                                'The voice call was cancelled.';
                            setIncomingCallWithLog(null);
                            callRequestRef.current = null;
                            audioRef.current.pause();
                            audioRef.current.currentTime = 0;
                            console.log('Отправляем тост:', cancelMessage);
                            toast.info(cancelMessage);
                        } else {
                            console.log('Нет активного вызова или звука:', {
                                incomingCall,
                                callRequestRef: callRequestRef.current,
                                callRequest,
                                audioSrc: audioRef.current?.src,
                            });
                        }
                    } else if (callRequest.type === 'voice' || callRequest.type === 'video' || callRequest.type === 'translate' || callRequest.type === 'group') {
                        console.log('Получен запрос на звонок:', callRequest);
                        handleIncomingCall(callRequest);
                    } else {
                        console.log('Получено сообщение с неизвестным типом:', callRequest);
                    }
                });
            },
            onWebSocketError: (error) => {
                console.error('Ошибка WebSocket для звонков:', error);
                toast.error('Lost connection to calls. Reconnecting...');
            },
        });
        stompClient.current.activate();

        return () => {
            stompClient.current.deactivate();
            console.log('WebSocket для звонков отключён');
        };
    }, [currentUserId]);

    // Обработка входящего звонка
    const handleIncomingCall = async (callRequest) => {
        console.log('Входящий запрос на звонок:', callRequest);
        callRequestRef.current = {
            roomId: callRequest.roomId,
            callerId: callRequest.callerId || callRequest.initiatorId,
            recipientId: callRequest.recipientId,
            type: callRequest.type,
            initiatorName: callRequest.initiatorName, // Для группового звонка
            recipientIds: callRequest.recipientIds,   // Для группового звонка
        };
        console.log('callRequestRef установлен:', callRequestRef.current);
        setIncomingCallWithLog(callRequest);
        try {
            const callerId = callRequest.callerId || callRequest.initiatorId;
            console.log('Начало fetch для callerId/initiatorId:', callerId);
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${callerId}`);
            console.log('fetch завершен, status:', response.status);
            if (response.ok) {
                const callerData = await response.json();
                console.log('Данные звонящего:', callerData);
                setIncomingCallWithLog({
                    ...callRequest,
                    callerName: callerData.username,
                    avatar: callerData.avatar,
                });
            } else {
                console.error('Ошибка при получении данных звонящего:', response.status);
                setIncomingCallWithLog({
                    ...callRequest,
                    callerName: 'Unknown',
                    avatar: null,
                });
            }
        } catch (error) {
            console.error('Ошибка при получении данных звонящего:', error);
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
            console.log('Отправка сигнала:', JSON.stringify(signalMessage, null, 2));
            stompClient.current.publish({
                destination: `/app/signal/${roomId}`,
                body: JSON.stringify(signalMessage),
            });
            console.log('Сигнал успешно отправлен.');
        } else {
            console.error('STOMP client is not connected');
            toast.error('Failed to send signal. Please try again.');
        }
    };

    // Принятие звонка
    const acceptCall = (navigate) => {
        if (!incomingCall) return;

        audioRef.current.pause();
        audioRef.current.currentTime = 0;
        console.log('Функция acceptCall в CallWebSocketContext работает...');
        console.log('Входящий звонок принят:', incomingCall);

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

                    setIncomingCallWithLog(null);
                    callRequestRef.current = null;

                    let route;
                    let state;
                    if (incomingCall.type === 'group') {
                        route = `/group-call`;
                        state = {
                            participants: [{ id: callerId, username: callerData.username }], // Будет дополнено в GroupCallActivity
                            initiator: { id: callerId, username: callerData.username },
                        };
                    } else {
                        route = incomingCall.type === 'video' ? `/video-call/${incomingCall.roomId}` :
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
                    console.error('Ошибка при получении имен пользователей');
                    toast.error('Failed to fetch user data');
                }
            } catch (error) {
                console.error('Ошибка при получении имен пользователей:', error);
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
            'Voice call rejected'
        );
    };

    return (
        <CallWebSocketContext.Provider
            value={{
                incomingCall,
                acceptCall,
                rejectCall,
                setCurrentUserId, // Для обновления currentUserId при логине/логауте
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