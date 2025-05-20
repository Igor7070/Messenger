import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { toast } from 'react-toastify';
import { useUserInteraction } from '../components/UserInteractionContext';
import { useNavigate } from 'react-router-dom'; // Добавляем useNavigate

const PrivateMessageNotificationContext = createContext();

export const PrivateMessageNotificationProvider = ({ children }) => {
    const { userInteracted } = useUserInteraction();
    const [currentUserId, setCurrentUserId] = useState(null);
    const [currentChatId, setCurrentChatId] = useState(null); // Текущее состояние для chatId
    const messageAudioRef = useRef(null);
    const stompClientRef = useRef(null);
    const navigate = useNavigate(); // Для навигации

    useEffect(() => {
        if (!currentUserId) {
            console.log('Нет currentUserId, пропускаем подписку на уведомления личных чатов');
            return;
        }

        console.log('useWebSocket: userInteracted =', userInteracted);
        console.log('useWebSocket: messageAudioRef =', messageAudioRef);

        const stompClient = new Client({
            webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
            onConnect: () => {
                console.log('Connected to WebSocket for private chat notifications');

                stompClient.subscribe(`/topic/notifications/${currentUserId}`, (message) => {
                    const notification = message.body;
                    console.log('Received notification:', notification);

                    const chatIdMatch = notification.match(/\(chatId: (\d+)\)/);
                    const chatId = chatIdMatch ? chatIdMatch[1] : null;

                    // Показываем уведомление, если нет текущего чата (currentChatId === null) или сообщение из другого чата
                    if (!currentChatId || (chatId && chatId !== currentChatId)) {
                        if (userInteracted && messageAudioRef.current) {
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

                        toast.info(notification, {
                            autoClose: false,
                            onClick: async () => {
                                if (messageAudioRef.current) {
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
                                                navigate(`/private-chat/${chatId}`);
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
                                                navigate(`/private-chat/${chatId}`);
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
                    }
                });
            },
            onWebSocketError: (error) => {
                console.error('WebSocket error:', error);
            },
            onStompError: (frame) => {
                console.error('STOMP error:', frame);
            },
        });

        stompClientRef.current = stompClient;
        stompClient.activate();

        return () => {
            if (stompClientRef.current) {
                stompClientRef.current.deactivate();
                console.log('WebSocket для уведомлений личных чатов отключён');
            }
        };
    }, [currentUserId, userInteracted, currentChatId, navigate]);

    return (
        <PrivateMessageNotificationContext.Provider value={{ setCurrentUserId, setCurrentChatId }}>
            <audio ref={messageAudioRef}>
                <source src="https://igor7070.github.io/Messenger/zvuk-dlya-soobsen.mp3" type="audio/mpeg" />
                Ваш браузер не поддерживает аудио.
            </audio>
            {children}
        </PrivateMessageNotificationContext.Provider>
    );
};

export const usePrivateMessageNotification = () => {
    return useContext(PrivateMessageNotificationContext);
};