import React from 'react';
import './IncomingCallNotification.css'; // Добавьте импорт стилей

const IncomingCallNotification = ({ callerName, onAccept, onReject }) => {
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

export default IncomingCallNotification;