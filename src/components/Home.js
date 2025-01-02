import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

const Home = () => {
    const [chatName, setChatName] = useState('');
    const [chats, setChats] = useState([]);
    const navigate = useNavigate();

    // Функция для получения списка чатов
    const fetchChats = async () => {
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/chats');
            if (response.ok) {
                const data = await response.json();
                setChats(data); // Предполагается, что API возвращает массив объектов чатов
            } else {
                console.error('Failed to fetch chats:', response.statusText);
            }
        } catch (error) {
            console.error('Error fetching chats:', error);
        }
    };

    useEffect(() => {
        fetchChats(); // Загружаем чаты при монтировании компонента
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
                    setChats([...chats, newChat]); // Добавляем новый объект чата в список
                    setChatName(''); // Очищаем поле ввода
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

    return (
        <div>
            <h1>Групповые Чаты</h1>
            <input
                type="text"
                value={chatName}
                onChange={(e) => setChatName(e.target.value)}
                placeholder="Введите имя чата"
            />
            <button onClick={createChat}>Создать групповой чат</button>
            <ul>
                {chats.map((chat) => (
                    <li key={chat.id} onClick={() => goToChat(chat.id)}>
                        {chat.name} {/* Отображаем имя чата */}
                    </li>
                ))}
            </ul>
        </div>
    );
};

export default Home;