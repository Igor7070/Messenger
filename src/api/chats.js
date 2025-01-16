export const getChatById = async (chatId) => {
    const response = await fetch(`https://unlimitedpossibilities12.org/api/chats/${chatId}`);
    if (!response.ok) {
        throw new Error('Ошибка при получении данных о чате');
    }
    return await response.json();
};