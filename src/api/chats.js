export const getChatById = async (chatId) => {
    const response = await fetch(`https://unlimitedpossibilities12.org/api/chats/${chatId}`);
    if (!response.ok) {
        throw new Error('Failed to load the chat');
    }
    return await response.json();
};