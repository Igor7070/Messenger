import React from 'react';
import { FaPhone, FaVideo, FaLanguage, FaUsers, FaLock } from 'react-icons/fa';
import '../styles/IncomingCallNotification.css';

const IncomingCallNotification = ({ callerId, callerName, avatar, callType, onAccept, onReject }) => {
    const avatarSrc = avatar ? `https://unlimitedpossibilities12.org${avatar}` : null;
    const initials = callerName ? callerName.charAt(0).toUpperCase() : '';

    const getNotificationDetails = () => {
        switch (callType) {
            case 'video':
                return { title: 'Incoming Video Call', Icon: FaVideo };
            case 'translate':
                return { title: 'Incoming Translate Call', Icon: FaLanguage };
            case 'group':
                return { title: 'Incoming Group Call', Icon: FaUsers };
            case 'voice_secure':
                return { 
                    title: 'Secure Voice Call (E2EE)', 
                    Icon: FaLock,
                    iconColor: '#00ff00',
                    titleColor: '#00ff00'
                };
            default:
                return { title: 'Incoming Voice Call', Icon: FaPhone };
        }
    };

    const { title, Icon, iconColor = '#4A90E2', titleColor = '#333' } = getNotificationDetails();

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
                <h2 style={{ color: titleColor, fontWeight: 'bold' }}>
                    {title} from {callerName}
                </h2>
                <div className="notification-icon-container">
                    <Icon className="notification-icon" style={{ color: iconColor }} />
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