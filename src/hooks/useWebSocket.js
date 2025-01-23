import { useEffect } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { toast } from 'react-toastify';

const useWebSocket = (userId) => {
    useEffect(() => {
        if (!userId) return; // Если userId не установлен, ничего не делаем

        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'), // Замените на ваш WebSocket URL
            onConnect: () => {
                console.log('Connected to WebSocket');

                // Подписка на уведомления
                stompClient.subscribe(`/topic/notifications/${userId}`, (message) => {
                    const notification = message.body;
                    console.log("Received notification:", notification);
                    toast.info(notification, {
                        autoClose: false,
                    });
                });
            },
            onWebSocketError: (error) => {
                console.error('WebSocket error:', error);
            },
        });

        stompClient.activate(); // Активируем клиента

        return () => {
            stompClient.deactivate(); // Деактивируем клиента при размонтировании компонента
        };
    }, [userId]); // Эффект зависит от userId
};

export default useWebSocket;