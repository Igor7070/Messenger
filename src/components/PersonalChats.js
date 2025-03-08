import React, { useState, useEffect } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import '../styles/PersonalChats.css';

const PersonalChats = () => {
    const { userId } = useParams();
    const navigate = useNavigate();
    const location = useLocation();
    const [privateChats, setPrivateChats] = useState([]);
    const [chatPartners, setChatPartners] = useState({});
    const [contextMenu, setContextMenu] = useState({ visible: false, chatId: null, x: 0, y: 0 });

    const currentUserName = location.state?.currentUserName || 'User';

    useEffect(() => {
        const fetchPrivateChats = async () => {
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}/private-chats`);
                if (response.ok) {
                    const chats = await response.json();
                    setPrivateChats(chats);
                    fetchChatPartners(chats);
                } else {
                    console.error('Failed to fetch private chats:', response.statusText);
                }
            } catch (error) {
                console.error('Error fetching private chats:', error);
            }
        };

        fetchPrivateChats();
    }, [userId]);

    const fetchChatPartners = async (chats) => {
        const partners = {};
        for (const chat of chats) {
            const partnerName = getChatPartnerName(chat.name);
            try {
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/by-username/${partnerName}`);
                if (response.ok) {
                    const userData = await response.json();
                    partners[chat.id] = userData;
                } else {
                    console.error(`Failed to fetch user ${partnerName}:`, response.statusText);
                }
            } catch (error) {
                console.error(`Error fetching user ${partnerName}:`, error);
            }
        }
        setChatPartners(partners);
    };

    const handleChatClick = (chatId) => {
        navigate(`/private-chat/${chatId}`);
    };

    const getChatPartnerName = (chatName) => {
        const names = chatName.split('_');
        return names.find(name => name !== currentUserName);
    };

    const handleContextMenu = (event, chatId) => {
        event.preventDefault();
        setContextMenu({
            visible: true,
            chatId: chatId,
            x: event.pageX,
            y: event.pageY,
        });
    };

    const handleDeleteChat = async () => {
        const confirmDelete = window.confirm("Are you sure you want to delete this chat?");
        if (confirmDelete) {
            await deleteChat(contextMenu.chatId);
        }
        setContextMenu({ ...contextMenu, visible: false }); // Скрываем контекстное меню
    };

    const deleteChat = async (chatId) => {
        try {
            const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${userId}/private-chats/${chatId}`, {
                method: 'DELETE',
            });
            
            if (response.ok) {
                setPrivateChats(prevChats => prevChats.filter(chat => chat.id !== chatId));
                console.log("Chat deleted from user list successfully");

                const checkResponse = await fetch(`https://unlimitedpossibilities12.org/api/users/private-chats/check/${chatId}`, {
                    method: 'DELETE',
                });

                const result = await checkResponse.text();
                console.log(result);
            } else {
                console.error('Failed to delete chat from user list:', response.statusText);
            }
        } catch (error) {
            console.error('Error deleting chat:', error);
        }
    };

    const closeContextMenu = () => {
        setContextMenu({ ...contextMenu, visible: false });
    };

    return (
        <div className="personal-chats-container" onClick={closeContextMenu}>
            <h1 className="custom-h1">{`${currentUserName}'s private chats`}</h1>
            <ul>
                {privateChats.map(chat => {
                    const partner = chatPartners[chat.id] || {};
                    const avatarSrc = partner.avatar ? `https://unlimitedpossibilities12.org${partner.avatar}` : null;
                    const initials = partner.username ? partner.username.charAt(0).toUpperCase() : '';
                    const partnerName = getChatPartnerName(chat.name);

                    return (
                        <li key={chat.id} onClick={() => handleChatClick(chat.id)} 
                            onContextMenu={(event) => handleContextMenu(event, chat.id)} 
                            className="chat-item1">
                            {avatarSrc ? (
                                <img src={avatarSrc} alt={`${partnerName} avatar`} className="chat-avatar" />
                            ) : (
                                <div className="chat-avatar">
                                    {initials}
                                </div>
                            )}
                            <span className="chat-name-list-item">{partnerName}</span>
                        </li>
                    );
                })}
            </ul>

            {contextMenu.visible && (
                <div 
                    className="context-menu" 
                    style={{ top: contextMenu.y, left: contextMenu.x }}
                >
                    <button onClick={handleDeleteChat}>Delete chat</button>
                </div>
            )}
        </div>
    );
};

export default PersonalChats;