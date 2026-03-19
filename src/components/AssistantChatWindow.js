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

  // Полный список языков
  const languages = [
    { name: 'Auto', code: 'auto', emoji: '🌐' },
    { name: 'Afrikaans', code: 'af', emoji: '🇿🇦' },
    { name: 'Albanian', code: 'sq', emoji: '🇦🇱' },
    { name: 'Amharic', code: 'am', emoji: '🇪🇹' },
    { name: 'Arabic', code: 'ar', emoji: '🇸🇦' },
    { name: 'Armenian', code: 'hy', emoji: '🇦🇲' },
    { name: 'Azerbaijani', code: 'az', emoji: '🇦🇿' },
    { name: 'Basque', code: 'eu', emoji: '🇪🇸' },
    { name: 'Belarusian', code: 'be', emoji: '🇧🇾' },
    { name: 'Bengali', code: 'bn', emoji: '🇧🇩' },
    { name: 'Bosnian', code: 'bs', emoji: '🇧🇦' },
    { name: 'Bulgarian', code: 'bg', emoji: '🇧🇬' },
    { name: 'Catalan', code: 'ca', emoji: '🇪🇸' },
    { name: 'Cebuano', code: 'ceb', emoji: '🇵🇭' },
    { name: 'Chichewa', code: 'ny', emoji: '🇲🇼' },
    { name: 'Chinese (Simplified)', code: 'zh', emoji: '🇨🇳' },
    { name: 'Chinese (Traditional)', code: 'zh-TW', emoji: '🇹🇼' },
    { name: 'Corsican', code: 'co', emoji: '🇫🇷' },
    { name: 'Croatian', code: 'hr', emoji: '🇭🇷' },
    { name: 'Czech', code: 'cs', emoji: '🇨🇿' },
    { name: 'Danish', code: 'da', emoji: '🇩🇰' },
    { name: 'Dutch', code: 'nl', emoji: '🇳🇱' },
    { name: 'English', code: 'en', emoji: '🇬🇧' },
    { name: 'Esperanto', code: 'eo', emoji: '🏳️' },
    { name: 'Estonian', code: 'et', emoji: '🇪🇪' },
    { name: 'Filipino', code: 'tl', emoji: '🇵🇭' },
    { name: 'Finnish', code: 'fi', emoji: '🇫🇮' },
    { name: 'French', code: 'fr', emoji: '🇫🇷' },
    { name: 'Frisian', code: 'fy', emoji: '🇳🇱' },
    { name: 'Galician', code: 'gl', emoji: '🇪🇸' },
    { name: 'Georgian', code: 'ka', emoji: '🇬🇪' },
    { name: 'German', code: 'de', emoji: '🇩🇪' },
    { name: 'Greek', code: 'el', emoji: '🇬🇷' },
    { name: 'Gujarati', code: 'gu', emoji: '🇮🇳' },
    { name: 'Haitian Creole', code: 'ht', emoji: '🇭🇹' },
    { name: 'Hausa', code: 'ha', emoji: '🇳🇬' },
    { name: 'Hawaiian', code: 'haw', emoji: '🇺🇸' },
    { name: 'Hebrew', code: 'he', emoji: '🇮🇱' },
    { name: 'Hindi', code: 'hi', emoji: '🇮🇳' },
    { name: 'Hmong', code: 'hmn', emoji: '🇨🇳' },
    { name: 'Hungarian', code: 'hu', emoji: '🇭🇺' },
    { name: 'Icelandic', code: 'is', emoji: '🇮🇸' },
    { name: 'Igbo', code: 'ig', emoji: '🇳🇬' },
    { name: 'Indonesian', code: 'id', emoji: '🇮🇩' },
    { name: 'Irish', code: 'ga', emoji: '🇮🇪' },
    { name: 'Italian', code: 'it', emoji: '🇮🇹' },
    { name: 'Japanese', code: 'ja', emoji: '🇯🇵' },
    { name: 'Javanese', code: 'jv', emoji: '🇮🇩' },
    { name: 'Kannada', code: 'kn', emoji: '🇮🇳' },
    { name: 'Kazakh', code: 'kk', emoji: '🇰🇿' },
    { name: 'Khmer', code: 'km', emoji: '🇰🇭' },
    { name: 'Kinyarwanda', code: 'rw', emoji: '🇷🇼' },
    { name: 'Korean', code: 'ko', emoji: '🇰🇷' },
    { name: 'Kurdish', code: 'ku', emoji: '🇮🇶' },
    { name: 'Kyrgyz', code: 'ky', emoji: '🇰🇬' },
    { name: 'Lao', code: 'lo', emoji: '🇱🇦' },
    { name: 'Latin', code: 'la', emoji: '🏳️' },
    { name: 'Latvian', code: 'lv', emoji: '🇱🇻' },
    { name: 'Lithuanian', code: 'lt', emoji: '🇱🇹' },
    { name: 'Luxembourgish', code: 'lb', emoji: '🇱🇺' },
    { name: 'Macedonian', code: 'mk', emoji: '🇲🇰' },
    { name: 'Malagasy', code: 'mg', emoji: '🇲🇬' },
    { name: 'Malay', code: 'ms', emoji: '🇲🇾' },
    { name: 'Malayalam', code: 'ml', emoji: '🇮🇳' },
    { name: 'Maltese', code: 'mt', emoji: '🇲🇹' },
    { name: 'Maori', code: 'mi', emoji: '🇳🇿' },
    { name: 'Marathi', code: 'mr', emoji: '🇮🇳' },
    { name: 'Mongolian', code: 'mn', emoji: '🇲🇳' },
    { name: 'Myanmar (Burmese)', code: 'my', emoji: '🇲🇲' },
    { name: 'Nepali', code: 'ne', emoji: '🇳🇵' },
    { name: 'Norwegian', code: 'no', emoji: '🇳🇴' },
    { name: 'Odia', code: 'or', emoji: '🇮🇳' },
    { name: 'Pashto', code: 'ps', emoji: '🇦🇫' },
    { name: 'Persian', code: 'fa', emoji: '🇮🇷' },
    { name: 'Polish', code: 'pl', emoji: '🇵🇱' },
    { name: 'Portuguese', code: 'pt', emoji: '🇵🇹' },
    { name: 'Punjabi', code: 'pa', emoji: '🇮🇳' },
    { name: 'Romanian', code: 'ro', emoji: '🇷🇴' },
    { name: 'Russian', code: 'ru', emoji: '🇷🇺' },
    { name: 'Samoan', code: 'sm', emoji: '🇼🇸' },
    { name: 'Scots Gaelic', code: 'gd', emoji: '🏴󠁧󠁢󠁳󠁣󠁴󠁿' },
    { name: 'Serbian', code: 'sr', emoji: '🇷🇸' },
    { name: 'Sesotho', code: 'st', emoji: '🇱🇸' },
    { name: 'Shona', code: 'sn', emoji: '🇿🇼' },
    { name: 'Sindhi', code: 'sd', emoji: '🇵🇰' },
    { name: 'Sinhala', code: 'si', emoji: '🇱🇰' },
    { name: 'Slovak', code: 'sk', emoji: '🇸🇰' },
    { name: 'Slovenian', code: 'sl', emoji: '🇸🇮' },
    { name: 'Somali', code: 'so', emoji: '🇸🇴' },
    { name: 'Spanish', code: 'es', emoji: '🇪🇸' },
    { name: 'Sundanese', code: 'su', emoji: '🇮🇩' },
    { name: 'Swahili', code: 'sw', emoji: '🇹🇿' },
    { name: 'Swedish', code: 'sv', emoji: '🇸🇪' },
    { name: 'Tajik', code: 'tg', emoji: '🇹🇯' },
    { name: 'Tamil', code: 'ta', emoji: '🇮🇳' },
    { name: 'Tatar', code: 'tt', emoji: '🇷🇺' },
    { name: 'Telugu', code: 'te', emoji: '🇮🇳' },
    { name: 'Thai', code: 'th', emoji: '🇹🇭' },
    { name: 'Turkish', code: 'tr', emoji: '🇹🇷' },
    { name: 'Turkmen', code: 'tk', emoji: '🇹🇲' },
    { name: 'Ukrainian', code: 'uk', emoji: '🇺🇦' },
    { name: 'Urdu', code: 'ur', emoji: '🇵🇰' },
    { name: 'Uyghur', code: 'ug', emoji: '🇨🇳' },
    { name: 'Uzbek', code: 'uz', emoji: '🇺🇿' },
    { name: 'Vietnamese', code: 'vi', emoji: '🇻🇳' },
    { name: 'Welsh', code: 'cy', emoji: '🏴󠁧󠁢󠁷󠁬󠁳󠁿' },
    { name: 'Xhosa', code: 'xh', emoji: '🇿🇦' },
    { name: 'Yiddish', code: 'yi', emoji: '🇮🇱' },
    { name: 'Yoruba', code: 'yo', emoji: '🇳🇬' },
    { name: 'Zulu', code: 'zu', emoji: '🇿🇦' },
  ].sort((a, b) => (a.name === 'Auto' ? -1 : b.name === 'Auto' ? 1 : a.name.localeCompare(b.name)));

  // Форматируем под react-select (флаги + название)
  const selectLanguages = languages.map((lang) => ({
    value: lang.code,
    label: `${lang.emoji} ${lang.name}`,
  }));

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

        {/* Меню — всегда в DOM, высота 0, не толкает контент */}
        <div
          style={{
            height: 0,
            overflow: 'visible',
            position: 'absolute',           // ← меняем на absolute
            top: 64,                         // стартуем от верха шапки
            right: 0,                       // ← вот здесь магия: relative → absolute + right:0
            width: 'auto',                  // не растягиваем
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
              absolute top-full
              right-0                     // теперь right считается от правого края окна
              mr-3                        // отступ от края экрана/окна
              mt-1
              bg-slate-900/95 backdrop-blur-lg 
              border border-slate-700/60
              rounded-xl shadow-2xl shadow-black/40
              py-3 px-5
              min-w-[200px]
              w-auto
              origin-top-right
              z-[9999]
            `}
            style={{
              right: '16px',                // основной отступ от правого края
              left: 'auto',
              transformOrigin: 'top right',
            }}
          >
            {/* кнопка как была */}
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

        {/* Основной контент — структура как в твоём оригинальном коде */}
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
              <div className="mb-6 px-5">
                <Select
                  options={selectLanguages}
                  value={selectLanguages.find((l) => l.value === selectedLanguage)}
                  onChange={(opt) => setSelectedLanguage(opt.value)}
                  className="language-select"
                  classNamePrefix="react-select"
                  styles={{
                    control: (base) => ({
                      ...base,
                      backgroundColor: '#1e293b',
                      borderColor: '#334155',
                      color: 'white',
                    }),
                    menu: (base) => ({ ...base, backgroundColor: '#0f172a', color: 'white' }),
                    option: (base, state) => ({
                      ...base,
                      backgroundColor: state.isSelected ? '#2563eb' : state.isFocused ? '#334155' : '#0f172a',
                      color: 'white',
                    }),
                    singleValue: (base) => ({ ...base, color: 'white' }),
                  }}
                />
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
                <div className="translation-block">
                  <div className="label">Оригинал</div>
                  <div className="text-content">
                    {originalText || 'Говорите...'}
                    {originalText && (
                      <button onClick={() => speakText(originalText, 'auto')} className="play-btn blue">
                        <PlayIcon className="w-6 h-6" />
                      </button>
                    )}
                  </div>
                </div>

                <div className="translation-block mt-6">
                  <div className="label">Перевод</div>
                  <div className="text-content">
                    {translatedText || 'Ожидаем перевод...'}
                    {translatedText && (
                      <button onClick={() => speakText(translatedText, selectedLanguage)} className="play-btn green">
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