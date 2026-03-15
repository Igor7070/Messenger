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

  console.log('typeof useState:', typeof useState);
  console.log('useState === React.useState:', useState === React.useState);

  // ─── Общие состояния ───────────────────────────────────────
  const [mode, setMode] = useState('chat');                    // ← УБРАЛ generic
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

  // Безопасный выбор текущей опции
  const currentLangOption = 
    typeof translatorLang === 'string' 
      ? languages.find(l => l.value === translatorLang) || languages[0]
      : languages[0];

  // Защита от порчи значения
  useEffect(() => {
    if (typeof translatorLang !== 'string' || !languages.some(l => l.value === translatorLang)) {
      console.warn('translatorLang испорчен → сбрасываем на auto', translatorLang);
      setTranslatorLang('auto');
    }
  }, [translatorLang]);

  // ─── Скролл ────────────────────────────────────────────────
  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    if (mode === 'chat') scrollToBottom();
  }, [messages, mode]);

  // ─── sendMessage (поднята выше, чтобы ESLint не ругался) ───
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

  // ─── Остальные функции (startRecording, stopRecording, sendVoiceToTranslator, speakText) ──
  // (оставлены как были у тебя — просто скопируй их из предыдущей версии)

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
        {/* Шапка + весь return без изменений */}
        {/* ... (вставь сюда остальную разметку из твоего последнего кода) */}
      </motion.div>
    </AnimatePresence>
  );
};

export default AssistantChatWindow;