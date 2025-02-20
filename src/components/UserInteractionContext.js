import React, { createContext, useState, useContext } from 'react';

const UserInteractionContext = createContext();

export const UserInteractionProvider = ({ children }) => {
    const [userInteracted, setUserInteracted] = useState(false);

    const handleInteraction = () => {
        setUserInteracted(true);
    };

    return (
        <UserInteractionContext.Provider value={{ userInteracted, handleInteraction }}>
            {children}
        </UserInteractionContext.Provider>
    );
};

export const useUserInteraction = () => {
    return useContext(UserInteractionContext);
};