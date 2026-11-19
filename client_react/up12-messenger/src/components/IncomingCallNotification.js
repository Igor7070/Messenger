import React from 'react';
import { FaPhone, FaVideo, FaLanguage, FaUsers, FaLock } from 'react-icons/fa';
import '../styles/IncomingCallNotification.css';

const IncomingCallNotification = ({ 
    callerId, 
    callerName, 
    avatar, 
    callType, 
    onAccept, 
    onReject,
    onSilence
}) => {
    const [silenced, setSilenced] = React.useState(false);
    const avatarSrc = avatar ? `https://unlimitedpossibilities12.org${avatar}` : null;
    const initials = callerName ? callerName.charAt(0).toUpperCase() : '';

    const getNotificationDetails = () => {
        switch (callType) {
            case 'video':
                return { 
                    title: 'Incoming Video Call', 
                    Icon: FaVideo, 
                    accentColor: '#22d3ee' 
                };
            case 'translate':
                return { 
                    title: 'Incoming Translate Call', 
                    Icon: FaLanguage, 
                    accentColor: '#a78bfa' 
                };
            case 'group':
                return { 
                    title: 'Incoming Group Call', 
                    Icon: FaUsers, 
                    accentColor: '#f472b6' 
                };
            case 'voice_secure':
                return { 
                    title: 'Secure Voice Call (E2EE)', 
                    Icon: FaLock, 
                    accentColor: '#4ade80' 
                };
            default:
                return { 
                    title: 'Incoming Voice Call', 
                    Icon: FaPhone, 
                    accentColor: '#67e8f9' 
                };
        }
    };

    const { title, Icon, accentColor } = getNotificationDetails();

    return (
        <div className="incoming-call-notification">
            <div className="notification-content">
                {/* Аватар */}
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

                {/* Заголовок и иконка */}
                <div className="notification-header">
                    <h2>
                        <Icon className="notification-icon" style={{ color: accentColor }} />
                        {title}
                    </h2>
                    <p className="caller-name">from <strong>{callerName}</strong></p>
                </div>

                {/* Кнопки — логика полностью как в старом коде */}
                <div className="button-container">
                    <button className="reject-button" onClick={onReject}>
                        Reject
                    </button>
                    <button className="accept-button" onClick={onAccept}>
                        Accept
                    </button>
                </div>
                {/* Приглушить, не отвечая и не отказывая, — то же, что
                    «Silence» на телефоне: звонок остаётся на виду, а музыка
                    замолкает. Без этого собеседнику оставалось либо отбиться,
                    либо слушать */}
                {onSilence && (
                    <button
                        type="button"
                        className="silence-button"
                        disabled={silenced}
                        onClick={() => { onSilence(); setSilenced(true); }}
                    >
                        {silenced ? 'Silenced' : 'Silence'}
                    </button>
                )}
            </div>
        </div>
    );
};

export default IncomingCallNotification;