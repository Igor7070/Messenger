import React, { useState, useEffect, useRef } from 'react';
import { useAssistant } from '../context/AssistantContext';
import { motion, AnimatePresence } from 'framer-motion';
import Select from 'react-select';
import toast from 'react-hot-toast';

import {
  EllipsisVerticalIcon,
  ArrowsPointingOutIcon,
  ArrowsPointingInIcon,
  MicrophoneIcon,
  StopIcon,
  PlayIcon,
} from '@heroicons/react/24/outline';

import '../styles/AssistantChatWindow.css';

const AssistantChatWindow = () => {
  const { isOpen, isFullScreen, closeAssistant, toggleFullScreen } = useAssistant();

  // ─── Общие состояния ───────────────────────────────────────
  const [mode, setMode] = useState<'chat' | 'translator'>('chat');
  const [messages, setMessages] = useState([
    { role: 'assistant', content: 'Привет! Я твой умный ассистент.' },
    { role: 'assistant', content: 'Могу помочь с переводами, анализом чатов, резюме и многим другим.' },
    { role: 'assistant', content: 'Спрашивай что угодно ⚡' }
  ]);
  const [inputValue, setInputValue] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const messagesEndRef = useRef(null);

  // ─── Переводчик ────────────────────────────────────────────
  const [isRecording, setIsRecording] = useState(false);
  const [recordingTimer, setRecordingTimer] = useState(0);
  const [originalText, setOriginalText] = useState('');
  const [translatedText, setTranslatedText] = useState('');
  const [translatorLang, setTranslatorLang] = useState('auto');

  const mediaRecorderRef = useRef(null);
  const audioChunksRef = useRef([]);
  const timerIntervalRef = useRef(null);

  const languages = [
    { value: 'auto', label: 'Авто 🌐' },
    { value: 'en', label: 'English 🇬🇧' },
    { value: 'ru', label: 'Русский 🇷🇺' },
    { value: 'uk', label: 'Українська 🇺🇦' },
    { value: 'es', label: 'Español 🇪🇸' },
    { value: 'fr', label: 'Français 🇫🇷' },
    { value: 'de', label: 'Deutsch 🇩🇪' },
    { value: 'it', label: 'Italiano 🇮🇹' },
    { value: 'zh', label: '中文 🇨🇳' },
    { value: 'ja', label: '日本語 🇯🇵' },
  ];

  // ─── Функции, которые используются в JSX, должны быть подняты выше ──
  const sendMessage = async () => {
    if (!inputValue.trim() || isLoading) return;

    const userMessage = inputValue.trim();
    setMessages(prev => [...prev, { role: 'user', content: userMessage }]);
    setInputValue('');
    setIsLoading(true);

    try {
      const res = await fetch('https://unlimitedpossibilities12.org/api/communicating_with_a_advanced_ai_model', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: userMessage }),
      });

      if (!res.ok) throw new Error('Ошибка сервера');

      const result = await res.text();
      setMessages(prev => [...prev, { role: 'assistant', content: result }]);
    } catch (err) {
      console.error(err);
      setMessages(prev => [...prev, { role: 'assistant', content: 'Извини, что-то пошло не так. Попробуй ещё раз.' }]);
    } finally {
      setIsLoading(false);
    }
  };

  // ─── Остальные функции (можно оставить ниже, они не используются в JSX напрямую) ──
  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  // ... startRecording, stopRecording, sendVoiceToTranslator, speakText — оставляем как есть

  if (!isOpen) return null;

  // Безопасный выбор текущей опции языка
  const currentLangOption = languages.find(l => l.value === translatorLang) || languages[0];

  return (
    <AnimatePresence>
      <motion.div
        initial={{ y: '100%', opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={{ y: '100%', opacity: 0 }}
        transition={{ type: 'spring', damping: 25, stiffness: 300 }}
        className={`assistant-window ${isFullScreen ? 'full-screen' : 'mini'}`}
      >
        {/* Шапка */}
        <div className="assistant-header">
          <div className="assistant-title">
            <div className="assistant-avatar">⚡</div>
            <span>{mode === 'chat' ? 'AI Ассистент' : 'Голосовой переводчик'}</span>
          </div>

          <div className="assistant-actions">
            <button onClick={toggleFullScreen} className="icon-btn">
              {isFullScreen 
                ? <ArrowsPointingInIcon className="w-6 h-6" /> 
                : <ArrowsPointingOutIcon className="w-6 h-6" />
              }
            </button>

            <div className="relative">
              <button className="icon-btn" onClick={() => {/* меню режимов */}}>
                <EllipsisVerticalIcon className="w-6 h-6" />
              </button>
            </div>

            <button onClick={closeAssistant} className="icon-btn">✕</button>
          </div>
        </div>

        {/* Переключение режимов */}
        <AnimatePresence mode="wait">
          {mode === 'chat' ? (
            <motion.div
              key="chat"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="flex flex-col flex-1"
            >
              {/* Сообщения чата */}
              <div className="assistant-messages">
                {messages.map((msg, i) => (
                  <div key={i} className={`message ${msg.role}`}>
                    <div className="message-bubble">{msg.content}</div>
                  </div>
                ))}
                {isLoading && <div className="typing"><span></span><span></span><span></span></div>}
                <div ref={messagesEndRef} />
              </div>

              {/* Поле ввода */}
              <div className="assistant-input">
                <input
                  value={inputValue}
                  onChange={e => setInputValue(e.target.value)}
                  onKeyPress={e => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      sendMessage();   // ← теперь функция доступна
                    }
                  }}
                  placeholder="Напиши сообщение..."
                  disabled={isLoading}
                />
                <button 
                  onClick={sendMessage}   // ← тоже доступна
                  disabled={isLoading || !inputValue.trim()}
                >
                  ➤
                </button>
              </div>
            </motion.div>
          ) : (
            <motion.div
              key="translator"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="translator-mode"
            >
              <Select
                options={languages}
                value={currentLangOption}
                onChange={opt => setTranslatorLang(opt?.value ?? 'auto')}
                className="language-select mb-6"
                classNamePrefix="react-select"
                styles={{
                  control: (base) => ({ ...base, backgroundColor: '#1e293b', borderColor: '#334155', color: 'white' }),
                  menu: (base) => ({ ...base, backgroundColor: '#0f172a', color: 'white' }),
                  option: (base, state) => ({
                    ...base,
                    backgroundColor: state.isSelected ? '#2563eb' : state.isFocused ? '#334155' : '#0f172a',
                    color: 'white',
                  }),
                  singleValue: (base) => ({ ...base, color: 'white' }),
                }}
              />

              {/* Кнопка записи и результаты перевода — оставляем как было */}
              {/* ... */}
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </AnimatePresence>
  );
};

export default AssistantChatWindow;