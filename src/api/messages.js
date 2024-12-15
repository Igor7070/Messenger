const API_URL = 'https://unlimitedpossibilities12.org/api/messages'; // Замените на ваш URL

export const getMessages = async () => {
    const response = await fetch(API_URL);
    if (!response.ok) throw new Error('Network response was not ok');
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

// Функция для создания пользователя
export const createUser = async (user) => {
    const response = await fetch('https://unlimitedpossibilities12.org/api/users', { // Убедитесь, что URL правильный
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
    if (!response.ok) throw new Error('Failed to log in');
    return await response.json();
};