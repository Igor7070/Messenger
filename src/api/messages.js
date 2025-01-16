const API_URL = 'https://unlimitedpossibilities12.org/api/messages';
const API_CHATS_URL = 'https://unlimitedpossibilities12.org/api/chats'; // URL для чатов

export const getMessages = async (chatId) => {
    const response = await fetch(`${API_CHATS_URL}/${chatId}/messages`);
    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Failed to fetch messages: ${errorText}`);
    }
    return await response.json();
};

export const sendMessage = async (message) => {
    const response = await fetch(API_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(message),
    });
    if (!response.ok) throw new Error('Failed to send message');
    return await response.json();
};

// Добавляем функцию для удаления сообщения
export const deleteMessage = async (id) => {
    const response = await fetch(`${API_URL}/${id}`, {
        method: 'DELETE',
    });
    if (!response.ok) throw new Error('Failed to delete message');
};

export const createUser = async (user) => {
    const response = await fetch('https://unlimitedpossibilities12.org/api/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(user),
    });
    if (!response.ok) throw new Error('Failed to create user');
    return await response.json();
};

export const loginUser = async (user) => {
    const response = await fetch('https://unlimitedpossibilities12.org/api/users/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(user),
    });

    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText);
    }
    
    return await response.json();
};

export const createChat = async (chat) => {
    const response = await fetch('https://unlimitedpossibilities12.org/api/chats', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(chat),
    });

    if (!response.ok) {
        throw new Error('Failed to create chat');
    }

    const data = await response.json();
    return data;
};
