import React from 'react';
import { FaPhone, FaVideo, FaLanguage } from 'react-icons/fa';
import '../styles/IncomingCallNotification.css';

const IncomingCallNotification = ({ callerId, callerName, avatar, callType, onAccept, onReject }) => {
    const avatarSrc = avatar ? `https://unlimitedpossibilities12.org${avatar}` : null;
    const initials = callerName ? callerName.charAt(0).toUpperCase() : '';
    const notificationTitle = callType === 'video' ? 'Incoming Video Call' : 
                         callType === 'translate' ? 'Incoming Translate Call' : 
                         'Incoming Voice Call';
    const NotificationIcon = callType === 'video' ? FaVideo : 
                            callType === 'translate' ? FaLanguage : 
                            FaPhone;

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
            <div className="notification-header">
                <h2>{notificationTitle} from {callerName}</h2>
                <div className="notification-icon-container">
                    <NotificationIcon className="notification-icon" />
                </div>
            </div>
            <div className="button-container">
                <button className="accept-button" onClick={onAccept}>
                    Accept
                </button>
                <button className="reject-button" onClick={onReject}>
                    Reject
                </button>
            </div>
        </div>
    );
};

export default IncomingCallNotification;