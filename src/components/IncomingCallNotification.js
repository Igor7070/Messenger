import React from 'react';
import '../styles/IncomingCallNotification.css';

const IncomingCallNotification = ({ callerId, callerName, avatar, onAccept, onReject }) => {
    const avatarSrc = avatar ? `https://unlimitedpossibilities12.org${avatar}` : null;
    const initials = callerName ? callerName.charAt(0).toUpperCase() : '';

    return (
        <div className="incoming-call-notification">
            <div className="caller-avatar">
                {avatarSrc ? (
                    <img
                        src={avatarSrc}
                        alt={`${callerName} avatar`}
                        className="caller-avatar-img"
                    />
                ) : (
                    <div className="caller-avatar-placeholder">{initials}</div>
                )}
            </div>
            <h2>Incoming call from {callerName}</h2>
            <div className="button-container">
                <button className="accept-button" onClick={onAccept}>Accept</button>
                <button className="reject-button" onClick={onReject}>Reject</button>
            </div>
        </div>
    );
};

export default IncomingCallNotification;