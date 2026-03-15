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

  console.log('[DEBUG] typeof useState:', typeof useState);
  console.log('[DEBUG] useState === React.useState:', useState === React.useState);

  console.log('[DEBUG] Перед useState mode');
  const [mode, setMode] = useState('chat');  // упростил тип, но можно вернуть

  console.log('[DEBUG] Перед useState messages');
  const [messages, setMessages] = useState([]);  // ← ВРЕМЕННО ПУСТОЙ МАССИВ, чтобы проверить

  console.log('[DEBUG] Перед useState inputValue');
  const [inputValue, setInputValue] = useState('');

  console.log('[DEBUG] Перед useState isLoading');
  const [isLoading, setIsLoading] = useState(false);

  console.log('[DEBUG] Перед useRef messagesEndRef');
  const messagesEndRef = useRef(null);

  // ─── Переводчик ────────────────────────────────────────────
  console.log('[DEBUG] Перед useState isRecording');
  const [isRecording, setIsRecording] = useState(false);

  console.log('[DEBUG] Перед useState recordingTimer');
  const [recordingTimer, setRecordingTimer] = useState(0);

  console.log('[DEBUG] Перед useState originalText');
  const [originalText, setOriginalText] = useState('');

  console.log('[DEBUG] Перед useState translatedText');
  const [translatedText, setTranslatedText] = useState('');

  console.log('[DEBUG] Перед useState translatorLang');
  const [translatorLang, setTranslatorLang] = useState('auto');

  console.log('[DEBUG] translatorLang после инициализации:', typeof translatorLang, translatorLang);

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

  console.log('[DEBUG] Перед currentLangOption, translatorLang =', translatorLang);
  const currentLangOption = 
    typeof translatorLang === 'string' && translatorLang
      ? languages.find(l => l.value === translatorLang) || languages[0]
      : languages[0];

  // Защита от испорченного значения
  useEffect(() => {
    if (typeof translatorLang !== 'string' || !languages.some(l => l.value === translatorLang)) {
      console.warn('[ЗАЩИТА] translatorLang испорчен → сбрасываем на auto', translatorLang);
      setTranslatorLang('auto');
    }
  }, [translatorLang]);

  // ─── Скролл вниз в чате ────────────────────────────────────
  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    if (mode === 'chat') scrollToBottom();
  }, [messages, mode]);

  // ─── Остальные функции без изменений (sendMessage, startRecording, stopRecording и т.д.) ──
  // ... (оставляем как было, чтобы не удлинять код)

  if (!isOpen) return null;

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
              {isFullScreen ? <ArrowsPointingInIcon className="w-6 h-6" /> : <ArrowsPointingOutIcon className="w-6 h-6" />}
            </button>

            <div className="relative">
              <button className="icon-btn" onClick={() => {}}>
                <EllipsisVerticalIcon className="w-6 h-6" />
              </button>
            </div>

            <button onClick={closeAssistant} className="icon-btn">✕</button>
          </div>
        </div>

        {/* Переключение режимов */}
        <AnimatePresence mode="wait">
          {mode === 'chat' ? (
            <motion.div key="chat" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col flex-1">
              <div className="assistant-messages">
                {messages.map((msg, i) => (
                  <div key={i} className={`message ${msg.role}`}>
                    <div className="message-bubble">{msg.content}</div>
                  </div>
                ))}
                {isLoading && <div className="typing"><span></span><span></span><span></span></div>}
                <div ref={messagesEndRef} />
              </div>

              <div className="assistant-input">
                <input
                  value={inputValue}
                  onChange={e => setInputValue(e.target.value)}
                  onKeyPress={e => e.key === 'Enter' && !e.shiftKey && (e.preventDefault(), sendMessage())}
                  placeholder="Напиши сообщение..."
                  disabled={isLoading}
                />
                <button onClick={sendMessage} disabled={isLoading || !inputValue.trim()}>➤</button>
              </div>
            </motion.div>
          ) : (
            <motion.div key="translator" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="translator-mode">
              <Select
                options={languages}
                value={currentLangOption}
                onChange={(option) => {
                  console.log('[DEBUG Select onChange] option:', option);
                  setTranslatorLang(option ? option.value : 'auto');
                }}
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

              {/* Остальной переводчик без изменений */}
              <div className="record-container">
                {/* ... */}
              </div>
              <div className="translation-result">
                {/* ... */}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </AnimatePresence>
  );
};

export default AssistantChatWindow;