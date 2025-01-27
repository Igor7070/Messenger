import React from 'react';

const IncomingCallNotification = ({ callerName, onAccept, onReject }) => {
    return (
        <div className="incoming-call-notification">
            <h2>Incoming Call from {callerName}</h2>
            <button onClick={onAccept}>Accept</button>
            <button onClick={onReject}>Reject</button>
        </div>
    );
};

export default IncomingCallNotification;