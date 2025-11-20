import React from 'react';
import { FaPhone, FaVideo, FaLanguage, FaUsers, FaLock } from 'react-icons/fa';
import '../styles/IncomingCallNotification.css';

const IncomingCallNotification = ({ callerId, callerName, avatar, callType, onAccept, onReject }) => {
    const avatarSrc = avatar ? `https://unlimitedpossibilities12.org${avatar}` : null;
    const initials = callerName ? callerName.charAt(0).toUpperCase() : '';

    // Определяем заголовок и иконку в зависимости от типа вызова
    const getNotificationDetails = () => {
        switch (callType) {
            case 'video':
                return { title: 'Входящий видеозвонок', Icon: FaVideo };
            case 'translate':
                return { title: 'Входящий звонок с переводом', Icon: FaLanguage };
            case 'group':
                return { title: 'Входящий групровой звонок', Icon: FaUsers };
            case 'voice_secure': // ← НОВЫЙ ТИП!
                return { 
                    title: 'Защищённый звонок (E2EE)', 
                    Icon: FaLock,
                    iconColor: '#00ff00',
                    titleColor: '#00ff00'
                };
            default:
                return { title: 'Входящий голосовой звонок', Icon: FaPhone };
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
                    {title} от {callerName}
                </h2>
                <div className="notification-icon-container">
                    <Icon className="notification-icon" style={{ color: iconColor }} />
                </div>
            </div>

            <div className="button-container">
                <button className="accept-button" onClick={onAccept}>
                    Принять
                </button>
                <button className="reject-button" onClick={onReject}>
                    Отклонить
                </button>
            </div>
        </div>
    );
};

export default IncomingCallNotification;