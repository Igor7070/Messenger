import React, { useState, useEffect } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import '../styles/PersonalChats.css';

const PersonalChats = () => {
    const { userId } = useParams();
    const navigate = useNavigate();
    const location = useLocation();
    const [privateChats, setPrivateChats] = useState([]);
    const [chatPartners, setChatPartners] = useState({}); // Храним данные собеседников

    const currentUserName = location.state?.currentUserName || 'User';

    useEffect(() => {
        const fetchPrivateChats = async () => {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}/private-chats`);
                if (response.ok) {
                    const chats = await response.json();
                    setPrivateChats(chats);
                    fetchChatPartners(chats); // Загружаем данные собеседников
                } else {
                    console.error('Failed to fetch private chats:', response.statusText);
                }
            } catch (error) {
                console.error('Error fetching private chats:', error);
            }
        };

        fetchPrivateChats();
    }, [userId]);

    // Функция для загрузки данных собеседников
    const fetchChatPartners = async (chats) => {
        const partners = {};
        for (const chat of chats) {
            const partnerName = getChatPartnerName(chat.name);
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${partnerName}`);
                if (response.ok) {
                    const userData = await response.json();
                    partners[chat.id] = userData; // Сохраняем данные пользователя по ID чата
                } else {
                    console.error(`Failed to fetch user ${partnerName}:`, response.statusText);
                }
            } catch (error) {
                console.error(`Error fetching user ${partnerName}:`, error);
            }
        }
        setChatPartners(partners);
    };

    const handleChatClick = (chatId) => {
        navigate(`/private-chat/${chatId}`);
    };

    const getChatPartnerName = (chatName) => {
        const names = chatName.split('_');
        return names.find(name => name !== currentUserName);
    };

    return (
        <div className="personal-chats-container">
            <h1>{`${currentUserName}'s private chats`}</h1>
            <ul>
                {privateChats.map(chat => {
                    const partner = chatPartners[chat.id] || {};
                    const avatarSrc = partner.avatar ? `https://unlimitedpossibilities12.org${partner.avatar}` : null;
                    const initials = partner.username ? partner.username.charAt(0).toUpperCase() : '';
                    const partnerName = getChatPartnerName(chat.name);

                    return (
                        <li key={chat.id} onClick={() => handleChatClick(chat.id)} className="chat-item1">
                            {avatarSrc ? (
                                <img src={avatarSrc} alt={`${partnerName} avatar`} className="chat-avatar" />
                            ) : (
                                <div className="chat-avatar">
                                    {initials}
                                </div>
                            )}
                            <span className="chat-name">{partnerName}</span>
                        </li>
                    );
                })}
            </ul>
        </div>
    );
};

export default PersonalChats;