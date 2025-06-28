import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Line } from 'react-chartjs-2';
import { Chart, registerables } from 'chart.js';
import { useNotification } from '../context/NotificationContext';
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
  const transcriptionRef = useRef(null);
  const friendTranscriptionRef = useRef(null);
  // [ДОБАВЛЕНО] Состояния для переключателя перевода, выбранного языка и рефа для выпадающего списка
  const [isTranslationEnabled, setIsTranslationEnabled] = useState(false);
  const [selectedLanguage, setSelectedLanguage] = useState('en');
  const languageDropdownRef = useRef(null);
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
              setFriendTranscription(`Error: ${json.error}`);
              scrollToBottom(friendTranscriptionRef);
            } else if (json.transcription) {
              if (isTranslationEnabled && selectedLanguage && selectedLanguage !== 'auto') {
                // Выполняем перевод асинхронно
                translateTranscription(json.transcription, selectedLanguage, (translatedText) => {
                  const displayText = translatedText || json.transcription;
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
                // Отображаем оригинальный текст
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

        // [ИЗМЕНЕНО] Загрузка сохранённых настроек перевода из localStorage
        const savedTranslationEnabled = JSON.parse(localStorage.getItem(`translation_enabled_${roomId}`) || 'false');
        const savedLanguage = localStorage.getItem(`translation_language_${roomId}`) || 'en';
        setIsTranslationEnabled(savedTranslationEnabled);
        setSelectedLanguage(savedLanguage);
        if (languageDropdownRef.current) {
          languageDropdownRef.current.value = savedLanguage;
        }
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame);
        notify('STOMP protocol error.', { toastId: 'stomp-error' });
      },
      onWebSocketError: (error) => {
        console.warn('WebSocket error:', error);
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected');
      },
    });
    stompClient.current.activate();

    const interval = setInterval(() => {
      setChartData((prevData) => {
        const newLabels = [...prevData.labels, new Date().toLocaleTimeString()];
        const newData = [...prevData.datasets[0].data, Math.random() * 100];
        return {
          labels: newLabels,
          datasets: [{ ...prevData.datasets[0], data: newData }],
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
      // [ИЗМЕНЕНО] Очистка настроек перевода
      localStorage.removeItem(`translation_enabled_${roomId}`);
      localStorage.removeItem(`translation_language_${roomId}`);
    };
  }, [currentUserId, roomId, incomingCall]);

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
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        return response.text();
      })
      .then((translatedText) => {
        // [ДОБАВЛЕНО] Фильтрация нежелательных переводов
        const normalized = translatedText.toLowerCase().replace(/\s+/g, ' ').trim();
        if (translatedText.includes('"') && (normalized.includes('translat') || normalized.includes('phrase') || normalized.includes('word'))) {
          console.error('Filtered out invalid translation:', translatedText);
          callback(null); // Возвращаем null, чтобы использовать оригинальный текст
          return;
        }
        // [ДОБАВЛЕНО] Дополнительная проверка на нежелательные фразы
        if (normalized.includes('the translation') || normalized.includes('translated') || normalized.includes('phrase is')) {
          console.error('Filtered out invalid translation with translation/phrase:', translatedText);
          callback(null); // Возвращаем null для строк вроде "The translation phrase is ..."
          return;
        }
        // [ДОБАВЛЕНО] Удаляем кавычки, если они есть
        let cleanedText = translatedText;
        if (translatedText.startsWith('"') && translatedText.endsWith('"')) {
          cleanedText = translatedText.slice(1, -1);
        }
        console.log(`Translated transcription to ${targetLanguage}: ${cleanedText}`);
        callback(cleanedText);
      })
      .catch((error) => {
        console.error('Error during translation request:', error.message);
        callback(null); // Возвращаем null в случае ошибки
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
          urls: 'turn:16.170.203.109:3478?transport=tcp',
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
          urls: 'turn:16.170.203.109:3478?transport=tcp',
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
          setIsWaiting(false);
          ringtoneRef.current.pause();
          ringtoneRef.current.currentTime = 0;
          notify(`${recipientName} rejected the call.`, { toastId: `reject-call-${roomId}` });
          terminationInitiator = true;
          endCall();
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
    console.log('Ending call...');

    if (peerConnectionRef.current) {
      peerConnectionRef.current.close();
      peerConnectionRef.current = null;
    }

    if (localAudioRef.current && localAudioRef.current.srcObject) {
      localAudioRef.current.srcObject.getTracks().forEach((track) => track.stop());
      localAudioRef.current.srcObject = null;
    }
    if (remoteAudioRef.current && remoteAudioRef.current.srcObject) {
      remoteAudioRef.current.srcObject.getTracks().forEach((track) => track.stop());
      remoteAudioRef.current.srcObject = null;
    }

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
          const endCallMessage = {
            type: 'endCall',
            roomId,
            callerId: currentUserId,
            recipientId: incomingCall.recipientId,
          };
          stompClient.current.publish({
            destination: `/app/call/${incomingCall.recipientId}`,
            body: JSON.stringify(endCallMessage),
          });
        }
      }

      stompClient.current.deactivate();
    }

    navigate('/');
  };

  return (
    <div className="voice-call-container">
      <div className="voice-call-header">
        <h2 className="voice-call-title">Voice communication with {isCaller ? recipientName : callerName}</h2>
      </div>
      <div className="audio-container">
        <audio ref={localAudioRef} autoPlay muted />
        <audio ref={remoteAudioRef} autoPlay />
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
      
      // [ДОБАВЛЕНО] Контейнер для переключателя и выпадающего списка
      <div className="translation-controls">
        <button
          className="translation-toggle-button"
          onClick={() => {
            const newValue = !isTranslationEnabled;
            setIsTranslationEnabled(newValue);
            localStorage.setItem(`translation_enabled_${roomId}`, JSON.stringify(newValue));
            if (newValue) {
              const newLang = languageDropdownRef.current?.value || 'en';
              setSelectedLanguage(newLang);
              localStorage.setItem(`translation_language_${roomId}`, newLang);
            } else {
              setSelectedLanguage('en');
              localStorage.removeItem(`translation_language_${roomId}`);
            }
          }}
          title={isTranslationEnabled ? 'Disable translation' : 'Enable translation'}
          style={{ color: isTranslationEnabled ? '#42a5f5' : '#B0BEC5' }}
        >
          🌐
        </button>
        {isTranslationEnabled && (
          <select
            ref={languageDropdownRef}
            className="language-dropdown"
            value={selectedLanguage}
            onChange={(e) => {
              const newLang = e.target.value;
              setSelectedLanguage(newLang);
              localStorage.setItem(`translation_language_${roomId}`, newLang);
            }}
          >
            {languages.map((lang) => (
              <option key={lang.code} value={lang.code}>
                {lang.emoji} {lang.name}
              </option>
            ))}
          </select>
        )}
      </div>

      <button className="end-call-button-voice" onClick={endCall}>
        End Call
      </button>
    </div>
  );
};

export default VoiceCallWithTranslate;