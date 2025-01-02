import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';

const Home = () => {
    const [chatName, setChatName] = useState('');
    const [chats, setChats] = useState([]);
    const navigate = useNavigate();

    const createChat = () => {
        if (chatName) {
            setChats([...chats, chatName]);
            setChatName('');
        }
    };

    const goToChat = (chatName) => {
        navigate(`/chat/${chatName}`); // Исправлено: убран пробел перед маршрутом
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
                {chats.map((chat, index) => (
                    <li key={index} onClick={() => goToChat(chat)}>
                        {chat}
                    </li>
                ))}
            </ul>
        </div>
    );
};

export default Home;