import React from 'react';
import '../styles/IncomingCallNotification.css';

const IncomingCallNotification = ({ callerId, callerName, onAccept, onReject }) => {
    return (
        <div className="incoming-call-notification">
            <h2>Incoming call from {callerName}</h2>
            <div className="button-container">
                <button className="accept-button" onClick={onAccept}>Accept</button>
                <button className="reject-button" onClick={onReject}>Reject</button>
            </div>
        </div>
    );
};

export default IncomingCallNotification;