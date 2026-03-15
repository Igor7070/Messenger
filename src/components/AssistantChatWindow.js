import React, { useState, useEffect, useRef } from 'react';
import { useAssistant } from '../context/AssistantContext';
import { motion, AnimatePresence } from 'framer-motion';
import Select from 'react-select';
import toast from 'react-hot-toast';

// Heroicons v2 — outline стиль 24px
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
  const [mode, setMode] = useState('chat');  // ← ИСПРАВЛЕНО: убрали <'chat' | 'translator'>
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
  const [selectedLanguage, setSelectedLanguage] = useState('auto');

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

  // ─── Безопасный выбор языка (защита от undefined) ──────────
  const currentLangOption = languages.find(l => l.value === selectedLanguage) || languages[0];

  // Если вдруг язык пропал из списка — сбрасываем на auto
  useEffect(() => {
    if (!languages.some(l => l.value === selectedLanguage)) {
      setSelectedLanguage('auto');
    }
  }, [selectedLanguage]);

  // ─── Скролл вниз в чате ────────────────────────────────────
  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    if (mode === 'chat') scrollToBottom();
  }, [messages, mode]);

  // ─── Отправка текстового сообщения ─────────────────────────
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

  // ─── Запись голоса ─────────────────────────────────────────
  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorderRef.current = new MediaRecorder(stream);
      audioChunksRef.current = [];

      mediaRecorderRef.current.ondataavailable = e => {
        audioChunksRef.current.push(e.data);
      };

      mediaRecorderRef.current.start();
      setIsRecording(true);
      setRecordingTimer(0);

      timerIntervalRef.current = setInterval(() => {
        setRecordingTimer(prev => prev + 1);
      }, 1000);

      toast.success('Запись началась...');
    } catch (err) {
      console.error('Нет доступа к микрофону', err);
      toast.error('Не удалось получить доступ к микрофону');
    }
  };

  const stopRecording = () => {
    if (!mediaRecorderRef.current) return;

    mediaRecorderRef.current.stop();
    mediaRecorderRef.current.onstop = async () => {
      const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/mp3' });
      audioChunksRef.current = [];
      setIsRecording(false);
      clearInterval(timerIntervalRef.current);

      if (audioBlob.size < 1000) {
        toast.error('Запись слишком короткая');
        return;
      }

      await sendVoiceToTranslator(audioBlob);
    };
  };

  // ─── Отправка аудио на сервер ──────────────────────────────
  const sendVoiceToTranslator = async (audioBlob) => {
    const formData = new FormData();
    formData.append('audio', audioBlob, 'voice.mp3');

    try {
      setIsLoading(true);
      const res = await fetch(
        `https://unlimitedpossibilities12.org/api/global-assistant/translate-voice?targetLanguage=${selectedLanguage}&sourceLanguage=auto&withTts=true`,
        {
          method: 'POST',
          body: formData,
        }
      );

      if (!res.ok) throw new Error('Ошибка сервера');

      const data = await res.json();

      setOriginalText(data.original || 'Не распознано');
      setTranslatedText(data.translated || 'Перевод не удался');

      // Авто-воспроизведение перевода
      if (data.audioBase64) {
        const cleanBase64 = data.audioBase64.includes(',') 
          ? data.audioBase64.split(',')[1] 
          : data.audioBase64;

        const audio = new Audio(`data:audio/mp3;base64,${cleanBase64}`);
        audio.play().catch(e => {
          console.error('Автопроигрывание не удалось', e);
          toast('Нажми кнопку воспроизведения', { icon: '🔊' });
        });
      }
    } catch (err) {
      console.error(err);
      toast.error('Ошибка перевода');
    } finally {
      setIsLoading(false);
    }
  };

  // ─── Воспроизведение текста через TTS ──────────────────────
  const speakText = async (text, lang) => {
    if (!text) return;

    try {
      const res = await fetch('https://unlimitedpossibilities12.org/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, language: lang }),
      });

      if (!res.ok) throw new Error('Ошибка TTS сервера');

      const data = await res.json();
      let base64 = data.audio;

      if (base64?.includes(',')) {
        base64 = base64.split(',')[1];
      }

      const audio = new Audio(`data:audio/mp3;base64,${base64}`);
      audio.play().catch(e => {
        console.error('Ошибка воспроизведения', e);
        toast.error('Не удалось воспроизвести голос');
      });
    } catch (err) {
      console.error(err);
      toast.error('Ошибка TTS');
    }
  };

  if (!isOpen) return null;

  return (
    <AnimatePresence>
      <motion.div
        initial={{ y: '100%', opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={{ y: '100%', opacity: 0 }}
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
              <button className="icon-btn" onClick={() => {/* здесь можно открыть меню с выбором режима */}}>
                <EllipsisVerticalIcon className="w-6 h-6" />
              </button>
            </div>

            <button onClick={closeAssistant} className="icon-btn">
              ✕
            </button>
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
                  onKeyPress={e => e.key === 'Enter' && !e.shiftKey && (e.preventDefault(), sendMessage())}
                  placeholder="Напиши сообщение..."
                  disabled={isLoading}
                />
                <button onClick={sendMessage} disabled={isLoading || !inputValue.trim()}>
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
              {/* Выбор языка */}
              <Select
                options={languages}
                value={currentLangOption}
                onChange={opt => setSelectedLanguage(opt.value)}
                className="language-select mb-6"
                classNamePrefix="react-select"
                styles={{
                  control: (base) => ({
                    ...base,
                    backgroundColor: '#1e293b',
                    borderColor: '#334155',
                    color: 'white',
                  }),
                  menu: (base) => ({
                    ...base,
                    backgroundColor: '#0f172a',
                    color: 'white',
                  }),
                  option: (base, state) => ({
                    ...base,
                    backgroundColor: state.isSelected ? '#2563eb' : state.isFocused ? '#334155' : '#0f172a',
                    color: 'white',
                  }),
                  singleValue: (base) => ({ ...base, color: 'white' }),
                }}
              />

              {/* Кнопка записи */}
              <div className="record-container">
                <motion.button
                  whileTap={{ scale: 0.92 }}
                  onClick={isRecording ? stopRecording : startRecording}
                  className={`record-btn ${isRecording ? 'recording' : ''}`}
                >
                  {isRecording ? <StopIcon className="w-16 h-16" /> : <MicrophoneIcon className="w-16 h-16" />}
                  {isRecording && (
                    <div className="timer">
                      {`${Math.floor(recordingTimer / 60).toString().padStart(2, '0')}:${(recordingTimer % 60).toString().padStart(2, '0')}`}
                    </div>
                  )}
                </motion.button>
              </div>

              {/* Результат перевода */}
              <div className="translation-result">
                <div className="translation-block">
                  <div className="label">Оригинал</div>
                  <div className="text-content">
                    {originalText || 'Говори что-нибудь...'}
                    {originalText && (
                      <button 
                        onClick={() => speakText(originalText, 'auto')} 
                        className="play-btn blue"
                      >
                        <PlayIcon className="w-6 h-6" />
                      </button>
                    )}
                  </div>
                </div>

                <div className="translation-block">
                  <div className="label">Перевод</div>
                  <div className="text-content">
                    {translatedText || 'Ожидаем перевод...'}
                    {translatedText && (
                      <button 
                        onClick={() => speakText(translatedText, selectedLanguage)} 
                        className="play-btn green"
                      >
                        <PlayIcon className="w-6 h-6" />
                      </button>
                    )}
                  </div>
                </div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </AnimatePresence>
  );
};

export default AssistantChatWindow;