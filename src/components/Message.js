import React from 'react';
import './styles/Message.css';

const Message = ({ sender, content, fileUrl, id, onDownload }) => {
    return (
        <div className="message">
            <strong>{sender.username}: </strong>
            {content}
            {fileUrl && (
                <button onClick={() => onDownload(id)}>📥 Скачать файл</button>
            )}
        </div>
    );
};

export default Message;