import React, { useState, useEffect, useRef } from 'react';
import { useAssistant } from '../context/AssistantContext';
import { motion, AnimatePresence } from 'framer-motion';
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

  const [mode, setMode] = useState<'chat' | 'translator'>('chat');
  const [messages, setMessages] = useState([
    { role: 'assistant', content: 'Привет! Я твой умный ассистент.' },
    { role: 'assistant', content: 'Могу помочь с переводами, анализом чатов, резюме и многим другим.' },
    { role: 'assistant', content: 'Спрашивай что угодно ⚡' }
  ]);
  const [inputValue, setInputValue] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const messagesEndRef = useRef(null);

  // Переводчик
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

  // Защита от мусора в состоянии
  useEffect(() => {
    if (typeof translatorLang !== 'string' || !languages.some(l => l.value === translatorLang)) {
      console.warn('translatorLang был некорректным → исправлено на auto', translatorLang);
      setTranslatorLang('auto');
    }
  }, [translatorLang]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    if (mode === 'chat') scrollToBottom();
  }, [messages, mode]);

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

      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const result = await res.text();
      setMessages(prev => [...prev, { role: 'assistant', content: result }]);
    } catch (err) {
      console.error(err);
      setMessages(prev => [...prev, { role: 'assistant', content: 'Извини, ошибка. Попробуй ещё раз.' }]);
    } finally {
      setIsLoading(false);
    }
  };

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorderRef.current = new MediaRecorder(stream);
      audioChunksRef.current = [];

      mediaRecorderRef.current.ondataavailable = e => audioChunksRef.current.push(e.data);
      mediaRecorderRef.current.start();
      setIsRecording(true);
      setRecordingTimer(0);

      timerIntervalRef.current = setInterval(() => setRecordingTimer(p => p + 1), 1000);
      toast.success('Запись началась');
    } catch (err) {
      toast.error('Нет доступа к микрофону');
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

  const sendVoiceToTranslator = async (audioBlob) => {
    const formData = new FormData();
    formData.append('audio', audioBlob, 'voice.mp3');

    try {
      setIsLoading(true);
      const res = await fetch(
        `https://unlimitedpossibilities12.org/api/global-assistant/translate-voice?targetLanguage=${translatorLang}&sourceLanguage=auto&withTts=true`,
        { method: 'POST', body: formData }
      );

      if (!res.ok) throw new Error(`HTTP ${res.status}`);

      const data = await res.json();

      setOriginalText(data.original || 'Не распознано');
      setTranslatedText(data.translated || 'Перевод не удался');

      if (data.audioBase64) {
        const cleanBase64 = data.audioBase64.includes(',') ? data.audioBase64.split(',')[1] : data.audioBase64;
        const audio = new Audio(`data:audio/mp3;base64,${cleanBase64}`);
        audio.play().catch(() => toast('Нажми ▶ для прослушивания'));
      }
    } catch (err) {
      console.error(err);
      toast.error('Ошибка перевода');
    } finally {
      setIsLoading(false);
    }
  };

  const speakText = async (text, lang) => {
    if (!text?.trim()) return;

    try {
      const res = await fetch('https://unlimitedpossibilities12.org/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, language: lang }),
      });

      if (!res.ok) throw new Error(`TTS HTTP ${res.status}`);

      const data = await res.json();
      let base64 = data.audio || '';

      if (base64.includes(',')) base64 = base64.split(',')[1];
      if (!base64) return toast.error('Нет аудио');

      const audio = new Audio(`data:audio/mp3;base64,${base64}`);
      audio.play().catch(() => toast('Не удалось воспроизвести'));
    } catch (err) {
      console.error(err);
      toast.error('Ошибка воспроизведения');
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
              {isFullScreen ? <ArrowsPointingInIcon className="w-6 h-6" /> : <ArrowsPointingOutIcon className="w-6 h-6" />}
            </button>
            <button className="icon-btn" onClick={() => toast('Меню в разработке')}>
              <EllipsisVerticalIcon className="w-6 h-6" />
            </button>
            <button onClick={closeAssistant} className="icon-btn">✕</button>
          </div>
        </div>

        <AnimatePresence mode="wait">
          {mode === 'chat' ? (
            <motion.div key="chat" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col flex-1">
              <div className="assistant-messages">
                {messages.map((msg, i) => (
                  <div key={i} className={`message ${msg.role}`}>
                    <div className="message-bubble">{msg.content}</div>
                  </div>
                ))}
                {isLoading && <div className="typing"><span /><span /><span /></div>}
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
            <motion.div key="translator" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="translator-mode p-4">
              {/* Нативный select вместо react-select */}
              <select
                value={translatorLang}
                onChange={e => setTranslatorLang(e.target.value)}
                className="w-full p-3 bg-gray-800 border border-gray-700 rounded-lg text-white mb-6 focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                {languages.map(lang => (
                  <option key={lang.value} value={lang.value}>
                    {lang.label}
                  </option>
                ))}
              </select>

              <div className="record-container flex justify-center my-8">
                <motion.button
                  whileTap={{ scale: 0.92 }}
                  onClick={isRecording ? stopRecording : startRecording}
                  className={`record-btn ${isRecording ? 'recording' : ''} relative`}
                  disabled={isLoading}
                >
                  {isRecording ? <StopIcon className="w-16 h-16" /> : <MicrophoneIcon className="w-16 h-16" />}
                  {isRecording && (
                    <div className="absolute -bottom-10 text-white font-mono text-sm">
                      {`${Math.floor(recordingTimer / 60).toString().padStart(2, '0')}:${(recordingTimer % 60).toString().padStart(2, '0')}`}
                    </div>
                  )}
                </motion.button>
              </div>

              <div className="translation-result space-y-6">
                <div className="translation-block bg-gray-800 p-4 rounded-lg">
                  <div className="text-gray-400 mb-1">Оригинал</div>
                  <div className="relative min-h-[60px] whitespace-pre-wrap">
                    {originalText || 'Говори что-нибудь...'}
                    {originalText && (
                      <button
                        onClick={() => speakText(originalText, 'auto')}
                        className="absolute right-3 top-2 text-blue-400 hover:text-blue-300 transition-colors"
                      >
                        <PlayIcon className="w-6 h-6" />
                      </button>
                    )}
                  </div>
                </div>

                <div className="translation-block bg-gray-800 p-4 rounded-lg">
                  <div className="text-gray-400 mb-1">Перевод</div>
                  <div className="relative min-h-[60px] whitespace-pre-wrap">
                    {translatedText || 'Ожидаем перевод...'}
                    {translatedText && (
                      <button
                        onClick={() => speakText(translatedText, translatorLang)}
                        className="absolute right-3 top-2 text-green-400 hover:text-green-300 transition-colors"
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