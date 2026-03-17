import React, { useState, useEffect, useRef } from 'react';
import { useAssistant } from '../context/AssistantContext';
import { motion, AnimatePresence } from 'framer-motion';
import Select from 'react-select';
import toast from 'react-hot-toast';
import {
  MicrophoneIcon,
  StopIcon,
  PlayIcon,
} from '@heroicons/react/24/outline';
import '../styles/AssistantChatWindow.css';

const AssistantChatWindow = () => {
  const { isOpen, isFullScreen, closeAssistant, toggleFullScreen } = useAssistant();

  const [mode, setMode] = useState('chat');
  const [showMenu, setShowMenu] = useState(false);

  // Чат
  const [messages, setMessages] = useState([
    { role: 'assistant', content: 'Hi! I am your smart assistant.' },
    { role: 'assistant', content: 'I can help with resumes, translations, chat analysis, and much more.' },
    { role: 'assistant', content: 'Ask anything ⚡' }
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
    { value: 'auto', label: 'Auto 🌐' },
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

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const sendMessage = async () => {
    if (!inputValue.trim() || isLoading) return;

    const userMessage = inputValue.trim();
    setMessages((prev) => [...prev, { role: 'user', content: userMessage }]);
    setInputValue('');
    setIsLoading(true);

    try {
      const response = await fetch('https://unlimitedpossibilities12.org/api/communicating_with_a_advanced_ai_model', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: userMessage }),
      });

      if (!response.ok) throw new Error('Server error');

      const result = await response.text();
      setMessages((prev) => [...prev, { role: 'assistant', content: result }]);
    } catch (error) {
      console.error('Error while requesting AI:', error);
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: 'Sorry, I could not answer. Try again.' },
      ]);
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

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorderRef.current = new MediaRecorder(stream);
      audioChunksRef.current = [];

      mediaRecorderRef.current.ondataavailable = (e) => {
        audioChunksRef.current.push(e.data);
      };

      mediaRecorderRef.current.start();
      setIsRecording(true);
      setRecordingTimer(0);

      timerIntervalRef.current = setInterval(() => {
        setRecordingTimer((prev) => prev + 1);
      }, 1000);

      toast.success('Recording started...');
    } catch (err) {
      console.error('Microphone access denied', err);
      toast.error('Cannot access microphone');
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
        toast.error('Recording is too short');
        return;
      }

      await sendVoiceToTranslator(audioBlob);
    };
  };

  const sendVoiceToTranslator = async (audioBlob) => {
    const formData = new FormData();
    formData.append('audio', audioBlob, 'voice.mp3');

    try {
      const res = await fetch(
        `https://unlimitedpossibilities12.org/api/global-assistant/translate-voice?targetLanguage=${selectedLanguage}&sourceLanguage=auto&withTts=true`,
        { method: 'POST', body: formData }
      );

      if (!res.ok) throw new Error('Server error');

      const data = await res.json();

      setOriginalText(data.original || 'Not recognized');
      setTranslatedText(data.translated || 'Translation failed');

      if (data.audioBase64) {
        const cleanBase64 = data.audioBase64.includes(',')
          ? data.audioBase64.split(',')[1]
          : data.audioBase64;

        const audio = new Audio(`data:audio/mp3;base64,${cleanBase64}`);
        audio.play().catch(() => toast('Tap play button to listen', { icon: '🔊' }));
      }
    } catch (err) {
      console.error(err);
      toast.error('Translation error');
    }
  };

  const speakText = async (text, lang) => {
    if (!text) return;

    try {
      const res = await fetch('https://unlimitedpossibilities12.org/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, language: lang }),
      });

      if (!res.ok) throw new Error('TTS server error');

      const data = await res.json();
      let base64 = data.audio;

      if (base64?.includes(',')) base64 = base64.split(',')[1];

      const audio = new Audio(`data:audio/mp3;base64,${base64}`);
      audio.play();
    } catch (err) {
      console.error(err);
      toast.error('Cannot play voice');
    }
  };

  if (!isOpen) return null;

  return (
    <AnimatePresence>
      <motion.div
        initial={{ y: '100%', opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={{ y: '100%', opacity: 0 }}
        transition={{ type: 'spring', damping: 25, stiffness: 300 }}
        className={`assistant-window ${isFullScreen ? 'full-screen' : 'mini'} relative overflow-visible`}
        //                                       ↑↑↑ relative + overflow-visible — критично!
      >

        {/* Шапка */}
        <div className="assistant-header">
          <div className="assistant-title">
            <div className="assistant-avatar">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                <path
                  d="M13 2L4.09344 12.6879C3.74461 13.1064 3.5702 13.3157 3.56738 13.4925C3.5649 13.6461 3.63372 13.7923 3.75324 13.8889C3.89073 14 4.16316 14 4.70803 14H12L11 22L19.9066 11.3121C20.2554 10.8936 20.4298 10.6843 20.4326 10.5075C20.4351 10.3539 20.3663 10.2077 20.2468 10.1111C20.1093 10 19.8368 10 19.292 10H12L13 2Z"
                  fill="#60A5FA"
                />
              </svg>
            </div>
            <span>AI Assistant</span>
            <span className="online-dot"></span>
          </div>

          <div className="assistant-actions">
            <button
              className="menu-dots-btn"
              onClick={() => setShowMenu(!showMenu)}
            >
              <span className="dot"></span>
              <span className="dot"></span>
              <span className="dot"></span>
            </button>

            <button onClick={toggleFullScreen} className="icon-btn">
              {isFullScreen ? '↙' : '↗'}
            </button>
            <button onClick={closeAssistant} className="icon-btn">✕</button>
          </div>
        </div>

        {/* Меню — теперь абсолютно позиционировано поверх всего */}
        <AnimatePresence>
          {showMenu && (
            <motion.div
              initial={{ opacity: 0, y: -10, scale: 0.97 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: -10, scale: 0.97 }}
              transition={{ duration: 0.15 }}
              className={`
                absolute z-50
                top-full left-0 right-0
                bg-slate-900/95 backdrop-blur-lg
                border-b border-slate-600/60
                shadow-xl shadow-black/40
                pointer-events-auto
              `}
            >
              <div className="flex justify-end px-4 py-3">
                <button
                  onClick={() => {
                    setMode(mode === 'chat' ? 'translator' : 'chat');
                    setShowMenu(false);
                  }}
                  className="
                    flex items-center gap-3 px-5 py-2.5
                    text-white hover:text-sky-300 font-medium
                    hover:bg-slate-800/60 rounded-lg
                    transition-colors duration-150
                  "
                >
                  {mode === 'chat' ? (
                    <>
                      <MicrophoneIcon className="w-5 h-5 text-green-400 flex-shrink-0" />
                      Голосовой переводчик
                    </>
                  ) : (
                    <>
                      <svg
                        className="w-5 h-5 text-sky-400 flex-shrink-0"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                        strokeWidth={2}
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"
                        />
                      </svg>
                      Вернуться в чат
                    </>
                  )}
                </button>
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* Основной контент — теперь ничто не сдвигает его */}
        <AnimatePresence mode="wait">
          {mode === 'chat' ? (
            <motion.div
              key="chat"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="chat-container flex-1 flex flex-col"
            >
              <div className="assistant-messages flex-1">
                {messages.map((msg, index) => (
                  <div
                    key={index}
                    className={`message ${msg.role === 'user' ? 'user-message' : 'assistant-message'}`}
                  >
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
                  placeholder="Write a message..."
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
              className="translator-mode flex-1 flex flex-col"
            >
              <div className="mb-6 px-5">
                <Select /* ... весь Select без изменений ... */ />
              </div>

              <div className="flex-1 flex items-center justify-center">
                {/* ... кнопка записи без изменений ... */}
              </div>

              <div className="translation-result mt-6 px-5 pb-6">
                {/* ... блоки оригинал + перевод без изменений ... */}
              </div>
            </motion.div>
          )}
        </AnimatePresence>

      </motion.div>
    </AnimatePresence>
  );
};

export default AssistantChatWindow;