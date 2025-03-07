import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import '../styles/PersonalChats.css'; // Добавьте стили для компонента

const PersonalChats = () => {
    const { userId } = useParams();
    const navigate = useNavigate();
    const [privateChats, setPrivateChats] = useState([]);

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
            <h1>Private Chats</h1>
            <ul>
                {privateChats.map(chat => (
                    <li key={chat.id} onClick={() => handleChatClick(chat.id)} className="chat-item">
                        {chat.name}
                    </li>
                ))}
            </ul>
        </div>
    );
};

export default PersonalChats;