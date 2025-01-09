import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import '../styles/Home.css'; 

const Home = () => {
    const [chatName, setChatName] = useState('');
    const [searchTerm, setSearchTerm] = useState('');
    const [userSearchTerm, setUserSearchTerm] = useState(''); // Новое состояние для поиска пользователей
    const [chats, setChats] = useState([]);
    const [users, setUsers] = useState([]);
    const [modalIsOpen, setModalIsOpen] = useState(false);
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [bio, setBio] = useState('');
    const [avatar, setAvatar] = useState(null);
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
        const formData = new FormData();
        formData.append('username', username);
        formData.append('password', password);
        formData.append('bio', bio);
        if (avatar) {
            formData.append('avatar', avatar);
        }

        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/users', {
                method: 'POST',
                body: formData,
            });

            if (response.ok) {
                const newUser = await response.json();
                setUsers([...users, newUser]);
                setModalIsOpen(false);
                setUsername('');
                setPassword('');
                setBio('');
                setAvatar(null);
            } else {
                const errorMessage = await response.text();
                alert(errorMessage); // Показать сообщение об ошибке
            }
        } catch (error) {
            console.error('Error creating user:', error);
        }
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
                <h2>Users</h2>
                <input
                    type="text"
                    value={userSearchTerm}
                    onChange={(e) => setUserSearchTerm(e.target.value)}
                    placeholder="Search user"
                    className="search-input"
                />
                <button onClick={createUser} className="create-user-button-main">Create a new user</button>
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
                <h1>Group chats</h1>
                <div className="chat-input-container">
                    <input
                        type="text"
                        value={chatName}
                        onChange={(e) => setChatName(e.target.value)}
                        placeholder="Enter the chat name"
                        className="chat-input"
                    />
                    <button onClick={createChat} className="create-chat-button">Create a group chat</button>
                </div>
                <input
                    type="text"
                    value={searchTerm}
                    onChange={(e) => setSearchTerm(e.target.value)}
                    placeholder="Search chats..."
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

            {/* Модальное окно для создания пользователя */}
            <Modal isOpen={modalIsOpen} onRequestClose={() => setModalIsOpen(false)} className="modal" overlayClassName="modal-overlay">
                <h2>Create User</h2>
                <input
                    type="text"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    placeholder="Enter username"
                    required
                />
                <input
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Enter password"
                    required
                />
                <textarea
                    value={bio}
                    onChange={(e) => setBio(e.target.value)}
                    placeholder="Tell us about yourself (optional)"
                    rows={3}
                    style={{ resize: 'vertical' }} // Позволяет изменять высоту
                />
                <input
                    type="file"
                    onChange={(e) => setAvatar(e.target.files[0])}
                />
                <button onClick={createUser}>Create User</button>
                <button onClick={() => setModalIsOpen(false)} className="close-button">Close</button>
            </Modal>
        </div>
    );
};

export default Home;