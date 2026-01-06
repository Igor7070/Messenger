import React from 'react';
import { useAssistant } from '../../context/AssistantContext';
import { motion, AnimatePresence } from 'framer-motion';
import './AssistantChatWindow.css';

const AssistantChatWindow = () => {
  const { isOpen, isFullScreen, closeAssistant, toggleFullScreen } = useAssistant();

  if (!isOpen) return null;

  return (
    <AnimatePresence>
      <motion.div
        initial={{ y: "100%", opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={{ y: "100%", opacity: 0 }}
        transition={{ type: "spring", damping: 25, stiffness: 300 }}
        className={`assistant-window ${isFullScreen ? 'full-screen' : 'mini'}`}
      >
        {/* Шапка */}
        <div className="assistant-header">
          <div className="assistant-title">
            <div className="assistant-avatar">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                <path d="M13 2L4.09344 12.6879C3.74461 13.1064 3.5702 13.3157 3.56738 13.4925C3.5649 13.6461 3.63372 13.7923 3.75324 13.8889C3.89073 14 4.16316 14 4.70803 14H12L11 22L19.9066 11.3121C20.2554 10.8936 20.4298 10.6843 20.4326 10.5075C20.4351 10.3539 20.3663 10.2077 20.2468 10.1111C20.1093 10 19.8368 10 19.292 10H12L13 2Z" 
                      fill="#60A5FA"/>
              </svg>
            </div>
            <span>ИИ-Ассистент</span>
            <span className="online-dot"></span>
          </div>
          <div className="assistant-actions">
            <button onClick={toggleFullScreen} className="icon-btn">
              {isFullScreen ? '↙' : '↗'}
            </button>
            <button onClick={closeAssistant} className="icon-btn">✕</button>
          </div>
        </div>

        {/* Тело чата — пока заглушка */}
        <div className="assistant-body">
          <div className="welcome-message">
            <p>Привет! Я твой умный ассистент.</p>
            <p>Могу помочь с резюме, переводами, анализом чатов и многим другим.</p>
            <p>Спрашивай что угодно ⚡</p>
          </div>
        </div>

        {/* Поле ввода */}
        <div className="assistant-input">
          <input type="text" placeholder="Напиши сообщение..." disabled />
          <button className="mic-btn">🎤</button>
          <button className="send-btn">➤</button>
        </div>
      </motion.div>
    </AnimatePresence>
  );
};

export default AssistantChatWindow;