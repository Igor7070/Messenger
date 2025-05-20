import React, { createContext, useState, useContext, useEffect } from 'react';

const UserInteractionContext = createContext();

export const UserInteractionProvider = ({ children }) => {
    const [userInteracted, setUserInteracted] = useState(() => {
        // Загружаем состояние из localStorage
        const saved = localStorage.getItem('userInteracted');
        return saved === 'true'; // Если сохраненное значение 'true', то возвращаем true
    });

    const handleInteraction = () => {
        setUserInteracted(true);
    };

    // Сохраняем состояние в localStorage при изменении
    useEffect(() => {
        localStorage.setItem('userInteracted', userInteracted);
    }, [userInteracted]);

    return (
        <UserInteractionContext.Provider value={{ userInteracted, handleInteraction }}>
            {children}
        </UserInteractionContext.Provider>
    );
};

export const useUserInteraction = () => {
    return useContext(UserInteractionContext);
};