import React, { useEffect, useState } from 'react';
import '../styles/IncomingCallNotification.css';

const IncomingCallNotification = ({ callerId, onAccept, onReject }) => {
    const [callerName, setCallerName] = useState('Loading...');

    const getUserNameById = async (userId) => {
        try {
            const response = await fetch(`/api/users/${userId}`);
            if (!response.ok) {
                throw new Error('Network response was not ok');
            }
            const user = await response.json();
            return user.username; // Извлекаем username
        } catch (error) {
            console.error('Error fetching user:', error);
            return 'Unknown User'; // Возвращаем "Unknown User" в случае ошибки
        }
    };

    useEffect(() => {
        const fetchCallerName = async () => {
            const userName = await getUserNameById(callerId);
            setCallerName(userName);
        };

        fetchCallerName();
    }, [callerId]);

    return (
        <div className="incoming-call-notification">
            <h2>Incoming Call from {callerName}</h2>
            <div className="button-container">
                <button className="accept-button" onClick={onAccept}>Accept</button>
                <button className="reject-button" onClick={onReject}>Reject</button>
            </div>
        </div>
    );
};