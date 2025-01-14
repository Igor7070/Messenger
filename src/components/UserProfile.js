import React, { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import '../styles/UserProfile.css'; 
import { FaEdit } from 'react-icons/fa';

const UserProfile = () => {
    const { userId } = useParams();
    const [user, setUser] = useState(null);
    const [newAvatar, setNewAvatar] = useState(null);
    const [info, setInfo] = useState('');
    const [isEditing, setIsEditing] = useState(false);
    const [error, setError] = useState(null);
    const [currentUserId, setCurrentUserId] = useState(null); // Добавляем состояние для текущего пользователя

    useEffect(() => {
        const fetchUser = async () => {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}`);
                if (response.ok) {
                    const data = await response.json();
                    setUser(data);
                    setInfo(data.info || '');
                } else {
                    setError('Failed to fetch user data');
                    console.error('Failed to fetch user:', response.statusText);
                }
            } catch (error) {
                setError('Error fetching user data');
                console.error('Error fetching user:', error);
            }
        };

        fetchUser();

        // Получаем текущий ID пользователя из localStorage
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
                alert('User updated successfully!');
                const updatedUser = await response.json();
                setUser(updatedUser);
                setIsEditing(false);
            } else {
                setError('Failed to update user');
                console.error('Failed to update user:', response.statusText);
            }
        } catch (error) {
            setError('Error updating user');
            console.error('Error updating user:', error);
        }
    };

    if (error) return <div className="error-message">{error}</div>;
    if (!user) return <div>Loading...</div>;

    return (
        <div className="user-profile-container">
            <h1>{user.username}</h1>
            {user.avatar ? (
                <img 
                    src={`https://unlimitedpossibilities12.org${user.avatar}`} 
                    alt={`${user.username} avatar`} 
                    className="user-avatar-profile" 
                />
            ) : (
                <div className="user-avatar-profile" style={{ backgroundColor: '#e0e0e0', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                    {user.username.charAt(0).toUpperCase()}
                </div>
            )}
            <input type="file" onChange={handleAvatarChange} className="avatar-input" />
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
                        {userId === String(currentUserId) && ( // Проверка на совпадение ID
                            <FaEdit className="edit-icon" onClick={() => setIsEditing(true)} />
                        )}
                    </div>
                )}
            </div>
            {userId === String(currentUserId) && ( // Проверка на совпадение ID
                <button onClick={handleUpdateUser} className="update-button">Update Profile</button>
            )}
        </div>
    );
};

export default UserProfile;