import React from 'react';
import './styles/Message.css';

const Message = ({ sender, content }) => {
    return (
        <div className="message">
            <strong>{sender.username}: </strong>
            {content}
        </div>
    );
};

export default Message;