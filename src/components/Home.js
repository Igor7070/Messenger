import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import '../styles/Home.css'; 

const Home = () => {
    const [chatName, setChatName] = useState('');
    const [searchTerm, setSearchTerm] = useState('');
    const [chats, setChats] = useState([]);
    const navigate = useNavigate();

    const fetchChats = async () => {
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/chats');
            if (response.ok) {
                const data = await response.json();
                setChats(data);
            } else {
                console.error('Failed to fetch chats:', response.statusText);
            }
        } catch (error) {
            console.error('Error fetching chats:', error);
        }
    };

    useEffect(() => {
        fetchChats();
    }, []);

    const createChat = async () => {
        if (chatName) {
            try {
                const response = await fetch('https://unlimitedpossibilities12.org/api/chats', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({ name: chatName }),
                });

                if (response.ok) {
                    const newChat = await response.json();
                    setChats([...chats, newChat]);
                    setChatName('');
                } else {
                    console.error('Failed to create chat:', response.statusText);
                }
            } catch (error) {
                console.error('Error creating chat:', error);
            }
        }
    };

    const goToChat = (chatId) => {
        navigate(`/chat/${chatId}`);
    };

    // Фильтруем чаты по искомому имени
    const filteredChats = chats.filter(chat => chat.name.toLowerCase().includes(searchTerm.toLowerCase()));

    return (
        <div className="home-container">
            <h1>Групповые Чаты</h1>
            <div className="chat-input-container">
                <input
                    type="text"
                    value={chatName}
                    onChange={(e) => setChatName(e.target.value)}
                    placeholder="Введите имя чата"
                    className="chat-input"
                />
                <button onClick={createChat} className="create-chat-button">Создать групповой чат</button>
            </div>
            {/* Поле ввода для поиска */}
            <input
                type="text"
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                placeholder="Поиск чатов..."
                className="search-input"
            />
            <ul className="chat-list">
                {filteredChats.map((chat) => (
                    <li key={chat.id} onClick={() => goToChat(chat.id)} className="chat-item">
                        {chat.name}
                    </li>
                ))}
            </ul>
        </div>
    );
};

export default Home;