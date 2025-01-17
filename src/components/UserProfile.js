import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom'; // Импортируйте useNavigate
import '../styles/UserProfile.css'; 
import { FaEdit } from 'react-icons/fa';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import Modal from 'react-modal';

const UserProfile = () => {
    const { userId } = useParams();
    const navigate = useNavigate(); // Инициализация useNavigate
    const [user, setUser] = useState(null);
    const [newAvatar, setNewAvatar] = useState(null);
    const [info, setInfo] = useState('');
    const [isEditing, setIsEditing] = useState(false);
    const [error, setError] = useState(null);
    const [currentUserId, setCurrentUserId] = useState(null);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [avatarFileName, setAvatarFileName] = useState('');

    useEffect(() => {
        const fetchUser = async () => {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}`);
                if (response.ok) {
                    const data = await response.json();
                    setUser(data);
                    setInfo(data.info || '');
                    const avatarResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}/avatar`);
                    if (avatarResponse.ok) {
                        const fileName = await avatarResponse.text();
                        setAvatarFileName(fileName);
                    } else {
                        console.error('Failed to fetch avatar file name:', avatarResponse.statusText);
                    }
                } else {
                    setError('Failed to fetch user data');
                    console.error('Failed to fetch user:', response.statusText);
                    toast.error('Failed to fetch user data');
                }
            } catch (error) {
                setError('Error fetching user data');
                console.error('Error fetching user:', error);
                toast.error('Error fetching user data');
            }
        };

        fetchUser();

        const storedUserId = localStorage.getItem('userId');
        if (storedUserId) {
            setCurrentUserId(Number(storedUserId));
        }
    }, [userId]);

    const handleAvatarChange = (e) => {
        setNewAvatar(e.target.files[0]);
    };

    const handleUpdateUser = async () => {
        const formData = new FormData();
        formData.append('info', info);
        if (newAvatar) {
            formData.append('avatar', newAvatar);
        }

        try {
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}`, {
                method: 'PUT',
                body: formData,
            });

            if (response.ok) {
                const updatedUser = await response.json();
                setUser(updatedUser);
                toast.success('User updated successfully!');
                setIsEditing(false);
            } else {
                setError('Failed to update user');
                console.error('Failed to update user:', response.statusText);
                toast.error('Failed to update user');
            }
        } catch (error) {
            setError('Error updating user');
            console.error('Error updating user:', error);
            toast.error('Error updating user');
        }
    };

    const openModal = () => {
        setIsModalOpen(true);
    };

    const closeModal = () => {
        setIsModalOpen(false);
    };

    const handleSendMessage = async () => {
        const currentUsername = localStorage.getItem('username'); // Получаем актуальное имя пользователя
        const chatName = `${user.username}_${currentUsername}`; // Создаем имя чата
    
        if (currentUserId && userId !== String(currentUserId)) {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/chats/check?name1=${user.username}&name2=${currentUsername}`);
                if (!response.ok) {
                    throw new Error(`HTTP error! Status: ${response.status}`);
                }
    
                const data = await response.json();
                const chatId = data.chatId;
    
                if (chatId !== -1) {
                    navigate(`/private-chat/${chatId}`);
                } else {
                    const newChat = { 
                        name: chatName, 
                        isPrivate: true // Устанавливаем isPrivate в true
                    };
    
                    console.log('New chat data:', newChat);
    
                    const createResponse = await fetch('https://unlimitedpossibilities12.org/api/chats', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                        },
                        body: JSON.stringify(newChat),
                    });
    
                    if (createResponse.ok) {
                        const createdChat = await createResponse.json();
                        navigate(`/private-chat/${createdChat.id}`);
                    } else {
                        toast.error('Failed to create new chat');
                    }
                }
    
                toast.success(`Navigating to chat with ${user.username}!`);
            } catch (error) {
                console.error('Ошибка при отправке сообщения:', error);
                toast.error('Ошибка при отправке сообщения. Попробуйте снова.');
            }
        } else {
            toast.warn('You cannot send a message to yourself.');
        }
    };

    if (error) return <div className="error-message">{error}</div>;
    if (!user) return <div>Loading...</div>;

    return (
        <div className="user-profile-container">
            <ToastContainer position="top-right" autoClose={5000} hideProgressBar={false} newestOnTop={false} closeOnClick rtl={false} pauseOnFocusLoss draggable pauseOnHover />
            <h1>{user.username}</h1>
            {user.avatar ? (
                <img 
                    src={`https://unlimitedpossibilities12.org${user.avatar}`} 
                    alt={`${user.username} avatar`} 
                    className="user-avatar-profile" 
                    onClick={openModal} 
                />
            ) : (
                <div className="user-avatar-profile" style={{ backgroundColor: '#e0e0e0', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                    {user.username.charAt(0).toUpperCase()}
                </div>
            )}
            {userId === String(currentUserId) && ( 
                <input type="file" onChange={handleAvatarChange} className="avatar-input" />
            )}
            <div className="user-info">
                <h2>О себе:</h2>
                {isEditing ? (
                    <textarea 
                        value={info}
                        onChange={(e) => setInfo(e.target.value)}
                        rows={3}
                        className="user-info-input"
                        maxLength={244}
                    />
                ) : (
                    <div className="user-info-text">
                        <p>{info}</p>
                        {userId === String(currentUserId) && ( 
                            <FaEdit className="edit-icon" onClick={() => setIsEditing(true)} />
                        )}
                    </div>
                )}
            </div>
            {userId === String(currentUserId) && ( 
                <button onClick={handleUpdateUser} className="update-button">Update Profile</button>
            )}

            {/* Кнопка "Send message" */}
            {currentUserId && userId !== String(currentUserId) && ( 
                <button onClick={handleSendMessage} className="send-message-button">Send Message</button>
            )}

            {/* Модальное окно для увеличенного изображения */}
            <Modal isOpen={isModalOpen} onRequestClose={closeModal} className="user-profile-modal" overlayClassName="user-profile-modal-overlay">
                <img 
                    src={`https://unlimitedpossibilities12.org/avatars/${avatarFileName}`} 
                    alt={`${user.username} avatar`} 
                    className="user-profile-modal-image" 
                    style={{ width: '100%', height: 'auto' }} 
                    onClick={closeModal} 
                />
                <button onClick={closeModal} className="user-profile-modal-close-button">Close</button>
            </Modal>
        </div>
    );
};

export default UserProfile;