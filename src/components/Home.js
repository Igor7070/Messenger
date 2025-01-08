import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import '../styles/Home.css'; 

const Home = () => {
    const [chatName, setChatName] = useState('');
    const [searchTerm, setSearchTerm] = useState('');
    const [userSearchTerm, setUserSearchTerm] = useState(''); // Новое состояние для поиска пользователей
    const [chats, setChats] = useState([]);
    const [users, setUsers] = useState([]);
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

    const fetchUsers = async () => {
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/users');
            if (response.ok) {
                const data = await response.json();
                setUsers(data);
            } else {
                console.error('Failed to fetch users:', response.statusText);
            }
        } catch (error) {
            console.error('Error fetching users:', error);
        }
    };

    useEffect(() => {
        fetchChats();
        fetchUsers();
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

    const createUser = async () => {
        // Логика для создания нового пользователя
        console.log('Создание нового пользователя...');
    };

    const goToChat = (chatId) => {
        navigate(`/chat/${chatId}`);
    };

    // Фильтруем чаты по искомому имени
    const filteredChats = chats.filter(chat => chat.name.toLowerCase().includes(searchTerm.toLowerCase()));

    // Фильтруем пользователей по искомому имени
    const filteredUsers = users.filter(user => user.username.toLowerCase().includes(userSearchTerm.toLowerCase()));

    return (
        <div className="home-container">
            <div className="users-container">
                <h2>Пользователи</h2>
                <input
                    type="text"
                    value={userSearchTerm}
                    onChange={(e) => setUserSearchTerm(e.target.value)}
                    placeholder="Найти пользователя"
                    className="search-input"
                />
                <button onClick={createUser} className="create-user-button-main">Создать нового пользователя</button>
                <ul className="user-list">
                    {filteredUsers.map(user => (
                        <li key={user.id} className="user-item">
                            {user.username}
                        </li>
                    ))}
                </ul>
            </div>

            <div className="line-divider"></div> {/* Линия раздела */}

            <div className="chats-container">
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
        </div>
    );
};

export default Home;