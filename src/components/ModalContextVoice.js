import React, { createContext, useContext, useState } from 'react';
import '../styles/ModalContextVoice.css';

const ModalContext = createContext();

export const ModalProvider = ({ children }) => {
    const [showModalVoice, setShowModalVoice] = useState(false);

    const openModalVoice = () => setShowModalVoice(true);
    const closeModalVoice = () => setShowModalVoice(false);

    return (
        <ModalContext.Provider value={{ showModalVoice, openModalVoice, closeModalVoice }}>
            {children}
        </ModalContext.Provider>
    );
};

export const useModal = () => {
    return useContext(ModalContext);
};