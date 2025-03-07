import React, { useState, useEffect } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import '../styles/PersonalChats.css'; // Добавьте стили для компонента

const PersonalChats = () => {
    const { userId } = useParams();
    const navigate = useNavigate();
    const location = useLocation(); // Используем useLocation для получения переданных данных
    const [privateChats, setPrivateChats] = useState([]);
    const [avatars, setAvatars] = useState({}); // Состояние для хранения аватаров собеседников

    // Извлекаем имя пользователя из состояния
    const currentUserName = location.state?.currentUserName || 'User'; // Используем 'User' как запасное имя

    useEffect(() => {
        const fetchPrivateChats = async () => {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}/private-chats`);
                if (response.ok) {
                    const chats = await response.json();
                    setPrivateChats(chats);
                    fetchAvatars(chats); // Получаем аватары после загрузки чатов
                } else {
                    console.error('Failed to fetch private chats:', response.statusText);
                }
            } catch (error) {
                console.error('Error fetching private chats:', error);
            }
        };

        fetchPrivateChats();
    }, [userId]);

    const fetchAvatars = async (chats) => {
        const avatarPromises = chats.map(async (chat) => {
            const names = chat.name.split('_');
            const partnerName = names.find(name => name !== currentUserName);

            const response = await fetch(`https://unlimitedpossibilities12.org/api/users?username=${partnerName}`);
            if (response.ok) {
                const user = await response.json();
                return { username: partnerName, avatar: user.avatar }; // Возвращаем имя и аватар
            }
            return { username: partnerName, avatar: null }; // Если не удалось получить, возвращаем null
        });

        const avatarsData = await Promise.all(avatarPromises);
        const avatarsMap = avatarsData.reduce((acc, { username, avatar }) => {
            acc[username] = avatar;
            return acc;
        }, {});

        setAvatars(avatarsMap); // Сохраняем аватары в состоянии
    };

    const handleChatClick = (chatId) => {
        navigate(`/private-chat/${chatId}`); // Переход к конкретному чату
    };

    const getChatPartnerName = (chatName) => {
        const names = chatName.split('_');
        return names.find(name => name !== currentUserName); // Возвращаем имя, отличное от текущего пользователя
    };

    return (
        <div className="personal-chats-container">
            <h1>{`${currentUserName}'s private chats`}</h1> {/* Используем имя пользователя в заголовке */}
            <ul>
                {privateChats.map(chat => {
                    const partnerName = getChatPartnerName(chat.name);
                    const avatarSrc = avatars[partnerName] ? `https://unlimitedpossibilities12.org${avatars[partnerName]}` : null; // Получаем аватар собеседника

                    return (
                        <li key={chat.id} onClick={() => handleChatClick(chat.id)} className="chat-item1">
                            {avatarSrc ? (
                                <img src={avatarSrc} alt={`${partnerName} avatar`} className="chat-avatar" />
                            ) : (
                                <div className="chat-avatar-placeholder">{partnerName.charAt(0).toUpperCase()}</div>
                            )}
                            {partnerName}
                        </li>
                    );
                })}
            </ul>
        </div>
    );
};

export default PersonalChats;