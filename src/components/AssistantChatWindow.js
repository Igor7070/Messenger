import React, { useState, useEffect, useRef } from 'react';
import { useAssistant } from '../context/AssistantContext';
import { motion, AnimatePresence } from 'framer-motion';
import toast from 'react-hot-toast';
import { MicrophoneIcon as MicrophoneSolid } from '@heroicons/react/24/solid';
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
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);

  const mediaRecorderRef = useRef(null);
  const audioChunksRef = useRef([]);
  const timerIntervalRef = useRef(null);
  const dropdownRef = useRef(null);

  // Массив языков — идентичен тому, что в TranslationSettingsModal
  const languages = [
    { name: 'Auto', code: 'auto', flag: 'AUTO', emojiFlag: '🌐' },
    { name: 'Afrikaans', code: 'af', flag: 'AF', emojiFlag: '🇿🇦' },
    { name: 'Albanian', code: 'sq', flag: 'SQ', emojiFlag: '🇦🇱' },
    { name: 'Amharic', code: 'am', flag: 'AM', emojiFlag: '🇪🇹' },
    { name: 'Arabic', code: 'ar', flag: 'AR', emojiFlag: '🇸🇦' },
    { name: 'Armenian', code: 'hy', flag: 'HY', emojiFlag: '🇦🇲' },
    { name: 'Azerbaijani', code: 'az', flag: 'AZ', emojiFlag: '🇦🇿' },
    { name: 'Basque', code: 'eu', flag: 'EU', emojiFlag: '🇪🇸' },
    { name: 'Belarusian', code: 'be', flag: 'BE', emojiFlag: '🇧🇾' },
    { name: 'Bengali', code: 'bn', flag: 'BN', emojiFlag: '🇧🇩' },
    { name: 'Bosnian', code: 'bs', flag: 'BS', emojiFlag: '🇧🇦' },
    { name: 'Bulgarian', code: 'bg', flag: 'BG', emojiFlag: '🇧🇬' },
    { name: 'Catalan', code: 'ca', flag: 'CA', emojiFlag: '🇪🇸' },
    { name: 'Cebuano', code: 'ceb', flag: 'CEB', emojiFlag: '🇵🇭' },
    { name: 'Chichewa', code: 'ny', flag: 'NY', emojiFlag: '🇲🇼' },
    { name: 'Chinese (Simplified)', code: 'zh', flag: 'ZH', emojiFlag: '🇨🇳' },
    { name: 'Chinese (Traditional)', code: 'zh-TW', flag: 'ZH-TW', emojiFlag: '🇹🇼' },
    { name: 'Corsican', code: 'co', flag: 'CO', emojiFlag: '🇫🇷' },
    { name: 'Croatian', code: 'hr', flag: 'HR', emojiFlag: '🇭🇷' },
    { name: 'Czech', code: 'cs', flag: 'CS', emojiFlag: '🇨🇿' },
    { name: 'Danish', code: 'da', flag: 'DA', emojiFlag: '🇩🇰' },
    { name: 'Dutch', code: 'nl', flag: 'NL', emojiFlag: '🇳🇱' },
    { name: 'English', code: 'en', flag: 'EN', emojiFlag: '🇬🇧' },
    { name: 'Esperanto', code: 'eo', flag: 'EO', emojiFlag: '🏳️' },
    { name: 'Estonian', code: 'et', flag: 'ET', emojiFlag: '🇪🇪' },
    { name: 'Filipino', code: 'tl', flag: 'TL', emojiFlag: '🇵🇭' },
    { name: 'Finnish', code: 'fi', flag: 'FI', emojiFlag: '🇫🇮' },
    { name: 'French', code: 'fr', flag: 'FR', emojiFlag: '🇫🇷' },
    { name: 'Frisian', code: 'fy', flag: 'FY', emojiFlag: '🇳🇱' },
    { name: 'Galician', code: 'gl', flag: 'GL', emojiFlag: '🇪🇸' },
    { name: 'Georgian', code: 'ka', flag: 'KA', emojiFlag: '🇬🇪' },
    { name: 'German', code: 'de', flag: 'DE', emojiFlag: '🇩🇪' },
    { name: 'Greek', code: 'el', flag: 'EL', emojiFlag: '🇬🇷' },
    { name: 'Gujarati', code: 'gu', flag: 'GU', emojiFlag: '🇮🇳' },
    { name: 'Haitian Creole', code: 'ht', flag: 'HT', emojiFlag: '🇭🇹' },
    { name: 'Hausa', code: 'ha', flag: 'HA', emojiFlag: '🇳🇬' },
    { name: 'Hawaiian', code: 'haw', flag: 'HAW', emojiFlag: '🇺🇸' },
    { name: 'Hebrew', code: 'he', flag: 'HE', emojiFlag: '🇮🇱' },
    { name: 'Hindi', code: 'hi', flag: 'HI', emojiFlag: '🇮🇳' },
    { name: 'Hmong', code: 'hmn', flag: 'HMN', emojiFlag: '🇨🇳' },
    { name: 'Hungarian', code: 'hu', flag: 'HU', emojiFlag: '🇭🇺' },
    { name: 'Icelandic', code: 'is', flag: 'IS', emojiFlag: '🇮🇸' },
    { name: 'Igbo', code: 'ig', flag: 'IG', emojiFlag: '🇳🇬' },
    { name: 'Indonesian', code: 'id', flag: 'ID', emojiFlag: '🇮🇩' },
    { name: 'Irish', code: 'ga', flag: 'GA', emojiFlag: '🇮🇪' },
    { name: 'Italian', code: 'it', flag: 'IT', emojiFlag: '🇮🇹' },
    { name: 'Japanese', code: 'ja', flag: 'JA', emojiFlag: '🇯🇵' },
    { name: 'Javanese', code: 'jv', flag: 'JV', emojiFlag: '🇮🇩' },
    { name: 'Kannada', code: 'kn', flag: 'KN', emojiFlag: '🇮🇳' },
    { name: 'Kazakh', code: 'kk', flag: 'KK', emojiFlag: '🇰🇿' },
    { name: 'Khmer', code: 'km', flag: 'KM', emojiFlag: '🇰🇭' },
    { name: 'Kinyarwanda', code: 'rw', flag: 'RW', emojiFlag: '🇷🇼' },
    { name: 'Korean', code: 'ko', flag: 'KO', emojiFlag: '🇰🇷' },
    { name: 'Kurdish', code: 'ku', flag: 'KU', emojiFlag: '🇮🇶' },
    { name: 'Kyrgyz', code: 'ky', flag: 'KY', emojiFlag: '🇰🇬' },
    { name: 'Lao', code: 'lo', flag: 'LO', emojiFlag: '🇱🇦' },
    { name: 'Latin', code: 'la', flag: 'LA', emojiFlag: '🏳️' },
    { name: 'Latvian', code: 'lv', flag: 'LV', emojiFlag: '🇱🇻' },
    { name: 'Lithuanian', code: 'lt', flag: 'LT', emojiFlag: '🇱🇹' },
    { name: 'Luxembourgish', code: 'lb', flag: 'LB', emojiFlag: '🇱🇺' },
    { name: 'Macedonian', code: 'mk', flag: 'MK', emojiFlag: '🇲🇰' },
    { name: 'Malagasy', code: 'mg', flag: 'MG', emojiFlag: '🇲🇬' },
    { name: 'Malay', code: 'ms', flag: 'MS', emojiFlag: '🇲🇾' },
    { name: 'Malayalam', code: 'ml', flag: 'ML', emojiFlag: '🇮🇳' },
    { name: 'Maltese', code: 'mt', flag: 'MT', emojiFlag: '🇲🇹' },
    { name: 'Maori', code: 'mi', flag: 'MI', emojiFlag: '🇳🇿' },
    { name: 'Marathi', code: 'mr', flag: 'MR', emojiFlag: '🇮🇳' },
    { name: 'Mongolian', code: 'mn', flag: 'MN', emojiFlag: '🇲🇳' },
    { name: 'Myanmar (Burmese)', code: 'my', flag: 'MY', emojiFlag: '🇲🇲' },
    { name: 'Nepali', code: 'ne', flag: 'NE', emojiFlag: '🇳🇵' },
    { name: 'Norwegian', code: 'no', flag: 'NO', emojiFlag: '🇳🇴' },
    { name: 'Odia', code: 'or', flag: 'OR', emojiFlag: '🇮🇳' },
    { name: 'Pashto', code: 'ps', flag: 'PS', emojiFlag: '🇦🇫' },
    { name: 'Persian', code: 'fa', flag: 'FA', emojiFlag: '🇮🇷' },
    { name: 'Polish', code: 'pl', flag: 'PL', emojiFlag: '🇵🇱' },
    { name: 'Portuguese', code: 'pt', flag: 'PT', emojiFlag: '🇵🇹' },
    { name: 'Punjabi', code: 'pa', flag: 'PA', emojiFlag: '🇮🇳' },
    { name: 'Romanian', code: 'ro', flag: 'RO', emojiFlag: '🇷🇴' },
    { name: 'Russian', code: 'ru', flag: 'RU', emojiFlag: '🇷🇺' },
    { name: 'Samoan', code: 'sm', flag: 'SM', emojiFlag: '🇼🇸' },
    { name: 'Scots Gaelic', code: 'gd', flag: 'GD', emojiFlag: '🏴󠁧󠁢󠁳󠁣󠁴󠁿' },
    { name: 'Serbian', code: 'sr', flag: 'SR', emojiFlag: '🇷🇸' },
    { name: 'Sesotho', code: 'st', flag: 'ST', emojiFlag: '🇱🇸' },
    { name: 'Shona', code: 'sn', flag: 'SN', emojiFlag: '🇿🇼' },
    { name: 'Sindhi', code: 'sd', flag: 'SD', emojiFlag: '🇵🇰' },
    { name: 'Sinhala', code: 'si', flag: 'SI', emojiFlag: '🇱🇰' },
    { name: 'Slovak', code: 'sk', flag: 'SK', emojiFlag: '🇸🇰' },
    { name: 'Slovenian', code: 'sl', flag: 'SL', emojiFlag: '🇸🇮' },
    { name: 'Somali', code: 'so', flag: 'SO', emojiFlag: '🇸🇴' },
    { name: 'Spanish', code: 'es', flag: 'ES', emojiFlag: '🇪🇸' },
    { name: 'Sundanese', code: 'su', flag: 'SU', emojiFlag: '🇮🇩' },
    { name: 'Swahili', code: 'sw', flag: 'SW', emojiFlag: '🇹🇿' },
    { name: 'Swedish', code: 'sv', flag: 'SV', emojiFlag: '🇸🇪' },
    { name: 'Tajik', code: 'tg', flag: 'TG', emojiFlag: '🇹🇯' },
    { name: 'Tamil', code: 'ta', flag: 'TA', emojiFlag: '🇮🇳' },
    { name: 'Tatar', code: 'tt', flag: 'TT', emojiFlag: '🇷🇺' },
    { name: 'Telugu', code: 'te', flag: 'TE', emojiFlag: '🇮🇳' },
    { name: 'Thai', code: 'th', flag: 'TH', emojiFlag: '🇹🇭' },
    { name: 'Turkish', code: 'tr', flag: 'TR', emojiFlag: '🇹🇷' },
    { name: 'Turkmen', code: 'tk', flag: 'TK', emojiFlag: '🇹🇲' },
    { name: 'Ukrainian', code: 'uk', flag: 'UK', emojiFlag: '🇺🇦' },
    { name: 'Urdu', code: 'ur', flag: 'UR', emojiFlag: '🇵🇰' },
    { name: 'Uyghur', code: 'ug', flag: 'UG', emojiFlag: '🇨🇳' },
    { name: 'Uzbek', code: 'uz', flag: 'UZ', emojiFlag: '🇺🇿' },
    { name: 'Vietnamese', code: 'vi', flag: 'VI', emojiFlag: '🇻🇳' },
    { name: 'Welsh', code: 'cy', flag: 'CY', emojiFlag: '🏴󠁧󠁢󠁷󠁬󠁳󠁿' },
    { name: 'Xhosa', code: 'xh', flag: 'XH', emojiFlag: '🇿🇦' },
    { name: 'Yiddish', code: 'yi', flag: 'YI', emojiFlag: '🇮🇱' },
    { name: 'Yoruba', code: 'yo', flag: 'YO', emojiFlag: '🇳🇬' },
    { name: 'Zulu', code: 'zu', flag: 'ZU', emojiFlag: '🇿🇦' }
  ].sort((a, b) => (a.name === 'Auto' ? -1 : b.name === 'Auto' ? 1 : a.name.localeCompare(b.name)));

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  // Закрытие dropdown по клику вне
  useEffect(() => {
    const handleClickOutside = (event) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
        setIsDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

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
      const res = await fetch('https://unlimitedpossibilities12.org/api/tts', {
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
        className={`assistant-window ${isFullScreen ? 'full-screen' : 'mini'}`}
      >
        {/* Шапка */}
        <div className="assistant-header relative z-10">
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

        {/* Меню */}
        <div
          style={{
            height: 0,
            overflow: 'visible',
            position: 'absolute',
            top: 64,
            right: 0,
            width: 'auto',
            zIndex: 30,
            pointerEvents: 'none',
          }}
        >
          <motion.div
            animate={{
              opacity: showMenu ? 1 : 0,
              y: showMenu ? 0 : -12,
              scale: showMenu ? 1 : 0.96,
              pointerEvents: showMenu ? 'auto' : 'none',
            }}
            transition={{ duration: 0.18, ease: [0.25, 0.1, 0.25, 1] }}
            className={`
              absolute top-full right-0 mr-3 mt-1
              bg-slate-900/95 backdrop-blur-lg 
              border border-slate-700/60
              rounded-xl shadow-2xl shadow-black/40
              py-3 px-5 min-w-[200px] w-auto
              origin-top-right z-[9999]
            `}
            style={{
              right: '16px',
              left: 'auto',
              transformOrigin: 'top right',
            }}
          >
            <button
              onClick={() => {
                setMode(mode === 'chat' ? 'translator' : 'chat');
                setShowMenu(false);
              }}
              className="flex items-center gap-3 w-full text-left py-2.5 px-4 rounded-lg hover:bg-slate-800/70 transition-colors text-white hover:text-sky-300 whitespace-nowrap text-base font-medium"
            >
              {mode === 'chat' ? 'Голосовой переводчик' : 'Вернуться в чат'}
            </button>
          </motion.div>
        </div>

        {/* Основной контент */}
        <AnimatePresence mode="wait">
          {mode === 'chat' ? (
            <motion.div
              key="chat"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="flex-1 flex flex-col chat-container"
            >
              <div className="assistant-messages flex-1 overflow-y-auto">
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
              className="flex-1 flex flex-col translator-mode"
            >
              {/* Кастомный селект — как в TranslationSettingsModal */}
              <div className="mb-6 px-5">
                <label className="select-label block text-white mb-1">
                  Язык перевода
                </label>

                <div className="custom-select" ref={dropdownRef}>
                  <div
                    className={`select-selected ${isDropdownOpen ? 'open' : ''}`}
                    onClick={() => setIsDropdownOpen(!isDropdownOpen)}
                  >
                    <span className="select-flag">
                      {languages.find(lang => lang.code === selectedLanguage)?.flag || 'AUTO'}
                    </span>
                    <span className="select-name">
                      {languages.find(lang => lang.code === selectedLanguage)?.name || 'Auto'}
                    </span>
                    <span className="select-emoji-flag">
                      {languages.find(lang => lang.code === selectedLanguage)?.emojiFlag || '🌐'}
                    </span>
                  </div>

                  {isDropdownOpen && (
                    <div className="select-items">
                      {languages.map((lang) => (
                        <div
                          key={lang.code}
                          className={`select-option ${lang.code === selectedLanguage ? 'selected' : ''}`}
                          onClick={() => {
                            setSelectedLanguage(lang.code);
                            setIsDropdownOpen(false);
                          }}
                        >
                          <span className="select-flag">{lang.flag}</span>
                          <span className="select-name">{lang.name}</span>
                          <span className="select-emoji-flag">{lang.emojiFlag}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>

              <div className="flex-1 flex items-center justify-center">
                <motion.button
                  whileTap={{ scale: 0.92 }}
                  onClick={isRecording ? stopRecording : startRecording}
                  className={`record-btn ${isRecording ? 'recording' : ''}`}
                >
                  {isRecording ? <StopIcon className="w-20 h-20" /> : <MicrophoneIcon className="w-20 h-20" />}
                  {isRecording && (
                    <div className="timer absolute -bottom-10 text-2xl font-bold text-white">
                      {`${Math.floor(recordingTimer / 60).toString().padStart(2, '0')}:${(recordingTimer % 60).toString().padStart(2, '0')}`}
                    </div>
                  )}
                </motion.button>
              </div>

              <div className="translation-result mt-6 px-5 pb-6">
                <div className="translation-block relative">
                  <div className="label">Оригинал</div>
                  <div className="text-content relative">
                    {originalText || 'Говорите...'}
                    {originalText && (
                      <button
                        onClick={() => speakText(originalText, 'auto')}
                        className="absolute top-2 right-2 p-2 rounded-full bg-blue-900/40 hover:bg-blue-800/60 transition-colors"
                        title="Озвучить оригинал"
                      >
                        <MicrophoneSolid className="w-5 h-5 text-blue-400" />   {/* ← solid */}
                      </button>
                    )}
                  </div>
                </div>

                <div className="translation-block mt-6 relative">
                  <div className="label">Перевод</div>
                  <div className="text-content relative">
                    {translatedText || 'Ожидаем перевод...'}
                    {translatedText && (
                      <button
                        onClick={() => speakText(translatedText, selectedLanguage)}
                        className="absolute top-2 right-2 p-2 rounded-full bg-green-900/40 hover:bg-green-800/60 transition-colors"
                        title="Озвучить перевод"
                      >
                        <MicrophoneSolid className="w-5 h-5 text-green-400" />  {/* ← solid */}
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