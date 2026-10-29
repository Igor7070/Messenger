import { useEffect } from 'react';
import { stompAuthHeaders } from '../authToken';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { toast } from 'react-toastify';
import { useUserInteraction } from '../components/UserInteractionContext';

const useWebSocket = (currentUserId, messageAudioRef) => {
    const { userInteracted } = useUserInteraction();

    useEffect(() => {
        if (!currentUserId) {
            console.error('No currentUserId provided');
            return;
        }

        console.log('useWebSocket: userInteracted =', userInteracted);
        console.log('useWebSocket: messageAudioRef =', messageAudioRef);

        const stompClient = new Client({
            connectHeaders: stompAuthHeaders(),
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket');

                stompClient.subscribe(`/topic/notifications/${currentUserId}`, (message) => {
                    const notification = message.body;
                    console.log('Received notification:', notification);

                    const chatIdMatch = notification.match(/\(chatId: (\d+)\)/);
                    const chatId = chatIdMatch ? chatIdMatch[1] : null;

                    // Проверяем и воспроизводим звук
                    if (userInteracted && messageAudioRef && messageAudioRef.current) {
                        console.log('Attempting to play message audio');
                        messageAudioRef.current.play()
                            .then(() => console.log('Message audio played successfully'))
                            .catch(error => {
                                console.error('Failed to play message audio:', error);
                                toast.warn('Please interact with the page to enable notification sounds.');
                            });
                    } else {
                        console.log('Audio not played: userInteracted =', userInteracted, 'messageAudioRef =', messageAudioRef, 'messageAudioRef.current =', messageAudioRef?.current);
                    }

                    // Отображаем уведомление...
                    toast.info(notification, {
                        autoClose: false,
                        onClick: async () => {
                            // Остановка звука при клике
                            if (messageAudioRef && messageAudioRef.current) {
                                console.log('Pausing message audio on toast click');
                                messageAudioRef.current.pause();
                                messageAudioRef.current.currentTime = 0;
                            }

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
                                console.error('chatId не найден в уведомлении');
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
    }, [currentUserId, userInteracted, messageAudioRef]);

    return null;
};

export default useWebSocket;