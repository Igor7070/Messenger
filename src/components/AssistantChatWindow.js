import React, { useState, useEffect, useRef } from 'react';
import { useAssistant } from '../context/AssistantContext';
import { motion, AnimatePresence } from 'framer-motion';
import '../styles/AssistantChatWindow.css';

const AssistantChatWindow = () => {
  const { isOpen, isFullScreen, closeAssistant, toggleFullScreen } = useAssistant();

  // Режим: 'chat' или 'translator'
  const [mode, setMode] = useState<'chat' | 'translator'>('chat');

  // Чат
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

  // Скролл вниз в чате
  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    if (mode === 'chat') scrollToBottom();
  }, [messages, mode]);

  // Отправка сообщения в чате
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
      setMessages(prev => [...prev, { role: 'assistant', content: 'Извини, не получилось ответить.' }]);
    } finally {
      setIsLoading(false);
    }
  };

  const handleKeyPress = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  // Запись голоса
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
    } catch (err) {
      console.error('Нет доступа к микрофону', err);
      alert('Не удалось получить доступ к микрофону');
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
        alert('Запись слишком короткая');
        return;
      }

      await sendVoiceToTranslator(audioBlob);
    };
  };

  // Отправка аудио на сервер
  const sendVoiceToTranslator = async (audioBlob) => {
    const formData = new FormData();
    formData.append('audio', audioBlob, 'voice.mp3');

    try {
      const res = await fetch(
        `https://unlimitedpossibilities12.org/api/global-assistant/translate-voice?targetLanguage=${selectedLanguage}&sourceLanguage=auto&withTts=true`,
        { method: 'POST', body: formData }
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
        audio.play().catch(() => {
          alert('Нажми кнопку воспроизведения, чтобы услышать перевод');
        });
      }
    } catch (err) {
      console.error(err);
      alert('Ошибка перевода');
    }
  };

  // Воспроизведение текста через TTS
  const speakText = async (text, lang) => {
    if (!text) return;

    try {
      const res = await fetch('https://unlimitedpossibilities12.org/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, language: lang }),
      });

      if (!res.ok) throw new Error('Ошибка TTS');

      const data = await res.json();
      let base64 = data.audio;

      if (base64?.includes(',')) base64 = base64.split(',')[1];

      const audio = new Audio(`data:audio/mp3;base64,${base64}`);
      audio.play();
    } catch (err) {
      console.error(err);
      alert('Не удалось воспроизвести голос');
    }
  };

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
            <span>{mode === 'chat' ? 'AI Ассистент' : 'Голосовой переводчик'}</span>
          </div>

          <div className="assistant-actions">
            <button onClick={toggleFullScreen} className="icon-btn">
              {isFullScreen ? 'Свернуть' : 'Развернуть'}
            </button>

            {/* Кнопка переключения режима */}
            <button 
              onClick={() => setMode(mode === 'chat' ? 'translator' : 'chat')}
              className="icon-btn"
            >
              {mode === 'chat' ? '🎤 Переводчик' : '💬 Чат'}
            </button>

            <button onClick={closeAssistant} className="icon-btn">✕</button>
          </div>
        </div>

        {/* Контент в зависимости от режима */}
        <AnimatePresence mode="wait">
          {mode === 'chat' ? (
            <motion.div
              key="chat"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="flex flex-col flex-1"
            >
              <div className="assistant-messages">
                {messages.map((msg, i) => (
                  <div key={i} className={`message ${msg.role}`}>
                    <div className="message-bubble">{msg.content}</div>
                  </div>
                ))}
                {isLoading && (
                  <div className="message assistant-message">
                    <div className="message-bubble typing">
                      <span></span><span></span><span></span>
                    </div>
                  </div>
                )}
                <div ref={messagesEndRef} />
              </div>

              <div className="assistant-input">
                <input
                  type="text"
                  value={inputValue}
                  onChange={(e) => setInputValue(e.target.value)}
                  onKeyPress={handleKeyPress}
                  placeholder="Напиши сообщение..."
                  disabled={isLoading}
                />
                <button 
                  onClick={sendMessage} 
                  className="send-btn"
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
              {/* Выбор языка */}
              <select
                value={selectedLanguage}
                onChange={e => setSelectedLanguage(e.target.value)}
                className="language-select mb-6"
              >
                {languages.map(lang => (
                  <option key={lang.value} value={lang.value}>
                    {lang.label}
                  </option>
                ))}
              </select>

              {/* Кнопка записи */}
              <div className="record-container">
                <motion.button
                  whileTap={{ scale: 0.92 }}
                  onClick={isRecording ? stopRecording : startRecording}
                  className={`record-btn ${isRecording ? 'recording' : ''}`}
                >
                  {isRecording ? '■' : '●'}
                  {isRecording && (
                    <div className="timer">
                      {`${Math.floor(recordingTimer / 60).toString().padStart(2, '0')}:${(recordingTimer % 60).toString().padStart(2, '0')}`}
                    </div>
                  )}
                </motion.button>
              </div>

              {/* Результат */}
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
                        ▶
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
                        ▶
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