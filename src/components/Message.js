import React from 'react';
import './styles/Message.css';

const Message = ({ sender, content, fileUrl, id, onDownload }) => {
    return (
        <div className="message">
            <strong>{sender.username}: </strong>
            {content}
            {fileUrl && (
                <a href={fileUrl} download>📥 Скачать файл</a>
            )}
        </div>
    );
};

export default Message;