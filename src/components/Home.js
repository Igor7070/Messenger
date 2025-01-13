import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from 'react-modal';
import '../styles/Home.css'; 

const Home = () => {
    const [chatName, setChatName] = useState('');
    const [searchTerm, setSearchTerm] = useState('');
    const [userSearchTerm, setUserSearchTerm] = useState('');
    const [chats, setChats] = useState([]);
    const [users, setUsers] = useState([]);
    const [modalIsOpen, setModalIsOpen] = useState(false);
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [bio, setBio] = useState('');
    const [avatar, setAvatar] = useState(null);
    const [currentUserId, setCurrentUserId] = useState(null);
    const [loginUsername, setLoginUsername] = useState('');
    const [loginPassword, setLoginPassword] = useState('');
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
        const storedUserId = localStorage.getItem('userId');
        if (storedUserId) {
            setCurrentUserId(storedUserId);
        }
        fetchChats();
        fetchUsers();
    }, []);

    const loginUser = async () => {
        try {
            const response = await fetch('https://unlimitedpossibilities12.org/api/users/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ username: loginUsername, password: loginPassword }),
            });

            if (response.ok) {
                const user = await response.json();
                localStorage.setItem('userId', user.id);
                setCurrentUserId(user.id);
                setLoginUsername('');
                setLoginPassword('');
            } else {
                const errorMessage = await response.text();
                alert(errorMessage);
            }
        } catch (error) {
            console.error('Error logging in:', error);
        }
    };

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
            const response = await fetch('https://unlimitedpossibilities12.org/api/users/create', {
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
                alert(errorMessage);
            }
        } catch (error) {
            console.error('Error creating user:', error);
        }
    };

    const goToChat = (chatId) => {
        navigate(`/chat/${chatId}`);
    };

    const openModal = () => {
        setUsername('');
        setPassword('');
        setBio('');
        setAvatar(null);
        setModalIsOpen(true);
    };

    const filteredChats = chats.filter(chat => chat.name.toLowerCase().includes(searchTerm.toLowerCase()));
    const filteredUsers = users.filter(user => user.username.toLowerCase().includes(userSearchTerm.toLowerCase()));

    return (
        <div className="home-container">
            {/* Новый контейнер для логина */}
            <div className="login-container-home">
                <input
                    type="text"
                    value={loginUsername}
                    onChange={(e) => setLoginUsername(e.target.value)}
                    placeholder="Username"
                    className="login-input-home"
                />
                <input
                    type="password"
                    value={loginPassword}
                    onChange={(e) => setLoginPassword(e.target.value)}
                    placeholder="Password"
                    className="login-input-home"
                />
                <button onClick={loginUser} className="login-button-home">Login</button>
            </div>

            <div className="users-container">
                <h2>Users</h2>
                <input
                    type="text"
                    value={userSearchTerm}
                    onChange={(e) => setUserSearchTerm(e.target.value)}
                    placeholder="Search user"
                    className="search-input"
                />
                <button type="button" onClick={openModal} className="create-user-button-main">Create a new user</button>
                <ul className="user-list">
                    {filteredUsers.map(user => {
                        const avatarSrc = user.avatar ? `https://unlimitedpossibilities12.org${user.avatar}` : null;
                        const initials = user.username ? user.username.charAt(0).toUpperCase() : '';

                        return (
                            <li key={user.id} className="user-item" onClick={() => {
                                if (user.id === currentUserId) {
                                    navigate(`/user/${user.id}`);
                                } else {
                                    alert('Вы можете редактировать только свой аккаунт.');
                                }
                            }}>
                                {avatarSrc ? (
                                    <img src={avatarSrc} alt={`${user.username} avatar`} className="user-avatar" />
                                ) : (
                                    <div className="user-avatar">
                                        {initials}
                                    </div>
                                )}
                                <span className="user-name">{user.username}</span>
                            </li>
                        );
                    })}
                </ul>
            </div>
    
            <div className="line-divider"></div> 
    
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
    
            <Modal isOpen={modalIsOpen} onRequestClose={() => setModalIsOpen(false)} className="modal" overlayClassName="modal-overlay">
                <h2 className="modal-title">Create User</h2>
                <input
                    type="text"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    placeholder="Enter username"
                    className="modal-input"
                    required
                />
                <input
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Enter password"
                    className="modal-input"
                    required
                />
                <textarea
                    value={bio}
                    onChange={(e) => setBio(e.target.value)}
                    placeholder="Tell us about yourself (optional)"
                    rows={3}
                    className="modal-textarea"
                    maxLength={244}
                />
                <input
                    type="file"
                    onChange={(e) => setAvatar(e.target.files[0])}
                    className="modal-file-input"
                />
                <button type="button" onClick={createUser} className="modal-create-button">Create User</button>
                <button onClick={() => setModalIsOpen(false)} className="modal-close-button">Close</button>
            </Modal>
        </div>
    );
};

export default Home;