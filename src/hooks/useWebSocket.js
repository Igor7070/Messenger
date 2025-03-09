import { useEffect } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { toast } from 'react-toastify';

const useWebSocket = (currentUserId) => {
    useEffect(() => {
        if (!currentUserId) {
            console.error('No currentUserId provided');
            return; // Если currentUserId не установлен, ничего не делаем
        }

        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');

                // Подписка на уведомления
                stompClient.subscribe(`/topic/notifications/${currentUserId}`, (message) => {
                    const notification = message.body;
                    console.log("Received notification:", notification);

                    // Извлечение chatId из уведомления
                    const chatIdMatch = notification.match(/\(chatId: (\d+)\)/);
                    const chatId = chatIdMatch ? chatIdMatch[1] : null;

                    // Отображение уведомления
                    toast.info(notification, {
                        autoClose: false,
                        onClick: async () => {
                            if (chatId) {
                                try {
                                    const checkResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${currentUserId}/private-chats/${chatId}/exists`);
                                    console.log('checkResponse status:', checkResponse.status);

                                    if (checkResponse.ok) {
                                        const text = await checkResponse.text();
                                        console.log('checkResponse text:', text);
                                        const exists = text === "Chat exists in user's private chats";
                                        if (exists) {
                                            window.location.href = `/Messenger/#/private-chat/${chatId}`;
                                            toast.success('Navigating to chat!');
                                        } else {
                                            console.error('Unexpected response for OK status:', text);
                                            toast.error('Unexpected server response');
                                        }
                                    } else if (checkResponse.status === 404) {
                                        console.log('Чат не существует у текущего пользователя, добавляем его');
                                        const addResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${currentUserId}/private-chats/${chatId}/add`, {
                                            method: 'POST',
                                        });
                                        console.log('addResponse status:', addResponse.status, 'text:', await addResponse.text());

                                        if (addResponse.ok) {
                                            window.location.href = `/Messenger/#/private-chat/${chatId}`;
                                            toast.success('Navigating to chat!');
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
                                console.error("chatId не найден в уведомлении");
                                toast.error('Invalid notification format');
                            }
                        },
                    });
                });
            },
            onWebSocketError: (error) => {
                console.error('WebSocket error:', error);
            },
        });

        stompClient.activate();

        return () => {
            stompClient.deactivate();
        };
    }, [currentUserId]); // Зависимость от currentUserId
};

export default useWebSocket;