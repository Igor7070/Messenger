import React from 'react';
import { FaPhone, FaVideo, FaLanguage, FaUsers } from 'react-icons/fa';
import '../styles/IncomingCallNotification.css';

const IncomingCallNotification = ({ callerId, callerName, avatar, callType, onAccept, onReject }) => {
    const avatarSrc = avatar ? `https://unlimitedpossibilities12.org${avatar}` : null;
    const initials = callerName ? callerName.charAt(0).toUpperCase() : '';

    // Определяем заголовок и иконку в зависимости от типа вызова
    const getNotificationDetails = () => {
        switch (callType) {
            case 'video':
                return { title: 'Incoming Video Call', Icon: FaVideo };
            case 'translate':
                return { title: 'Incoming Translate Call', Icon: FaLanguage };
            case 'group':
                return { title: 'Incoming Group Call', Icon: FaUsers };
            default:
                return { title: 'Incoming Voice Call', Icon: FaPhone };
        }
    };

    const { title, Icon } = getNotificationDetails();

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
                <h2>{title} from {callerName}</h2>
                <div className="notification-icon-container">
                    <Icon className="notification-icon" />
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