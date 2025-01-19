import React from 'react';
import '../styles/Message.css';

const Message = ({ sender, content, fileUrl, delivered, read, onDownload }) => {
    return (
        <div className="message">
            <strong>{sender.username}: </strong>
            <span>{content}</span>
            {fileUrl && (
                <a href={fileUrl} download onClick={onDownload}>📥 Download file</a>
            )}
            <div className="message-status">
                {delivered && <span className="delivery-indicator">✔️</span>}
                {read && <span className="read-indicator">✔️</span>}
            </div>
        </div>
    );
};

export default Message;