import React, { useCallback, useEffect, useRef, useState } from 'react';
import { stompAuthHeaders } from '../authToken';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Line } from 'react-chartjs-2';
import { Chart, registerables } from 'chart.js';
import { useNotification } from '../context/NotificationContext';
import axios from 'axios'; // NEW: Добавляем импорт axios
// Те же модели OPUS-MT, что уже переводят секретные чаты: в звонке они избавляют
// от платного перевода каждой фразы
import * as localTranslator from '../localTranslation/localTranslator';
import '../styles/VoiceCallWithTranslate.css';

Chart.register(...registerables);

const VoiceCallWithTranslate = ({ recipientId }) => {
  const navigate = useNavigate();
  const notify = useNotification();
  const { roomId } = useParams();
  const location = useLocation();
  const { incomingCall, isCaller, currentUserId, callerName, recipientName } = location.state || {
    incomingCall: null,
    isCaller: false,
    currentUserId: null,
    callerName: '',
    recipientName: '',
  };
  const localAudioRef = useRef(null);
  const remoteAudioRef = useRef(null);
  const peerConnectionRef = useRef(null);
  const stompClient = useRef(null);
  const mediaRecorderRef = useRef(null);
  const [incomingCallState, setIncomingCallState] = useState(incomingCall);
  const [isWaiting, setIsWaiting] = useState(false);
  const [transcription, setTranscription] = useState('Transcription: Waiting...');
  const [friendTranscription, setFriendTranscription] = useState("Friend's Transcription: Waiting...");
  const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));
  const hasNotifiedEndCall = useRef(false);
  const hasNotifiedAcceptCall = useRef(false);
  const hasNotifiedRejectCall = useRef(false); // NEW: Добавляем реф для rejectCall
  const hasSavedCall = useRef(false); // NEW: Флаг для предотвращения двойного сохранения звонка
  const transcriptionRef = useRef(null);
  const friendTranscriptionRef = useRef(null);
  const ttsAudioRef = useRef(null); // [ДОБАВЛЕНО] Реф для TTS-аудио
  const ttsQueueRef = useRef([]);   // очередь озвучек: произносим по одной
  const ttsPlayingRef = useRef(false);

  /** Громкость живого голоса: с озвучкой — фоном, на время самой озвучки тише. */
  const setRemoteVolume = useCallback((speaking) => {
    if (!remoteAudioRef.current) return;
    remoteAudioRef.current.muted = false;
    remoteAudioRef.current.volume = !ttsEnabledRef.current ? 1.0 : (speaking ? 0.12 : 0.3);
  }, []);

  /** Произнести перевод голосом браузера — бесплатно, ничего не отправляя. */
  const speakLocally = useCallback((text, lang) => {
    if (!text || typeof window === 'undefined' || !('speechSynthesis' in window)) return;
    const utterance = new window.SpeechSynthesisUtterance(text);
    utterance.lang = lang === 'ru' ? 'ru-RU' : lang === 'uk' ? 'uk-UA' : 'en-US';
    // Очередь ведёт сам speechSynthesis, фразы не обрывают друг друга
    utterance.onstart = () => setRemoteVolume(true);
    utterance.onend = () => setRemoteVolume(false);
    utterance.onerror = () => setRemoteVolume(false);
    window.speechSynthesis.speak(utterance);
  }, [setRemoteVolume]);

  /** Проигрывает следующую озвучку из очереди; оригинал приглушён, пока очередь не пуста. */
  const playNextTts = useCallback(() => {
    if (ttsPlayingRef.current) return;
    const url = ttsQueueRef.current.shift();
    if (!url) {
      // Оригинал возвращаем не в полную силу, а фоном: перевод звучит после
      // фразы, и если оба голоса громкие, разговор удваивается
      if (remoteAudioRef.current) {
        remoteAudioRef.current.muted = false;
        remoteAudioRef.current.volume = ttsEnabled ? 0.3 : 1.0;
      }
      return;
    }
    const audio = ttsAudioRef.current;
    if (!audio) return;
    ttsPlayingRef.current = true;
    if (remoteAudioRef.current) {
      remoteAudioRef.current.muted = false;
      remoteAudioRef.current.volume = 0.12; // почти тишина, но слышно, что человек ещё говорит
    }
    audio.src = url;
    audio.onended = () => {
      ttsPlayingRef.current = false;
      URL.revokeObjectURL(url);
      playNextTts();
    };
    audio.onerror = () => {
      console.error('Error playing TTS audio');
      ttsPlayingRef.current = false;
      URL.revokeObjectURL(url);
      playNextTts();
    };
    audio.play().catch((error) => {
      console.error('Error playing TTS audio:', error);
      ttsPlayingRef.current = false;
      URL.revokeObjectURL(url);
      playNextTts();
    });
  }, []);
  // [ДОБАВЛЕНО] Состояния для переключателя перевода, выбранного языка и рефа для выпадающего списка
  const [isTranslationEnabled, setIsTranslationEnabled] = useState(false);
  const [selectedLanguage, setSelectedLanguage] = useState('auto'); // ИЗМЕНЕНО: 'en' → 'auto'
  // Выбранный язык описывает самого пользователя: на нём он говорит и на него
  // ему переводят. Отдельного списка «на каком языке говорю» нет — это тот же
  // факт, записанный дважды.
  const languageDropdownRef = useRef(null);
  const isTranslationEnabledRef = useRef(isTranslationEnabled);
  const selectedLanguageRef = useRef(selectedLanguage);

  // [ДОБАВЛЕНО] Состояние и реф для переключателя TTS
  const [ttsEnabled, setTtsEnabled] = useState(false);
  const ttsEnabledRef = useRef(ttsEnabled);
  /**
   * Бесплатный режим: перевод делают модели в браузере, произносит его сам
   * браузер. Своя речь по-прежнему распознаётся на сервере — бесплатного
   * распознавания в браузере у нас нет, поэтому звук отправлять продолжаем.
   */
  const [onDeviceMode, setOnDeviceMode] = useState(false);
  const onDeviceModeRef = useRef(onDeviceMode);
  const [modelProgress, setModelProgress] = useState(null);
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

  const [sessionId, setSessionId] = useState(`${currentUserId}_${Date.now()}`);
  const [callDuration, setCallDuration] = useState(0);

  let userName = recipientName;
  let terminationInitiator = false;

  const [chartData, setChartData] = useState({
    labels: [],
    datasets: [
      {
        label: 'Voice Activity',
        data: [],
        borderColor: 'rgba(74, 144, 226, 1)',
        backgroundColor: 'rgba(74, 144, 226, 0.2)',
        borderWidth: 2,
        fill: true,
      },
    ],
  });

  useEffect(() => {
    if (isCaller) {
      setIsWaiting(true);
      ringtoneRef.current.loop = true;
      ringtoneRef.current.play().catch((error) => {
        console.error('Error playing ringtone:', error);
      });
    }

    return () => {
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
    };
  }, [isCaller]);

  useEffect(() => {
    if (!currentUserId) {
      console.error('currentUserId is undefined.');
      notify('User ID is missing.', { toastId: 'user-id-missing' });
      return;
    }

    const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
    stompClient.current = new Client({
      connectHeaders: stompAuthHeaders(),
      webSocketFactory: () => socket,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        console.log('Connected to WebSocket');
        console.log('Room ID:', roomId);

        // Подписка на сигнализацию
        stompClient.current.subscribe(`/topic/room/${roomId}`, (message) => {
          console.log('Received message on /topic/room/' + roomId + ':', message.body);
          try {
            const signalMessage = JSON.parse(message.body);
            handleSignal(signalMessage);
          } catch (error) {
            console.error('Error parsing WebSocket message:', error);
          }
        });

        // Подписка на собственную транскрипцию
        stompClient.current.subscribe(`/topic/transcription/${roomId}`, (message) => {
          console.log('Received transcription message:', message.body);
          try {
            const json = JSON.parse(message.body);
            if (json.error) {
              setTranscription(`Error: ${json.error}`);
              scrollToBottom(transcriptionRef);
            } else if (json.transcription && json.sessionId === sessionId) {
              setTranscription((prev) => {
                if (prev === 'Transcription: Waiting...' || prev.startsWith('Error')) {
                  return json.transcription;
                }
                const lines = prev.split('\n');
                if (lines.length >= 50) {
                  return [...lines.slice(-49), json.transcription].join('\n');
                }
                return `${prev}\n${json.transcription}`;
              });
              scrollToBottom(transcriptionRef);
            }
          } catch (error) {
            console.error('Error parsing transcription JSON:', error);
            setTranscription(`Error: ${error.message}`);
            scrollToBottom(transcriptionRef);
          }
        });

        // Подписка на транскрипцию собеседника с переводом
        stompClient.current.subscribe(`/topic/friend-transcription/${roomId}/${currentUserId}`, (message) => {
          console.log('Received friend transcription:', message.body);
          try {
            const json = JSON.parse(message.body);
            if (json.error) {
              console.log('Transcription error:', json.error); // [ОСТАВЛЕНО] Лог ошибки
              setFriendTranscription(`Error: ${json.error}`);
              scrollToBottom(friendTranscriptionRef);
            } else if (json.transcription) {
              console.log('Processing transcription:', { // [ИЗМЕНЕНО] Используем рефы
                transcription: json.transcription,
                isTranslationEnabled: isTranslationEnabledRef.current,
                selectedLanguage: selectedLanguageRef.current,
              });
              if (isTranslationEnabledRef.current && selectedLanguageRef.current && selectedLanguageRef.current !== 'auto') {
                console.log('Translating transcription to:', selectedLanguageRef.current); // [ИЗМЕНЕНО] Используем реф
                translateTranscription(json.transcription, selectedLanguageRef.current, (translatedText) => {
                  console.log('Translation result:', translatedText); // [ОСТАВЛЕНО] Лог результата
                  const displayText = translatedText || json.transcription;
                  // В бесплатном режиме сервер нам озвучку не присылает — говорим сами
                  if (onDeviceModeRef.current && ttsEnabledRef.current) {
                    speakLocally(displayText, selectedLanguageRef.current);
                  }
                  setFriendTranscription((prev) => {
                    if (prev === "Friend's Transcription: Waiting..." || prev.startsWith('Error')) {
                      return displayText;
                    }
                    const lines = prev.split('\n');
                    if (lines.length >= 50) {
                      return [...lines.slice(-49), displayText].join('\n');
                    }
                    return `${prev}\n${displayText}`;
                  });
                  scrollToBottom(friendTranscriptionRef);
                });
              } else {
                console.log('Translation skipped, using original:', json.transcription); // [ОСТАВЛЕНО] Лог пропуска перевода
                setFriendTranscription((prev) => {
                  if (prev === "Friend's Transcription: Waiting..." || prev.startsWith('Error')) {
                    return json.transcription;
                  }
                  const lines = prev.split('\n');
                  if (lines.length >= 50) {
                    return [...lines.slice(-49), json.transcription].join('\n');
                  }
                  return `${prev}\n${json.transcription}`;
                });
                scrollToBottom(friendTranscriptionRef);
              }
            }
          } catch (error) {
            console.error('Error parsing friend transcription JSON:', error);
            setFriendTranscription(`Error: ${error.message}`);
            scrollToBottom(friendTranscriptionRef);
          }
        });

        // [ДОБАВЛЕНО] Подписка на TTS-аудио
        stompClient.current.subscribe(`/topic/tts/${roomId}/${currentUserId}`, (message) => {
          console.log('Received TTS audio:', message.body);
          try {
            const json = JSON.parse(message.body);
            if (json.audio && json.sessionId) {
              const audioData = atob(json.audio);
              const arrayBuffer = new ArrayBuffer(audioData.length);
              const view = new Uint8Array(arrayBuffer);
              for (let i = 0; i < audioData.length; i++) {
                view[i] = audioData.charCodeAt(i);
              }
              const blob = new Blob([arrayBuffer], { type: 'audio/mp3' });
              const url = URL.createObjectURL(blob);
              // Фразы приходят чаще, чем успевают проговариваться, а проигрыватель
              // один: раньше каждая новая озвучка обрывала предыдущую на полуслове,
              // и часть фраз пропадала. Ставим в очередь и произносим по порядку.
              ttsQueueRef.current.push(url);
              playNextTts();
            }
          } catch (error) {
            console.error('Error parsing TTS audio JSON:', error);
          }
        });

        // Регистрация recipientId
        if (incomingCall && (incomingCall.recipientId || incomingCall.callerId)) {
          registerRecipient();
        } else {
          console.warn('Cannot register recipient: incomingCall is missing required fields', incomingCall);
        }

        if (!isCaller && incomingCall) {
          console.log('Processing incoming call:', incomingCall);
          handleIncomingCall(incomingCall);
        }
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame); // [ОСТАВЛЕНО] Лог ошибки
        notify('STOMP protocol error.', { toastId: 'stomp-error' });
      },
      onWebSocketError: (error) => {
        console.error('WebSocket error:', error); // [ИЗМЕНЕНО] Более подробный лог
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected');
      },
    });
    stompClient.current.activate();

    const MAX_POINTS = 40;   // сколько секунд показывать на графике

    const interval = setInterval(() => {
      setChartData((prevData) => {
        const newTime = new Date().toLocaleTimeString([], { 
          hour: '2-digit',      // ← добавили часы
          minute: '2-digit', 
          second: '2-digit',
          hour12: false         // 24-часовой формат
        });

        const newValue = Math.random() * 85 + 15;

        let newLabels = [...prevData.labels, newTime];
        let newData = [...prevData.datasets[0].data, newValue];

        if (newLabels.length > MAX_POINTS) {
          newLabels = newLabels.slice(-MAX_POINTS);
          newData = newData.slice(-MAX_POINTS);
        }

        return {
          labels: newLabels,
          datasets: [{
            ...prevData.datasets[0],
            data: newData,
            borderColor: '#67e8f9',
            backgroundColor: 'rgba(103, 232, 249, 0.15)',
          }],
        };
      });
    }, 1000);

    return () => {
      console.log('Deactivating WebSocket connection...');
      if (stompClient.current) {
        stompClient.current.deactivate();
      }
      clearInterval(interval);
      stopAudioCapture();
      localStorage.removeItem(`translation_enabled_${roomId}`);
      localStorage.removeItem(`translation_language_${roomId}`);
      // [ДОБАВЛЕНО] Удаление tts_enabled из localStorage
      localStorage.removeItem(`tts_enabled_${roomId}`);
    };
  }, [currentUserId, roomId, incomingCall]);

  useEffect(() => {
    // [ИЗМЕНЕНО] Загрузка сохранённых настроек только при первом монтировании
    const savedTranslationEnabled = JSON.parse(localStorage.getItem(`translation_enabled_${roomId}`) || 'false');
    const savedLanguage = localStorage.getItem(`translation_language_${roomId}`) || 'auto';
    // [ДОБАВЛЕНО] Загрузка ttsEnabled
    const savedTtsEnabled = JSON.parse(localStorage.getItem(`tts_enabled_${roomId}`) || 'false');
    // Выбор помним как общий, а не только для этой комнаты: иначе с каждым новым
    // собеседником режим приходилось бы включать заново
    const savedOnDevice = localStorage.getItem(`on_device_translate_${roomId}`)
      ?? localStorage.getItem('on_device_translate_default');
    if (savedOnDevice === '1') setOnDeviceMode(true);
    console.log('Loaded settings on mount:', { savedTranslationEnabled, savedLanguage, savedTtsEnabled }); // [ИЗМЕНЕНО] Добавлен savedTtsEnabled в лог
    // Устанавливаем начальные состояния только если они ещё не были изменены
    setIsTranslationEnabled((prev) => prev || savedTranslationEnabled); // [ОСТАВЛЕНО]
    setSelectedLanguage((prev) => prev || savedLanguage); // [ОСТАВЛЕНО]
    // [ДОБАВЛЕНО] Устанавливаем ttsEnabled
    setTtsEnabled((prev) => prev || savedTtsEnabled);
    if (languageDropdownRef.current) {
      languageDropdownRef.current.value = savedLanguage;
      console.log('Set dropdown value on mount:', savedLanguage); // [ОСТАВЛЕНО]
    }
  }, []);

  useEffect(() => {
    isTranslationEnabledRef.current = isTranslationEnabled;
    console.log('Updated isTranslationEnabledRef:', isTranslationEnabledRef.current); // [ДОБАВЛЕНО] Лог для отладки
  }, [isTranslationEnabled]);

  useEffect(() => {
    selectedLanguageRef.current = selectedLanguage;
    console.log('Updated selectedLanguageRef:', selectedLanguageRef.current); // [ДОБАВЛЕНО] Лог для отладки
  }, [selectedLanguage]);

  // [ДОБАВЛЕНО] Синхронизация ttsEnabledRef
  useEffect(() => {
    ttsEnabledRef.current = ttsEnabled;
    console.log('Updated ttsEnabledRef:', ttsEnabledRef.current);
  }, [ttsEnabled]);

  useEffect(() => {
    onDeviceModeRef.current = onDeviceMode;
  }, [onDeviceMode]);

  // Ход загрузки моделей — одной обновляемой строкой рядом с переключателем
  useEffect(() => localTranslator.onProgress((event) => {
    // Worker присылает {src, tgt, info}, где info.progress — проценты по файлу
    if (event?.info?.status === 'progress' && typeof event.info.progress === 'number') {
      setModelProgress(Math.min(99, Math.round(event.info.progress)));
    }
  }), []);

  // [ДОБАВЛЕНО] Отправка настроек на сервер
  useEffect(() => {
    console.log('Sending settings to server:', { roomId, currentUserId, isTranslationEnabled, selectedLanguage, ttsEnabled });
    fetch('https://unlimitedpossibilities12.org/api/settings', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        roomId,
        userId: currentUserId,
        translationEnabled: isTranslationEnabled,
        translationLanguage: selectedLanguage,
        ttsEnabled,
        // В бесплатном режиме перевод произносит браузер: серверу синтезировать
        // не нужно, иначе одна фраза прозвучала бы дважды и за деньги
        localTts: onDeviceMode,
      }),
    })
      .then((response) => {
        console.log('Settings response:', { status: response.status, ok: response.ok });
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        localStorage.setItem(`tts_enabled_${roomId}`, JSON.stringify(ttsEnabled));
      })
      .catch((error) => {
        console.error('Error sending settings:', error);
        notify('Failed to save settings.', { toastId: 'settings-save-failed' });
      });
  }, [roomId, currentUserId, isTranslationEnabled, selectedLanguage, ttsEnabled, onDeviceMode]);

  /** Включение бесплатного режима: модели нужны заранее, по согласию пользователя. */
  const toggleOnDeviceMode = useCallback(async (checked) => {
    if (!checked) {
      setOnDeviceMode(false);
      setModelProgress(null);
      try {
        localStorage.setItem(`on_device_translate_${roomId}`, '0');
        localStorage.setItem('on_device_translate_default', '0');
      } catch { /* приватный режим браузера */ }
      if ('speechSynthesis' in window) window.speechSynthesis.cancel();
      return;
    }
    const target = selectedLanguageRef.current;
    if (!target || target === 'auto' || !localTranslator.SUPPORTED.some((l) => l.code === target)) {
      notify('Pick your language first: on-device translation covers English, Russian and Ukrainian.',
        { toastId: 'on-device-language' });
      return;
    }
    const cached = await localTranslator.isTargetCached(target);
    if (!cached) {
      const sizeMb = localTranslator.directionsForTarget(target).length * localTranslator.DIRECTION_SIZE_MB;
      // Честно про объём: модели кладутся в кэш браузера один раз
      if (!window.confirm(`On-device translation needs about ${sizeMb} MB of models, downloaded once. `
        + 'After that translation and speech are free. Download now?')) return;
    }
    setOnDeviceMode(true);
    try {
      localStorage.setItem(`on_device_translate_${roomId}`, '1');
      localStorage.setItem('on_device_translate_default', '1');
    } catch { /* приватный режим браузера */ }
    try {
      await localTranslator.loadForTarget(target);
      setModelProgress(null);
      notify('On-device translation is ready.', { toastId: 'on-device-ready' });
    } catch (error) {
      console.error('Could not load local models:', error);
      setModelProgress(null);
      setOnDeviceMode(false);
      notify('Could not load the models — translation stays on the server.', { toastId: 'on-device-failed' });
    }
  }, [roomId, notify]);

  const scrollToBottom = (ref) => {
    if (ref.current) {
      setTimeout(() => {
        const { scrollTop, scrollHeight, clientHeight } = ref.current;
        // Прокручиваем, если пользователь внизу, близко к низу (допуск 50px) или текста меньше высоты поля
        if (scrollHeight <= clientHeight || scrollTop + clientHeight >= scrollHeight - 50) {
          ref.current.scrollTop = ref.current.scrollHeight;
          console.log('Scrolled to bottom for ref:', ref.current.id || ref.current.className, 
                      { scrollTop, scrollHeight, clientHeight });
        } else {
          console.log('Not scrolling: user is viewing older messages', 
                      { scrollTop, scrollHeight, clientHeight });
        }
      }, 0); // Задержка 0ms для ожидания обновления DOM
    }
  };


  const startAudioCapture = async () => {
      // Вызывается из двух обработчиков состояния WebRTC, а состояние проходит
      // и 'connected', и 'completed' — без этой проверки захват запускался
      // дважды, сервер получал звук в двух копиях, и каждая фраза дважды
      // распознавалась, переводилась и озвучивалась.
      if (mediaRecorderRef.current) {
          console.log('Audio capture already running — skipping second start');
          return;
      }
      try {
          const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
          const audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
          const source = audioContext.createMediaStreamSource(stream);
          const processor = audioContext.createScriptProcessor(1024, 1, 1); // 1024 samples, mono

          // NEW: Обработка аудиоданных в PCM формате
          processor.onaudioprocess = (event) => {
              if (!stompClient.current || !stompClient.current.connected) {
                  console.warn('STOMP client not connected, skipping audio send');
                  return;
              }

              const inputBuffer = event.inputBuffer.getChannelData(0); // Mono channel
              // Convert float32 [-1, 1] to int16 [-32768, 32767]
              const pcmData = new Int16Array(inputBuffer.length);
              for (let i = 0; i < inputBuffer.length; i++) {
                  pcmData[i] = Math.max(-32768, Math.min(32767, inputBuffer[i] * 32767));
              }
              // Convert Int16Array to Buffer and encode to Base64
              const buffer = new Uint8Array(pcmData.buffer);
              const base64Audio = btoa(String.fromCharCode.apply(null, buffer));
              const audioMessage = {
                  audio: base64Audio,
                  sessionId: sessionId,
                  // Распознаванию сообщаем язык пользователя, иначе оно угадывает
                  // по короткой фразе и ошибается
                  spokenLanguage: selectedLanguageRef.current,
              };
              stompClient.current.publish({
                  destination: `/app/audio-transcription/${roomId}`,
                  body: JSON.stringify(audioMessage),
              });
              console.log('Sent PCM audio buffer, length:', buffer.length, 'sessionId:', sessionId);
          };

          // NEW: Подключение аудиоузлов
          source.connect(processor);
          processor.connect(audioContext.destination); // Required to keep processor active
          mediaRecorderRef.current = { stream, audioContext, processor }; // Store for stopping
          console.log('PCM audio capture started, sampleRate: 16000 Hz, mono');
      } catch (error) {
          console.error('Error starting PCM audio capture:', error);
          notify('Failed to access microphone for transcription.', { toastId: 'audio-capture-failed' });
      }
  };

  const stopAudioCapture = () => {
      if (mediaRecorderRef.current) {
          // NEW: Остановка AudioContext и ScriptProcessor
          if (mediaRecorderRef.current.processor) {
              mediaRecorderRef.current.processor.disconnect();
          }
          if (mediaRecorderRef.current.audioContext) {
              mediaRecorderRef.current.audioContext.close().catch((error) => {
                  console.error('Error closing AudioContext:', error);
              });
          }
          if (mediaRecorderRef.current.stream) {
              mediaRecorderRef.current.stream.getTracks().forEach((track) => track.stop());
          }
          mediaRecorderRef.current = null;
          console.log('PCM audio capture stopped');
      }
  };

  // [ДОБАВЛЕНО] Метод для перевода транскрипции через /api/translate
  const translateTranscription = (text, targetLanguage, callback) => {
    console.log('translateTranscription called:', { text, targetLanguage }); // [ДОБАВЛЕНО] Лог вызова
    if (!text || !targetLanguage || targetLanguage === 'auto') { // [ДОБАВЛЕНО] Проверка входных данных
      console.warn('Invalid input for translation:', { text, targetLanguage });
      callback(null);
      return;
    }
    if (onDeviceModeRef.current) {
      // Бесплатный путь: переводят модели в браузере, наружу не уходит ничего
      localTranslator
        .translateToIfNeeded(text, targetLanguage)
        .then((translated) => {
          console.log('Local translation:', translated);
          callback(translated); // null означает «текст и так на нужном языке»
        })
        .catch((error) => {
          console.error('Local translation failed, falling back to server:', error.message);
          callback(null);
        });
      return;
    }
    const requestBody = {
      prompt: text,
      targetLanguage: targetLanguage,
    };

    fetch('https://unlimitedpossibilities12.org/api/translate', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(requestBody),
    })
      .then((response) => {
        console.log('Fetch response:', { status: response.status, ok: response.ok }); // [ДОБАВЛЕНО] Лог ответа
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        return response.text(); // [ОСТАВЛЕНО] Ожидаем строку, как в Android
      })
      .then((translatedText) => {
        console.log('Raw translated text:', translatedText); // [ДОБАВЛЕНО] Лог сырого ответа
        const normalized = translatedText.toLowerCase().replace(/\s+/g, ' ').trim();
        if (translatedText.includes('"') && (normalized.includes('translat') || normalized.includes('phrase') || normalized.includes('word'))) {
          console.error('Filtered out invalid translation:', translatedText);
          callback(null);
          return;
        }
        if (normalized.includes('the translation') || normalized.includes('translated') || normalized.includes('phrase is')) {
          console.error('Filtered out invalid translation with translation/phrase:', translatedText);
          callback(null);
          return;
        }
        let cleanedText = translatedText;
        if (translatedText.startsWith('"') && translatedText.endsWith('"')) {
          cleanedText = translatedText.slice(1, -1);
        }
        console.log(`Translated transcription to ${targetLanguage}: ${cleanedText}`); // [ОСТАВЛЕНО] Лог результата
        callback(cleanedText);
      })
      .catch((error) => {
        console.error('Translation error:', error.message); // [ДОБАВЛЕНО] Лог ошибки
        callback(null);
      });
  };

  const registerRecipient = () => {
      if (stompClient.current && stompClient.current.connected) {
          const registerMessage = {
              sessionId: sessionId,
              recipientId: isCaller ? incomingCall?.recipientId : incomingCall?.callerId,
          };
          // NEW: Проверка наличия recipientId
          if (!registerMessage.recipientId) {
              console.error('recipientId is missing, cannot register recipient', { incomingCall });
              notify('Failed to register recipient: missing ID', { toastId: 'recipient-id-missing' });
              return;
          }
          stompClient.current.publish({
              destination: `/app/register-recipient/${roomId}`,
              body: JSON.stringify(registerMessage),
          });
          console.log('Sent register recipient: sessionId=', registerMessage.sessionId, ', recipientId=', registerMessage.recipientId);
      } else {
          console.error('STOMP client not connected, cannot send register-recipient');
          notify('STOMP client not connected for recipient registration', { toastId: 'stomp-not-connected' });
      }
  };

  const handleIncomingCall = async (callRequest) => {
    console.log('Handling incoming call:', callRequest);
    userName = callerName;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      localAudioRef.current.srcObject = stream;
      console.log('Audio stream obtained successfully.');

      const iceServers = [
        { urls: 'stun:stun.l.google.com:19302' },
        {
          urls: 'turn:13.49.238.121:3478?transport=tcp',
          username: 'username2',
          credential: 'key2',
        },
      ];

      peerConnectionRef.current = new RTCPeerConnection({ iceServers, iceTransportPolicy: 'all' });
      peerConnectionRef.current.ontrack = (event) => {
        console.log('ontrack triggered:', event);
        const stream = event.streams[0];
        if (stream) {
          remoteAudioRef.current.srcObject = stream;
          remoteAudioRef.current.play().catch((err) => console.error('Audio play error:', err));
        }
      };

      peerConnectionRef.current.oniceconnectionstatechange = () => {
        console.log('ICE Connection State:', peerConnectionRef.current.iceConnectionState);
        if (['connected', 'completed'].includes(peerConnectionRef.current.iceConnectionState)) {
          console.log('WebRTC connection established');
          startAudioCapture();
        } else if (peerConnectionRef.current.iceConnectionState === 'failed') {
          console.error('WebRTC connection failed');
          notify('Failed to establish voice connection.', { toastId: 'voice-connection-failed' });
          endCall();
        }
      };

      stream.getTracks().forEach((track) => {
        peerConnectionRef.current.addTrack(track, stream);
      });

      peerConnectionRef.current.onicecandidate = (event) => {
        if (event.candidate) {
          sendSignal({
            from: currentUserId,
            signal: {
              type: 'candidate',
              iceCandidate: {
                candidate: event.candidate.candidate,
                sdpMid: event.candidate.sdpMid,
                sdpMLineIndex: event.candidate.sdpMLineIndex.toString(),
              },
            },
          });
        }
      };

      sendSignal({ from: currentUserId, signal: { type: 'ready', sdp: '' } });
    } catch (error) {
      console.error('Error accessing audio devices:', error);
      notify('Failed to access microphone.', { toastId: 'media-access-failed' });
      endCall();
    }
  };

  const startCall = async () => {
    console.log('Starting call...');
    userName = recipientName;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      localAudioRef.current.srcObject = stream;

      const iceServers = [
        { urls: 'stun:stun.l.google.com:19302' },
        {
          urls: 'turn:13.49.238.121:3478?transport=tcp',
          username: 'username1',
          credential: 'key1',
        },
      ];

      peerConnectionRef.current = new RTCPeerConnection({ iceServers, iceTransportPolicy: 'all' });
      peerConnectionRef.current.ontrack = (event) => {
        const stream = event.streams[0];
        if (stream) {
          remoteAudioRef.current.srcObject = stream;
          remoteAudioRef.current.play().catch((err) => console.error('Audio play error:', err));
        }
      };

      peerConnectionRef.current.oniceconnectionstatechange = () => {
        console.log('ICE Connection State:', peerConnectionRef.current.iceConnectionState);
        if (peerConnectionRef.current.iceConnectionState === 'connected') {
          console.log('WebRTC connection established');
          startAudioCapture();
        } else if (peerConnectionRef.current.iceConnectionState === 'failed') {
          console.error('WebRTC connection failed');
          notify('Failed to establish voice connection.', { toastId: 'voice-connection-failed' });
          endCall();
        }
      };

      stream.getTracks().forEach((track) => {
        peerConnectionRef.current.addTrack(track, stream);
      });

      peerConnectionRef.current.onicecandidate = (event) => {
        if (event.candidate) {
          sendSignal({
            from: currentUserId,
            signal: {
              type: 'candidate',
              iceCandidate: {
                candidate: event.candidate.candidate,
                sdpMid: event.candidate.sdpMid,
                sdpMLineIndex: event.candidate.sdpMLineIndex.toString(),
              },
            },
          });
        }
      };

      const offer = await peerConnectionRef.current.createOffer();
      await peerConnectionRef.current.setLocalDescription(offer);
      sendSignal({ from: currentUserId, signal: offer, roomId });
    } catch (error) {
      console.error('Error starting voice call:', error);
      notify('Failed to start voice call.', { toastId: 'start-call-failed' });
      endCall();
    }
  };

  const handleSignal = async (signalMessage) => {
    console.log('Received signal message:', JSON.stringify(signalMessage, null, 2));
    if (signalMessage.signal) {
      try {
        if (signalMessage.signal.type === 'offer') {
          if (peerConnectionRef.current && !isCaller) {
            await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
            const answer = await peerConnectionRef.current.createAnswer();
            await peerConnectionRef.current.setLocalDescription(answer);
            sendSignal({ from: currentUserId, signal: { type: 'answer', sdp: answer.sdp } });
            // NEW: Устанавливаем hasNotifiedAcceptCall при получении offer
            hasNotifiedAcceptCall.current = true;
            notify(`${recipientName} accepted the call.`, { toastId: `accept-call-${roomId}` });
          }
        } else if (signalMessage.signal.type === 'answer') {
          if (isCaller && peerConnectionRef.current) {
            if (!peerConnectionRef.current.remoteDescription) {
              await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
            }
          }
        } else if (signalMessage.signal.type === 'candidate') {
          const { iceCandidate } = signalMessage.signal;
          if (iceCandidate.candidate && iceCandidate.sdpMid !== null && iceCandidate.sdpMLineIndex !== null) {
            const iceCandidateToAdd = new RTCIceCandidate({
              candidate: iceCandidate.candidate,
              sdpMid: iceCandidate.sdpMid,
              sdpMLineIndex: iceCandidate.sdpMLineIndex,
            });
            try {
              await peerConnectionRef.current.addIceCandidate(iceCandidateToAdd);
              console.log('ICE candidate added:', iceCandidateToAdd);
            } catch (error) {
              console.error('Error adding ICE candidate:', error);
            }
          }
        } else if (signalMessage.signal.type === 'ready' && isCaller) {
          setIsWaiting(false);
          ringtoneRef.current.pause();
          ringtoneRef.current.currentTime = 0;
          setTimeout(() => {
            startCall();
          }, 1000);
        } else if (signalMessage.signal.type === 'endCall') {
          if (!hasNotifiedEndCall.current) {
            hasNotifiedEndCall.current = true;
            notify(`${userName || signalMessage.from} interrupted the conversation.`, {
              toastId: `end-call-${roomId}`,
            });
          }
          terminationInitiator = true;
          endCall();
        } else if (signalMessage.signal.type === 'rejectCall' && isCaller) {
          // NEW: Обработка rejectCall
          if (!hasNotifiedRejectCall.current) {
            hasNotifiedRejectCall.current = true;
            setIsWaiting(false);
            ringtoneRef.current.pause();
            ringtoneRef.current.currentTime = 0;
            notify(`${recipientName} rejected the call.`, { toastId: `reject-call-${roomId}` });
            terminationInitiator = true;
            endCall();
          }
        } else if (signalMessage.signal.type === 'acceptCall' && isCaller) {
          if (!hasNotifiedAcceptCall.current) {
            hasNotifiedAcceptCall.current = true;
            setIsWaiting(false);
            ringtoneRef.current.pause();
            ringtoneRef.current.currentTime = 0;
            notify(`${recipientName} accepted the call.`, { toastId: `accept-call-${roomId}` });
          }
        }
      } catch (error) {
        console.error('Error processing signal:', error);
      }
    }
  };

  const sendSignal = (signalMessage) => {
    if (stompClient.current && stompClient.current.connected) {
      stompClient.current.publish({
        destination: `/app/signal/${roomId}`,
        body: JSON.stringify(signalMessage),
      });
      console.log('Signal sent successfully:', signalMessage);
    } else {
      console.error('Cannot send signal: STOMP client is not connected.');
    }
  };

  const endCall = () => {
    console.log('endCall called'); // NEW: Лог для отладки вызовов endCall

    // NEW: Проверяем, был ли звонок уже сохранён, чтобы избежать дублирования
    if (hasSavedCall.current) {
      console.log('Call already saved, skipping endCall');
      return;
    }

    if (peerConnectionRef.current) {
      peerConnectionRef.current.close();
      peerConnectionRef.current = null;
      console.log('Peer connection closed.');
    }

    if (localAudioRef.current && localAudioRef.current.srcObject) {
      localAudioRef.current.srcObject.getTracks().forEach((track) => track.stop());
      localAudioRef.current.srcObject = null;
    }
    if (remoteAudioRef.current && remoteAudioRef.current.srcObject) {
      remoteAudioRef.current.srcObject.getTracks().forEach((track) => track.stop());
      remoteAudioRef.current.srcObject = null;
    }

    // NEW: Останавливаем захват аудио для транскрипции
    stopAudioCapture();

    if (isCaller) {
      setIsWaiting(false);
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
    }

    if (stompClient.current && stompClient.current.connected) {
      if (!terminationInitiator) {
        sendSignal({
          from: currentUserId,
          signal: { type: 'endCall' },
        });

        if (isCaller && incomingCall?.recipientId) {
          console.log('incomingCall:', incomingCall);
          const endCallMessage = {
            type: 'endCall',
            roomId,
            callerId: currentUserId,
            recipientId: incomingCall.recipientId,
          };
          console.log('Sending endCall to /app/call/', incomingCall.recipientId, ':', endCallMessage);
          stompClient.current.publish({
            destination: `/app/call/${incomingCall.recipientId}`,
            body: JSON.stringify(endCallMessage),
          });
        }
      }

      stompClient.current.deactivate();
      console.log('WebSocket connection deactivated in endCall.');
    }

    // NEW: Добавляем функцию saveCall для сохранения звонка
    const saveCall = async () => {
      console.log('saveCall called with hasNotifiedAcceptCall:', hasNotifiedAcceptCall.current);
      try {
        let status = hasNotifiedRejectCall.current ? 'rejected' : 'completed';

        await axios.post(
          `https://unlimitedpossibilities12.org/api/calls?userId=${currentUserId}`,
          {
            caller: isCaller ? currentUserId : incomingCall?.callerId,
            receiver: isCaller ? incomingCall?.recipientId : currentUserId,
            direction: isCaller ? 'outgoing' : 'incoming',
            status: status,
            callType: 'voice_with_translate',
            timestamp: new Date().toLocaleString('en-US', {
              year: 'numeric',
              month: '2-digit',
              day: '2-digit',
              hour: '2-digit',
              minute: '2-digit',
              second: '2-digit',
              timeZone: 'Europe/Kiev',
              hour12: false,
            }).replace(/(\d+)\/(\d+)\/(\d+), (\d+:\d+:\d+)/, '$3-$1-$2T$4'),
            roomId: roomId, // NEW: Добавляем roomId
          }
        );
        console.log(`Voice call with translate saved to server with status: ${status}`);
        hasSavedCall.current = true;
      } catch (error) {
        console.error('Error saving voice call with translate:', error);
        notify('Failed to save call', { toastId: 'save-call-error' });
      }
    };

    // NEW: Вызываем saveCall перед сбросом флагов и навигацией
    saveCall();

    // NEW: Сброс флагов после saveCall
    hasNotifiedAcceptCall.current = false;
    hasNotifiedEndCall.current = false;
    hasNotifiedRejectCall.current = false;

    navigate('/');
  };

  // Таймер продолжительности звонка
  useEffect(() => {
    const timer = setInterval(() => {
      setCallDuration(prev => prev + 1);
    }, 1000);

    return () => clearInterval(timer);
  }, []);

  return (
    <div className="voice-call-container">
      <div className="voice-call-header">
        <h2 className="voice-call-title">
          Voice communication with {isCaller ? recipientName : callerName}
        </h2>
        <div className="call-duration">
          {Math.floor(callDuration / 60).toString().padStart(2, '0')}:
          {(callDuration % 60).toString().padStart(2, '0')}
        </div>
      </div>
      <div className="audio-container">
        <audio ref={localAudioRef} autoPlay muted />
        <audio ref={remoteAudioRef} autoPlay />
        <audio ref={ttsAudioRef} />
      </div>
      <div className="chart-container-voice-translate">
        <Line
          data={chartData}
          options={{
            responsive: true,
            maintainAspectRatio: false,
            scales: {
              x: {
                title: { display: true, text: 'Time' },
              },
              y: {
                title: { display: true, text: 'Activity Level' },
              },
            },
          }}
        />
      </div>
      <div className="transcription-container">
        <h3 className="transcription-label">Your Voice</h3>
        <div className="transcription-scroll" ref={transcriptionRef}>
          <p className="transcription-text">{transcription}</p>
        </div>
      </div>
      <div className="transcription-container">
        <h3 className="transcription-label">Friend's Voice</h3>
        <div className="transcription-scroll" ref={friendTranscriptionRef}>
          <p className="transcription-text">{friendTranscription}</p>
        </div>
      </div>
      
      <div className="voice-call-translation-container">
        <div className="voice-call-translation-controls">
          <button
            className="voice-call-translation-button"
            onClick={() => {
              const newValue = !isTranslationEnabled;
              console.log('Toggling translation:', { newValue, currentLanguage: selectedLanguage });
              setIsTranslationEnabled(newValue);
              localStorage.setItem(`translation_enabled_${roomId}`, JSON.stringify(newValue));
              if (newValue) {
                const newLang = languageDropdownRef.current?.value || 'auto';
                console.log('Setting language:', newLang);
                setSelectedLanguage(newLang);
                localStorage.setItem(`translation_language_${roomId}`, newLang);
                if (languageDropdownRef.current) {
                  languageDropdownRef.current.value = newLang;
                  console.log('Synced dropdown value:', newLang);
                }
              } else {
                console.log('Disabling translation, resetting to auto');
                setSelectedLanguage('auto');
                localStorage.setItem(`translation_language_${roomId}`, 'auto');
                setFriendTranscription("Friend's Transcription: Waiting...");
                if (languageDropdownRef.current) {
                  languageDropdownRef.current.value = 'auto';
                  console.log('Synced dropdown value to auto');
                }
              }
            }}
            style={{ backgroundColor: isTranslationEnabled ? '#42a5f5' : '#B0BEC5' }}
          >
            {isTranslationEnabled ? 'Translation ON' : 'Translation OFF'}
          </button>
          {/* Список задаёт СВОЙ язык: на нём распознаётся своя речь и на него
              переводится речь собеседника — иначе выбирают язык собеседника
              и распознавание слышит чужой язык */}
          <span
            className="voice-call-language-hint"
            title="You speak this language, and you hear others translated into it"
            style={{ display: isTranslationEnabled ? 'inline' : 'none', marginLeft: '10px', fontSize: '13px' }}
          >
            My language:
          </span>
          <select
            ref={languageDropdownRef}
            className="voice-call-language-dropdown"
            style={{ display: isTranslationEnabled ? 'inline-block' : 'none', marginLeft: '10px' }}
            value={selectedLanguage}
            onChange={(e) => {
              const newLang = e.target.value;
              console.log('Language changed:', { newLang, isTranslationEnabled });
              setSelectedLanguage(newLang);
              localStorage.setItem(`translation_language_${roomId}`, newLang);
              console.log('Updated selectedLanguage:', newLang);
            }}
          >
            {languages.map((lang) => (
              <option key={lang.code} value={lang.code}>
                {lang.emoji} {lang.name}
              </option>
            ))}
          </select>
          <div className="voice-call-tts-controls" style={{ display: isTranslationEnabled ? 'inline-block' : 'none', marginLeft: '10px' }}>
            <input
              type="checkbox"
              id="tts-enabled"
              checked={ttsEnabled}
              onChange={(e) => {
                const newValue = e.target.checked;
                console.log('Toggling TTS:', newValue);
                setTtsEnabled(newValue);
              }}
            />
            <label htmlFor="tts-enabled">Speak aloud</label>
          </div>
          <div
            className="voice-call-tts-controls"
            style={{ display: isTranslationEnabled ? 'inline-block' : 'none', marginLeft: '10px' }}
          >
            <input
              type="checkbox"
              id="on-device-translate"
              checked={onDeviceMode}
              onChange={(e) => toggleOnDeviceMode(e.target.checked)}
            />
            <label
              htmlFor="on-device-translate"
              title="Translation and speech happen in your browser. Your own speech is still recognised on the server."
            >
              On-device translation (free)
              {modelProgress !== null ? ` — loading ${modelProgress}%` : ''}
            </label>
          </div>
        </div>
      </div>

      <button className="end-call-button-voice" onClick={endCall}>
        End Call
      </button>
    </div>
  );
};

export default VoiceCallWithTranslate;