import axios from 'axios';
import { setAuthToken, applyAxiosAuth } from '../authToken';

const API_URL = 'https://unlimitedpossibilities12.org/api/users'; // Замените на ваш URL

export const createUser = async (user) => {
    const response = await fetch(API_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(user),
    });
    
    if (!response.ok) {
        // Получаем текст ошибки от сервера
        const errorText = await response.text();
        throw new Error(errorText); // Выбрасываем ошибку с текстом
    }

    const created = await response.json();
    if (created && created.token) { setAuthToken(created.token); applyAxiosAuth(axios); }
    return created;
};