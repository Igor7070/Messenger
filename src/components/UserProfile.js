import React, { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import '../styles/UserProfile.css'; 

const UserProfile = () => {
    const { userId } = useParams();
    const [user, setUser] = useState(null);
    const [newAvatar, setNewAvatar] = useState(null);
    const [info, setInfo] = useState('');
    const [error, setError] = useState(null); // Для хранения ошибок

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
                // Обновите информацию пользователя в состоянии
                const updatedUser = await response.json(); // Получаем обновлённые данные
                setUser(updatedUser);
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
            <img src={user.avatar || '/default-avatar.png'} alt={`${user.username} avatar`} className="user-avatar" />
            <input type="file" onChange={handleAvatarChange} />
            <textarea 
                value={info}
                onChange={(e) => setInfo(e.target.value)}
                placeholder="Tell us about yourself"
                rows={4}
                className="user-info-input"
            />
            <button onClick={handleUpdateUser} className="update-button">Update Profile</button>
        </div>
    );
};

export default UserProfile;