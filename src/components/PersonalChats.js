import React, { useState, useEffect } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import '../styles/PersonalChats.css'; // Добавьте стили для компонента

const PersonalChats = () => {
    const { userId } = useParams();
    const navigate = useNavigate();
    const location = useLocation(); // Используем useLocation для получения переданных данных
    const [privateChats, setPrivateChats] = useState([]);

    // Извлекаем имя пользователя из состояния
    const currentUserName = location.state?.currentUserName || 'User'; // Используем 'User' как запасное имя

    useEffect(() => {
        const fetchPrivateChats = async () => {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}/private-chats`);
                if (response.ok) {
                    const chats = await response.json();
                    setPrivateChats(chats);
                } else {
                    console.error('Failed to fetch private chats:', response.statusText);
                }
            } catch (error) {
                console.error('Error fetching private chats:', error);
            }
        };

        fetchPrivateChats();
    }, [userId]);

    const handleChatClick = (chatId) => {
        navigate(`/private-chat/${chatId}`); // Переход к конкретному чату
    };

    return (
        <div className="personal-chats-container">
            <h1>{`${currentUserName}'s Private Chats`}</h1> {/* Используем имя пользователя в заголовке */}
            <ul>
                {privateChats.map(chat => (
                    <li key={chat.id} onClick={() => handleChatClick(chat.id)} className="chat-item1">
                        {chat.name}
                    </li>
                ))}
            </ul>
        </div>
    );
};

export default PersonalChats;