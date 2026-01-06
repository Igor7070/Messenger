import React, { createContext, useContext, useState } from 'react';

const AssistantContext = createContext();

export const AssistantProvider = ({ children }) => {
  const [isOpen, setIsOpen] = useState(false);
  const [isFullScreen, setIsFullScreen] = useState(false);

  const openAssistant = () => {
    setIsOpen(true);
    setIsFullScreen(false);
  };

  const closeAssistant = () => setIsOpen(false);

  const toggleFullScreen = () => setIsFullScreen(prev => !prev);

  return (
    <AssistantContext.Provider value={{
      isOpen,
      isFullScreen,
      openAssistant,
      closeAssistant,
      toggleFullScreen
    }}>
      {children}
    </AssistantContext.Provider>
  );
};

export const useAssistant = () => useContext(AssistantContext);