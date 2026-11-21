import React, { useEffect, useRef, useState, useMemo } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { Line } from 'react-chartjs-2';
import { Chart, registerables } from 'chart.js';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import { createCallGuard, CallSecurity } from '../utils/callGuard';
import axios from 'axios';
import '../styles/GroupCallActivity.css';

/** Короткая подпись под именем участника — что вышло с его подписью. */
const securityWord = (state) => {
  switch (state) {
    case CallSecurity.CONFIRMED: return 'confirmed by you';
    case CallSecurity.VERIFIED: return 'verified';
    case CallSecurity.NEW_DEVICE: return 'new device';
    case CallSecurity.CHANGED: return 'key changed';
    case CallSecurity.FAILED: return 'not verified';
    case CallSecurity.ENCRYPTED: return 'encrypted';
    default: return 'checking…';
  }
};

/** Цвет замка: тревожим только когда есть чем. */
const securityColor = (state) => {
  switch (state) {
    case CallSecurity.CONFIRMED:
    case CallSecurity.VERIFIED: return '#34D399';
    case CallSecurity.CHANGED: return '#FBBF24';
    case CallSecurity.FAILED: return '#F87171';
    default: return '#94A3B8';
  }
};

Chart.register(...registerables);

const GroupCallActivity = ({ call = {}, minimized = false, onMinimize, onClose }) => {
  const navigate = useNavigate();
  const location = useLocation();
  // Разговор живёт в каркасе и получает своё свойствами; переход по
  // адресу остался запасным путём
  const state = Object.keys(call).length ? call : location.state;
  const { participants = [], initiator = {}, roomId: initialRoomId, alreadyIn = [] } = state || {};
  /*
   * Приглашение уходит всем — с него начинается знакомство в комнате.
   * Молчит оно лишь у того, кто уже сюда перешёл: его клиент видит, что
   * комната та же, в которой он и так находится, и звонка не поднимает.
   */
  const isInitiator = Number(localStorage.getItem('userId')) === initiator.id;
  const currentUserId = localStorage.getItem('userId');
  /*
   * Если комнату уже назвали — берём её.
   *
   * Заводящий разговор прежде всегда придумывал свою, и перевод из
   * разговора вдвоём ломался: собеседнику называли одну комнату, а сам
   * звавший уходил в другую.
   */
  const roomId = useRef(initialRoomId || (isInitiator ? crypto.randomUUID() : null));
  const peerConnections = useRef(new Map());
  /**
   * По стражу на каждого собеседника.
   *
   * Групповой звонок у нас — не общий поток через сервер, а несколько
   * обычных парных соединений. Значит и защита парная: с каждым своя подпись
   * сведений о соединении, своя проверка его ключа и свой код для сверки
   * голосом. Общей выработки ключей на компанию не нужно.
   */
  const guards = useRef(new Map());
  // Чей код сверки сейчас открыт
  const [codeFor, setCodeFor] = useState(null);
  // Свой микрофон: в групповом разговоре его нельзя было выключить
  const [muted, setMuted] = useState(false);
  /**
   * У кого из собеседников включена камера.
   *
   * Прежде плитки с видео показывались, только если ты сам нажимал
   * камеру: собеседник включал свою, а у тебя на экране не менялось
   * ничего, и он говорил в пустоту. Теперь вид переключается сам, как
   * только у кого-то появилась картинка, — своя камера при этом не
   * включается, её по-прежнему включают кнопкой.
   */
  const [cameraOnUsers, setCameraOnUsers] = useState([]);
  /**
   * Пришедшие картинки, по собеседнику.
   *
   * Поток приходит раньше, чем на экране появляется плитка для него:
   * плитки рисуются только в видимом виде, а он включается следом. Класть
   * картинку было некуда, и она пропадала — собеседник включал камеру, а
   * его не видели, пока он не выключит и не включит её снова.
   */
  const remoteStreams = useRef(new Map());
  /**
   * Места под нашу картинку у каждого собеседника.
   *
   * Заводятся при первом включении камеры и живут до конца разговора:
   * выключая камеру, мы вынимаем из места дорожку, а само место
   * оставляем. Тогда договариваться о связи заново не нужно — а
   * договариваться каждый раз мы как раз и не умеем: после нескольких
   * кругов стороны застревают, ожидая ответа друг от друга.
   */
  const местаПодКартинку = useRef(new Map());
  /** С кем нужно договориться заново: место под картинку только что заведено. */
  const нужноДоговориться = useRef(new Set());
  /** Свёрнутый разговор занимает полоску — приложение двигается под неё. */
  useEffect(() => {
    document.body.classList.toggle('has-call-strip', Boolean(minimized));
    return () => document.body.classList.remove('has-call-strip');
  }, [minimized]);
  const [securityByUser, setSecurityByUser] = useState({});
  const localAudioRef = useRef(null);
  const remoteAudioRefs = useRef(new Map());
  const pendingCandidates = useRef(new Map());
  const [callParticipants, setCallParticipants] = useState(participants);

  /**
   * Позвать ещё одного в идущий разговор.
   *
   * Здесь проще, чем в разговоре вдвоём: комната уже есть и все в ней, —
   * новому человеку уходит обычное приглашение, он отвечает, и с каждым
   * из нас у него складывается своё соединение со своим замком.
   */
  const [peopleOpen, setPeopleOpen] = useState(false);
  const [people, setPeople] = useState([]);

  useEffect(() => {
    if (!peopleOpen || people.length) return;
    (async () => {
      try {
        const ответ = await fetch('https://unlimitedpossibilities12.org/api/users');
        if (!ответ.ok) return;
        const все = await ответ.json();
        const свои = new Set(callParticipants.map((у) => String(у.id)));
        свои.add(String(currentUserId));
        setPeople(все.filter((ч) => !свои.has(String(ч.id))));
      } catch (ошибка) {
        console.warn('Список людей не пришёл:', ошибка);
      }
    })();
  }, [peopleOpen, people.length, callParticipants, currentUserId]);

  const позвать = (человек) => {
    setPeopleOpen(false);
    setPeople([]);
    const новый = { id: Number(человек.id), username: человек.displayName || человек.username, avatar: человек.avatar || null };
    setCallParticipants((prev) => (prev.some((у) => Number(у.id) === новый.id) ? prev : [...prev, новый]));
    const приглашение = {
      type: 'group',
      initiatorId: String(initiator.id),
      initiatorName: initiator.username,
      roomId: roomId.current,
      recipientIds: [...callParticipants.filter((у) => у.id !== initiator.id).map((у) => String(у.id)), String(новый.id)],
    };
    sendSignal(приглашение, roomId.current, String(новый.id));
    toast.info(`${новый.username} was invited`, { toastId: 'invited' });
  };
  const [callDuration, setCallDuration] = useState('00:00');
  const [startTime, setStartTime] = useState('');
  const [currentTime, setCurrentTime] = useState('');
  const [readyUsers, setReadyUsers] = useState(new Set());
  const [isWaiting, setIsWaiting] = useState(isInitiator);
  const [isCallStarted, setIsCallStarted] = useState(false);
  const [isCallEnded, setIsCallEnded] = useState(false);
  const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));
  const callStartTime = useRef(Date.now());
  /**
   * Короткий отпечаток содержания.
   *
   * По нему отличают одно предложение от другого: начала у них
   * одинаковые, а целиком сравнивать накладно.
   */
  const отпечатокСодержания = (строка) => {
    let итог = 5381;
    const с = String(строка || '');
    for (let i = 0; i < с.length; i += 1) итог = ((итог * 33) ^ с.charCodeAt(i)) >>> 0;
    return `${с.length}-${итог}`;
  };

  const hasNotifiedEndCall = useRef(false);
  /**
   * Кого из собеседников уже проводили.
   *
   * Прежде на всех уходы была одна отметка: проводив первого, экран
   * переставал замечать остальных. Втроём это видно так — вышел второй,
   * а у последнего разговор всё идёт, с пустой комнатой и бегущим
   * временем, пока он сам не нажмёт отбой.
   */
  const ушедшие = useRef(new Set());
  const hasSentInvite = useRef(false);
  const answerTimeouts = useRef(new Map()); // Таймауты для ожидания answer
  let isMounted = true;
  const offerReceived = useRef(new Map()); // Флаг для каждого пользователя
  const hasSavedCall = useRef(false); // NEW: Флаг для предотвращения двойного сохранения звонка

  const memoizedParticipants = useMemo(() => participants, [JSON.stringify(participants)]);
  const hasProcessedInvite = useRef(new Set());
  const unsubscribeRoomRef = useRef(null);
  const offerTimeoutRef = useRef(null);
  const readyTimeoutRef = useRef(null);
  const sentSignals = useRef(new Set());
  const receivedSignals = useRef(new Set());
  const signalQueue = useRef(new Map());
  const isProcessingSignal = useRef(new Map());
  const [isVideoMode, setIsVideoMode] = useState(false); // Состояние для переключения между аудио- и видео-вкладками
  const videoRefs = useRef(new Map()); // Ref для хранения ссылок на видеоэлементы
  const [isCameraOn, setIsCameraOn] = useState(false); // Состояние для отслеживания включения камеры текущего пользователя
  const localVideoStreamRef = useRef(null); // Ref для хранения локального видеопотока

  // Режим пропорций плиток участников: 'auto' — по реальному разрешению потока,
  // 'landscape' и 'portrait' — принудительно одинаковые плитки. Выбор запоминается.
  const [tileMode, setTileMode] = useState(() => {
    const stored = localStorage.getItem('groupCallTileMode');
    return stored === 'landscape' || stored === 'portrait' ? stored : 'auto';
  });
  // userId -> соотношение сторон входящего видео (ширина / высота)
  const [videoAspects, setVideoAspects] = useState({});

  const { sendSignal, setGroupSignalHandler, subscribeToRoom } = useCallWebSocket();

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

  console.log('GroupCallActivity: Initial state:', { state, initialRoomId, isInitiator, roomId: roomId.current });

  useEffect(() => {
    console.log('useEffect called with dependencies:', {
      currentUserId,
      initiatorId: initiator.id,
      isInitiator,
      isCallEnded,
      roomId: roomId.current,
      participants: memoizedParticipants,
    });

    if (!currentUserId || !initiator.id) {
      console.error('User ID or initiator ID is missing');
      toast.error('Could not open the call', { toastId: 'call-open' });
      navigate('/');
      return;
    }

    if (!roomId.current) {
      console.error('roomId is null, cannot proceed');
      toast.error('Could not open the call', { toastId: 'call-open' });
      navigate('/');
      return;
    }

    const initializeCall = async () => {
      if (isCallEnded) {
        console.log('Call already ended, skipping initialization');
        return;
      }

      try {
        // [ИЗМЕНЕНО]: Запрашиваем только аудио для начальной инициализации
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        console.log('Local stream tracks:', stream.getTracks());
        if (localAudioRef.current) {
          localAudioRef.current.srcObject = stream;
          console.log('Assigned stream to localAudioRef');
          // [ДОБАВЛЕНО]: Назначаем аудиопоток в localVideoStreamRef для согласованности
          localVideoStreamRef.current = stream;
          // Обработка очереди сигналов после установки srcObject
          handleQueuedSignals();
        } else {
          console.error('localAudioRef is null during stream assignment');
          console.warn('Звуковой узел ещё не готов');
          endCall();
          return;
        }

        const fetchParticipants = async () => {
          try {
            // Создаем объект текущего пользователя
            const currentUser = {
              id: Number(currentUserId),
              username: initiator.username || `User ${currentUserId}`,
              avatar: null, // Аватар будет запрошен у сервера
            };

            // Добавляем текущего пользователя, если его нет в списке
            const participantsWithCurrentUser = memoizedParticipants.find((p) => p.id === Number(currentUserId))
              ? memoizedParticipants
              : [...memoizedParticipants, currentUser];

            const updatedParticipants = await Promise.all(
              participantsWithCurrentUser.map(async (user) => {
                // Запрашиваем данные для всех пользователей, включая текущего
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${user.id}`, {
                  headers: { Authorization: `Bearer ${localStorage.getItem('authToken')}` },
                });
                if (!response.ok) {
                  console.error(`Failed to fetch user ${user.id}`);
                  return {
                    id: user.id,
                    username: user.username || `User ${user.id}`,
                    avatar: null, // Если запрос не удался, аватар будет null
                  };
                }
                const userData = await response.json();
                return {
                  id: user.id,
                  username: userData.username || `User ${user.id}`,
                  avatar: userData.avatar || null,
                };
              })
            );
            setCallParticipants(updatedParticipants);
            console.log('Updated callParticipants:', updatedParticipants);
          } catch (error) {
            console.error('Error fetching participants:', error);
            console.warn('Не удалось дочитать сведения об участниках');
          }
        };
        await fetchParticipants();

        for (const user of memoizedParticipants) {
          if (user.id !== Number(currentUserId) && !peerConnections.current.has(String(user.id))) {
            console.log('Initializing peerConnection for user:', user.id);
            await initializePeerConnection(String(user.id), stream);
          }
        }

        setGroupSignalHandler(handleSignal);

        if (isInitiator) {
          sendSignal({ signal: { type: 'group', roomId: roomId.current } }, roomId.current);
        }

        // Подписка на комнату с обработкой повторного подключения
        const subscribeWithReconnect = () => {
          let hasNotifiedError = false; // Флаг для предотвращения множественных тостов
          const trySubscribe = () => {
            unsubscribeRoomRef.current = subscribeToRoom(
              roomId.current,
              () => {
                console.log(`Successfully subscribed to room ${roomId.current}`);
                hasNotifiedError = false; // Сбрасываем флаг при успехе
                if (isInitiator && !hasSentInvite.current) {
                  hasSentInvite.current = true;
                  sendGroupCallInvite();
                } else if (!isInitiator && !hasSentInvite.current) {
                  hasSentInvite.current = true;
                  console.log('Sending acceptCall signal');
                  const acceptCallKey = `${currentUserId}-acceptCall`;
                  if (!sentSignals.current.has(acceptCallKey)) {
                    sentSignals.current.add(acceptCallKey);
                    sendSignal({ from: currentUserId, signal: { type: 'acceptCall' } }, roomId.current);
                  }
                  memoizedParticipants
                    .filter((p) => p.id !== Number(currentUserId))
                    .forEach((user) => {
                      const signalKey = `${currentUserId}-${user.id}-ready`;
                      if (!sentSignals.current.has(signalKey)) {
                        sentSignals.current.add(signalKey);
                        sendSignal(
                          { from: currentUserId, to: String(user.id), signal: { type: 'ready' } },
                          roomId.current
                        );
                        console.log(`Sent ready to user ${user.id}`);
                      }
                    });
                }
                if (isCallStarted) {
                  resendPendingSignals();
                }
              },
              (error) => {
                console.error('Subscription error:', error);
                if (!hasNotifiedError) {
                  hasNotifiedError = true;
                  toast.error('Lost the call server, reconnecting…', { toastId: 'call-server' });
                }
                const delay = Math.min(1000 * Math.pow(2, hasNotifiedError ? 1 : 0), 10000); // Экспоненциальная задержка
                setTimeout(trySubscribe, delay);
              }
            );
          };
          trySubscribe();
        };

        subscribeWithReconnect();

        if (isInitiator) {
          setIsWaiting(true);
          ringtoneRef.current.loop = true;
          ringtoneRef.current.play().catch((error) => {
            if (isMounted) console.error('Error playing ringtone:', error);
          });
        }
      } catch (error) {
        console.error('Error initializing call:', error);
        toast.error('Could not start the call', { toastId: 'call-start' });
        endCall();
      }
    };

    const waitForLocalAudio = () => {
      const audioElement = document.getElementById('local-audio');
      if (audioElement) {
        console.log('local-audio element found, assigning to localAudioRef');
        localAudioRef.current = audioElement;
        initializeCall();
      } else {
        console.warn('local-audio element not found, setting up MutationObserver...');
        const observer = new MutationObserver((mutations, obs) => {
          const audio = document.getElementById('local-audio');
          if (audio) {
            console.log('local-audio element found via MutationObserver');
            localAudioRef.current = audio;
            initializeCall();
            obs.disconnect();
          }
        });
        observer.observe(document.body, { childList: true, subtree: true });
        setTimeout(() => {
          if (!localAudioRef.current) {
            console.error('Local audio element not found after timeout');
            console.warn('Звуковой узел ещё не готов');
            endCall();
            observer.disconnect();
          }
        }, 5000);
      }
    };

    waitForLocalAudio();

    const timerInterval = setInterval(() => {
      if (!isMounted) return;
      const duration = Math.floor((Date.now() - callStartTime.current) / 1000);
      const minutes = Math.floor(duration / 60);
      const seconds = duration % 60;
      setCallDuration(`${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`);
    }, 1000);

    const timeInterval = setInterval(() => {
      if (!isMounted) return;
      const now = new Date();
      setCurrentTime(now.toLocaleTimeString('en-US', { hour12: false }));
      if (!startTime) {
        setStartTime(now.toLocaleTimeString('en-US', { hour12: false }));
      }
    }, 1000);

    const chartInterval = setInterval(() => {
      if (!isMounted) return;
      setChartData((prevData) => {
        const newLabels = [...prevData.labels, new Date().toLocaleTimeString()];
        const newData = [...prevData.datasets[0].data, Math.random() * 100];
        return {
          labels: newLabels.slice(-20),
          datasets: [{ ...prevData.datasets[0], data: newData.slice(-20) }],
        };
      });
    }, 1000);

    readyTimeoutRef.current = setTimeout(() => {
      console.log('readyTimeout check:', { isInitiator, isWaiting, readyUsers: Array.from(readyUsers), isCallStarted });
      if (isInitiator && isWaiting && isMounted && !isCallStarted) {
        let hasActiveConnections = false;
        peerConnections.current.forEach((pc, userId) => {
          if (pc.iceConnectionState === 'connected') {
            hasActiveConnections = true;
            console.log(`Active connection detected with user ${userId}, skipping endCall`);
          }
        });
        if (!hasActiveConnections) {
          console.warn('No active connections within 30 seconds, ending call.');
          toast.error('Nobody answered', { toastId: 'no-answer' });
          endCall();
        }
      }
    }, 90000);

    offerTimeoutRef.current = setTimeout(() => {
      if (!isInitiator && !isCallStarted && isMounted) {
        let hasActiveConnections = false;
        peerConnections.current.forEach((pc, userId) => {
          if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
            hasActiveConnections = true;
            console.log(`Active connection with user ${userId}, skipping offer timeout`);
          }
        });
        if (!hasActiveConnections) {
          console.error('No offer received within 60 seconds and no active connections, aborting call');
          console.warn('Предложение соединиться не пришло');
          endCall();
        } else {
          console.log('Offer timeout skipped due to active connections');
        }
      }
    }, 90000);

    return () => {
      isMounted = false;
      clearInterval(timerInterval);
      clearInterval(timeInterval);
      clearInterval(chartInterval);
      if (readyTimeoutRef.current) clearTimeout(readyTimeoutRef.current);
      if (offerTimeoutRef.current) {
        clearTimeout(offerTimeoutRef.current);
        offerTimeoutRef.current = null;
      }
      answerTimeouts.current.forEach((timeout) => clearTimeout(timeout));
      answerTimeouts.current.clear();
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
      peerConnections.current.forEach((pc) => pc.close());
      peerConnections.current.clear();
      if (localAudioRef.current && localAudioRef.current.srcObject) {
        localAudioRef.current.srcObject.getTracks().forEach((track) => track.stop());
        localAudioRef.current.srcObject = null;
      }
      remoteAudioRefs.current.forEach((audio) => {
        if (audio.srcObject) {
          audio.srcObject.getTracks().forEach((track) => track.stop());
          audio.srcObject = null;
        }
        audio.remove();
      });
      remoteAudioRefs.current.clear();
      if (unsubscribeRoomRef.current) {
        unsubscribeRoomRef.current();
        console.log(`Unsubscribed from room ${roomId.current}`);
      }
    };
  }, [currentUserId, initiator.id, isInitiator, isCallEnded, memoizedParticipants, sendSignal, setGroupSignalHandler, navigate]);

  // Периодическая проверка соединений
  useEffect(() => {
    if (!isCallStarted || isCallEnded || !isMounted) return;

    let checkCount = 0; // Счётчик для первых трёх проверок
    let intervalId = setInterval(() => {
        console.log(`Periodic connection check triggered, checkCount=${checkCount}`);
        let hasFailedConnections = false;
        const checkedUsers = new Set();

        // Проверяем существующие peerConnections
        peerConnections.current.forEach((pc, userId) => {
            checkedUsers.add(userId);
            if (
                pc.iceConnectionState !== 'connected' &&
                pc.iceConnectionState !== 'completed' &&
                pc.iceConnectionState !== 'closed' ||
                pc.connectionState === 'new' ||
                pc.connectionState === 'connecting' ||
                pc.connectionState === 'failed'
            ) {
                hasFailedConnections = true;
                console.log(`Connection with user ${userId} not established, iceConnectionState: ${pc.iceConnectionState}, connectionState: ${pc.connectionState}`);
            } else {
                console.log(`Connection with user ${userId} established, iceConnectionState: ${pc.iceConnectionState}, connectionState: ${pc.connectionState}`);
            }
        });

        // Проверяем участников без peerConnections
        callParticipants.forEach((user) => {
            if (user.id !== Number(currentUserId) && !checkedUsers.has(String(user.id))) {
                hasFailedConnections = true;
                console.log(`No peerConnection for user ${user.id}, marking as failed`);
            }
        });

        if (hasFailedConnections) {
            console.log('Initiating retry for failed or missing connections');
            retryFailedConnections();
        } else {
            console.log('All connections established, no retry needed');
        }

        // Увеличиваем счётчик и определяем следующий интервал
        checkCount++;
        if (checkCount >= 3) {
            clearInterval(intervalId);
            intervalId = setInterval(() => {
                console.log('Periodic connection check triggered (long interval)');
                let hasFailedConnections = false;
                const checkedUsers = new Set();

                peerConnections.current.forEach((pc, userId) => {
                    checkedUsers.add(userId);
                    if (
                        pc.iceConnectionState !== 'connected' &&
                        pc.iceConnectionState !== 'completed' &&
                        pc.iceConnectionState !== 'closed' ||
                        pc.connectionState === 'new' ||
                        pc.connectionState === 'connecting' ||
                        pc.connectionState === 'failed'
                    ) {
                        hasFailedConnections = true;
                        console.log(`Connection with user ${userId} not established, iceConnectionState: ${pc.iceConnectionState}, connectionState: ${pc.connectionState}`);
                    } else {
                        console.log(`Connection with user ${userId} established, iceConnectionState: ${pc.iceConnectionState}, connectionState: ${pc.connectionState}`);
                    }
                });

                callParticipants.forEach((user) => {
                    if (user.id !== Number(currentUserId) && !checkedUsers.has(String(user.id))) {
                        hasFailedConnections = true;
                        console.log(`No peerConnection for user ${user.id}, marking as failed`);
                    }
                });

                if (hasFailedConnections) {
                    console.log('Initiating retry for failed or missing connections');
                    retryFailedConnections();
                } else {
                    console.log('All connections established, no retry needed');
                }
            }, 10000); // Проверка каждые 10 секунд
        }
    }, 4000); // Первые три проверки каждые 4 секунды

    return () => {
        clearInterval(intervalId);
        console.log('Cleared connection check interval');
    };
  }, [isCallStarted, isCallEnded, callParticipants]);

  // Обработка очереди сигналов
  useEffect(() => {
    if (localAudioRef.current && localAudioRef.current.srcObject && signalQueue.current.size > 0) {
      console.log('Processing queued signals:', signalQueue.current.size);
      signalQueue.current.forEach((signals, from) => {
        while (signals.length > 0) {
          const signal = signals.shift();
          handleSignal(signal);
        }
      });
    }
  }, [localAudioRef.current, localAudioRef.current?.srcObject]);

  const sendGroupCallInvite = () => {
    if (!roomId.current) {
      console.error('Cannot send group call invite: roomId is null');
      return;
    }
    const groupCallRequest = {
      type: 'group',
      initiatorId: String(initiator.id),
      initiatorName: initiator.username,
      roomId: roomId.current,
      recipientIds: callParticipants
        .filter((p) => p.id !== initiator.id)
        .map((p) => String(p.id)),
    };

    callParticipants
      .filter((p) => p.id !== initiator.id)
      .forEach((user) => {
        const signalKey = `${currentUserId}-${user.id}-groupCallInvite`;
        if (!sentSignals.current.has(signalKey)) {
          sentSignals.current.add(signalKey);
          console.log('GroupCallActivity: Подготовка groupCallInvite для пользователя:', user.id, groupCallRequest);
          sendSignal(groupCallRequest, roomId.current, String(user.id));
          console.log(`GroupCallActivity: Sent group call invite to user ${user.id}`, groupCallRequest);
        }
      });
  };

  const resendPendingSignals = () => {
    console.log('Resending pending signals for unconnected peers');
    peerConnections.current.forEach(async (pc, userId) => {
        if (pc.iceConnectionState !== 'connected' && pc.iceConnectionState !== 'completed') {
            // Проверяем, установлено ли соединение
            if (
                pc.iceConnectionState === 'connected' &&
                pc.signalingState === 'stable'
            ) {
                console.log(`Connection with user ${userId} is already established, skipping offer resend`);
                return;
            }
            if (pc.localDescription && pc.localDescription.type === 'offer' && !pc.remoteDescription) {
                const signalKey = `${currentUserId}-${userId}-offer-${отпечатокСодержания(pc.localDescription.sdp)}`;
                console.log(`Resending offer to user ${userId}`);
                sendSignal(
                    {
                        from: currentUserId,
                        to: userId,
                        signal: {
                            type: 'offer',
                            sdp: pc.localDescription.sdp,
                            ...(await signFor(userId, pc.localDescription.sdp)),
                        },
                    },
                    roomId.current
                );
                sentSignals.current.add(signalKey);
            }
        }
    });
  };

  /** Страж для собеседника: заводим один раз и держим до конца звонка. */
  const guardFor = async (userId) => {
    const key = String(userId);
    if (guards.current.has(key)) return guards.current.get(key);
    try {
      const guard = await createCallGuard(currentUserId, userId);
      guards.current.set(key, guard);
      // Ключ собеседника мог появиться позже — страж доспросит его сам и
      // скажет сюда, чтобы подпись под именем поправилась
      if (guard.onState) guard.onState((state) => setSecurityByUser((prev) => ({ ...prev, [key]: state })));
      setSecurityByUser((prev) => ({ ...prev, [key]: guard.state() }));
      return guard;
    } catch (error) {
      console.warn(`Не удалось завести стража для ${userId}:`, error);
      return null;
    }
  };

  /** Подписать своё описание соединения для этого собеседника. */
  const signFor = async (userId, sdp) => {
    const guard = await guardFor(userId);
    return guard ? { signature: guard.sign(sdp), deviceId: guard.deviceId } : {};
  };

  /**
   * Проверить подпись собеседника. Если не сошлась — рвём соединение
   * только с ним: остальные в звонке не виноваты и продолжают говорить.
   */
  const verifyFrom = async (userId, sdp, signature, senderDeviceId) => {
    const guard = await guardFor(userId);
    if (!guard) return false;
    const state = guard.verify(sdp, signature, senderDeviceId);
    setSecurityByUser((prev) => ({ ...prev, [String(userId)]: state }));
    if (state === CallSecurity.FAILED) {
      console.error(`Подпись участника ${userId} не сошлась — отключаем его`);
      toast.error('Could not verify one of the participants — they were disconnected', { toastId: 'verify' });
      const pc = peerConnections.current.get(String(userId));
      if (pc) {
        pc.close();
        peerConnections.current.delete(String(userId));
      }
      return true;
    }
    return false;
  };

  const initializePeerConnection = async (userId, stream) => {
    try {
      if (!localAudioRef.current || !stream) {
        console.error(`Cannot initialize PeerConnection for user ${userId}: localAudioRef or stream is null`);
        console.warn('Звуковой узел или поток ещё не готовы');
        endCall();
        return null;
      }

      console.log('initializePeerConnection: localAudioRef.current.srcObject tracks:', stream.getTracks());

      const iceServers = [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' },
        { urls: 'stun:stun2.l.google.com:19302' },
        {
          urls: 'turn:13.49.238.121:3478?transport=tcp',
          username: isInitiator ? 'username1' : 'username2',
          credential: isInitiator ? 'key1' : 'key2',
        },
      ];

      const peerConnection = new RTCPeerConnection({ iceServers, iceTransportPolicy: 'all' });
      peerConnection.isPolite = Number(currentUserId) > Number(userId); // Polite если ID больше
      peerConnections.current.set(userId, peerConnection);

      peerConnection.ontrack = (event) => {
        const stream = event.streams[0];
        console.log(`ontrack event for user ${userId}:`, event);
        if (stream) {
          console.log(`Remote stream tracks for user ${userId}:`, stream.getTracks());
          // [ДОБАВЛЕНО]: Обработка видеотреков
          const videoTracks = stream.getVideoTracks();
          if (videoTracks.length > 0) {
            remoteStreams.current.set(String(userId), stream);
            setCameraOnUsers((prev) => (prev.includes(String(userId)) ? prev : [...prev, String(userId)]));
            setIsVideoMode(true);
            const videoElement = videoRefs.current.get(userId);
            if (videoElement) {
              videoElement.srcObject = stream;
              videoElement.play().then(() => {
                console.log(`Video playing for user ${userId}`);
              }).catch((error) => {
                console.error(`Error playing video for ${userId}:`, error);
                console.warn('Картинка не пошла сразу у', userId);
              });
            } else {
              console.warn(`No video element found for user ${userId}`);
            }
          }
          // [ИЗМЕНЕНО]: Обработка аудиотреков
          const audioTracks = stream.getAudioTracks();
          if (audioTracks.length > 0) {
            const audioElement = document.createElement('audio');
            audioElement.id = `remote-audio-${userId}`;
            audioElement.srcObject = new MediaStream([audioTracks[0]]);
            audioElement.autoplay = true;
            audioElement.onloadedmetadata = () => {
              audioElement.play().then(() => {
                console.log(`Audio playing for user ${userId}`);
              }).catch((error) => {
                console.error(`Error playing audio for ${userId}:`, error);
                console.warn('Звук не пошёл сразу у', userId);
              });
            };
            remoteAudioRefs.current.set(userId, audioElement);
            document.body.appendChild(audioElement);
            console.log(`Remote audio track added for user ${userId}`);
          }
        } else {
          console.error(`No stream received for user ${userId}`);
          console.warn('Звукового потока пока нет от', userId);
        }
      };

      let disconnectTimeout = null;

      peerConnection.oniceconnectionstatechange = () => {
        console.log(`ICE for ${userId}: iceConnectionState: ${peerConnection.iceConnectionState}, connectionState: ${peerConnection.connectionState}, signalingState: ${peerConnection.signalingState}`);
        if (peerConnection.iceConnectionState === 'connected') {
          console.log(`Connection established with user ${userId}, clearing readyTimeout`);
          // Соединение есть — значит человек уже в разговоре, и гудок вызова
          // звучать больше не должен. Прежде он гас только при старте
          // звонка, и если сигнал «готов» терялся, играл до конца разговора
          ringtoneRef.current.pause();
          ringtoneRef.current.currentTime = 0;
          setIsWaiting(false);
          if (readyTimeoutRef.current) {
            clearTimeout(readyTimeoutRef.current);
            readyTimeoutRef.current = null;
          }
          if (disconnectTimeout) {
            clearTimeout(disconnectTimeout);
            disconnectTimeout = null;
            console.log(`Cleared disconnect timeout for user ${userId}`);
          }
          setReadyUsers((prev) => {
            if (prev.has(userId)) return prev;
            const newSet = new Set([...prev, userId]);
            console.log(`Added user ${userId} to readyUsers:`, Array.from(newSet));
            return newSet;
          });
        } else if (peerConnection.iceConnectionState === 'failed') {
          console.error(`WebRTC connection failed for user ${userId}`);
          console.warn('Связь не сложилась с', userId);
          endCall();
        } else if (peerConnection.iceConnectionState === 'disconnected') {
          console.warn(`WebRTC connection disconnected for user ${userId}`);
          console.warn('Связь с', userId, 'прервалась — пробуем снова');
          disconnectTimeout = setTimeout(() => {
            if (peerConnection.iceConnectionState === 'disconnected') {
              console.log(`User ${userId} still disconnected after 10 seconds, removing from call`);
              //setCallParticipants((prev) => prev.filter((u) => u.id !== Number(userId)));
              //peerConnections.current.delete(userId);
              if (callParticipants.length < 2) {
                console.log('Ending call: initiator or less than 2 participants remain');
                endCall();
              }
            } else {
              console.log(`User ${userId} reconnected or state changed, skipping removal: iceConnectionState=${peerConnection.iceConnectionState}`);
            }
          }, 10000); // Уменьшено до 10 секунд
        }
      };

      peerConnection.onicecandidate = (event) => {
        if (event.candidate) {
          const signalKey = `${currentUserId}-${userId}-candidate-${event.candidate.candidate}`;
          if (!sentSignals.current.has(signalKey)) {
            sentSignals.current.add(signalKey);
            sendSignal(
              {
                from: currentUserId,
                to: userId,
                signal: {
                  type: 'candidate',
                  iceCandidate: {
                    candidate: event.candidate.candidate,
                    sdpMid: event.candidate.sdpMid,
                    sdpMLineIndex: event.candidate.sdpMLineIndex.toString(),
                  },
                },
              },
              roomId.current
            );
            console.log(`Sent ICE candidate for user ${userId}:`, event.candidate);
          }
        }
      };

      stream.getTracks().forEach((track) => peerConnection.addTrack(track, stream));
      // [ДОБАВЛЕНО]: Добавляем видеотреки, если камера включена
      if (localVideoStreamRef.current && localVideoStreamRef.current.getVideoTracks().length > 0) {
        localVideoStreamRef.current.getVideoTracks().forEach((track) => {
          peerConnection.addTrack(track, localVideoStreamRef.current);
          console.log(`Added video track for user ${userId}`);
        });
      }
      console.log(`PeerConnection initialized for user ${userId}, isPolite: ${peerConnection.isPolite}`);
      return peerConnection;
    } catch (error) {
      console.error(`Error initializing PeerConnection for user ${userId}:`, error);
      toast.error('Could not reach the microphone', { toastId: 'mic' });
      endCall();
      return null;
    }
  };

  const startCall = async () => {
    if (isCallStarted) {
      console.log('Call already started, skipping startCall');
      return;
    }
    if (!localAudioRef.current || !localAudioRef.current.srcObject) {
      console.warn('Cannot start call: localAudioRef or srcObject not ready');
      return;
    }
    setIsCallStarted(true);
    setIsWaiting(false);
    ringtoneRef.current.pause();
    ringtoneRef.current.currentTime = 0;
    console.log('Starting group call for participants:', callParticipants.map((u) => u.id));

    try {
      for (const user of callParticipants) {
        if (user.id !== Number(currentUserId)) {
          if (!peerConnections.current.has(String(user.id))) {
            console.log(`Initializing peerConnection for user ${user.id}`);
            await initializePeerConnection(String(user.id), localAudioRef.current.srcObject);
          }
          const peerConnection = peerConnections.current.get(String(user.id));
          if (peerConnection) {
            if (
                peerConnection.iceConnectionState !== 'connected' &&
                peerConnection.iceConnectionState !== 'completed'
            ) {
                // Проверяем, установлено ли соединение
                if (
                    peerConnection.iceConnectionState === 'connected' &&
                    peerConnection.signalingState === 'stable'
                ) {
                    console.log(`Connection with user ${user.id} is already established, skipping offer creation`);
                    continue;
                }
                // Убрана проверка isPolite, отправляем offer всем
                if (peerConnection.localDescription && !peerConnection.remoteDescription) {
                    console.log(`Rolling back localDescription for user ${user.id}, signalingState: ${peerConnection.signalingState}`);
                    if (peerConnection.signalingState !== 'stable') {
                        await peerConnection.setLocalDescription({ type: 'rollback' }).catch((error) => {
                            console.error(`Error rolling back localDescription for ${user.id}:`, error);
                        });
                    } else {
                        console.log(`Skipping rollback for user ${user.id}: signalingState is stable`);
                    }
                }
                if (!peerConnection.remoteDescription) {
                    console.log(`Creating offer for user ${user.id}`);
                    const currentMid = peerConnection.localDescription?.sdp?.match(/a=mid:(\d+)/)?.[1] || '0';
                    const offer = await peerConnection.createOffer();
                    let modifiedSdp = offer.sdp.replace(/a=setup:passive/, 'a=setup:actpass');
                    if (currentMid && offer.sdp) {
                        modifiedSdp = modifiedSdp.replace(/a=mid:\d+/, `a=mid:${currentMid}`);
                    }
                    const modifiedOffer = {
                        type: 'offer',
                        sdp: modifiedSdp
                    };
                    await peerConnection.setLocalDescription(modifiedOffer);
                    const signalKey = `${currentUserId}-${user.id}-offer-${отпечатокСодержания(modifiedOffer.sdp)}`;
                    if (!sentSignals.current.has(signalKey)) {
                        sentSignals.current.add(signalKey);
                        sendSignal(
                            {
                                from: currentUserId,
                                to: String(user.id),
                                // Своё описание соединения подписываем — собеседник
                                // проверит подпись и увидит подмену ключа
                                signal: { ...modifiedOffer, ...(await signFor(user.id, modifiedOffer.sdp)) },
                            },
                            roomId.current
                        );
                        console.log(`Sent offer to user ${user.id}`);
                        answerTimeouts.current.set(
                            user.id,
                            setTimeout(() => {
                                if (peerConnection && !peerConnection.remoteDescription) {
                                    console.warn(`No answer received from user ${user.id} within 90 seconds`);
                                }
                            }, 90000)
                        );
                    }
                } else {
                    console.log(`Skipping offer for user ${user.id}: remoteDescription exists`);
                }
            } else {
                console.log(`Skipping offer for user ${user.id}: already connected, iceConnectionState=${peerConnection.iceConnectionState}`);
            }
          } else {
            console.warn(`No peerConnection for user ${user.id}`);
          }
        }
      }
    } catch (error) {
      /*
       * Не беда: разговор продолжается с остальными, и ниже так и
       * написано. Тревожить человека словами «не удалось начать
       * разговор» посреди идущего разговора незачем — в журнал.
       */
      console.warn('Не со всеми получилось соединиться сразу:', error);
      // Продолжаем звонок с оставшимися участниками
    }
  };

  const handleQueuedSignals = () => {
    if (localAudioRef.current && localAudioRef.current.srcObject && signalQueue.current.size > 0) {
      console.log('Processing queued signals:', signalQueue.current.size);
      signalQueue.current.forEach((signals, from) => {
        while (signals.length > 0) {
          const signal = signals.shift();
          handleSignal(signal);
        }
      });
    }
  };

  const retryFailedConnections = async () => {
    console.log('Checking connection states for all participants');
    const retryCounts = new Map();

    try {
      for (const user of callParticipants) {
        if (user.id !== Number(currentUserId)) {
          let peerConnection = peerConnections.current.get(String(user.id));
          if (!peerConnection) {
            // Создаём PeerConnection для отсутствующих пар
            console.log(`No peerConnection for user ${user.id}, initializing new one`);
            const stream = localAudioRef.current.srcObject;
            await initializePeerConnection(String(user.id), stream);
            peerConnection = peerConnections.current.get(String(user.id));
          }
          if (peerConnection) {
            if (
              peerConnection.iceConnectionState !== 'connected' &&
              peerConnection.iceConnectionState !== 'completed' ||
              peerConnection.connectionState === 'failed' ||
              peerConnection.connectionState === 'connecting' ||
              peerConnection.connectionState === 'new'
            ) {
              const retries = retryCounts.get(user.id) || 0;
              if (retries >= 3) {
                console.error(`Max retries reached for user ${user.id}`);
                console.warn('Связь не сложилась с', user.id);
                peerConnections.current.delete(String(user.id));
                setCallParticipants((prev) => prev.filter((u) => u.id !== Number(user.id)));
                if (callParticipants.length < 2) {
                  console.log('Ending call: less than 2 participants remain after retry');
                  endCall();
                }
                continue;
              }
              retryCounts.set(user.id, retries + 1);
              console.log(`Retrying connection for user ${user.id}, attempt ${retries + 1}, iceConnectionState: ${peerConnection.iceConnectionState}, connectionState: ${peerConnection.connectionState}`);

              // Закрываем старое соединение
              peerConnection.close();
              peerConnections.current.delete(String(user.id));
              pendingCandidates.current.delete(String(user.id));

              // Инициализируем новое соединение
              const stream = localAudioRef.current.srcObject;
              await initializePeerConnection(String(user.id), stream);
              peerConnection = peerConnections.current.get(String(user.id));

              if (peerConnection) {
                // Проверяем, установлено ли соединение
                if (
                    peerConnection.iceConnectionState === 'connected' &&
                    peerConnection.signalingState === 'stable'
                ) {
                    console.log(`Connection with user ${user.id} is already established, skipping offer creation`);
                    continue;
                }
                // Отправляем offer независимо от isPolite
                console.log(`Creating retry offer for user ${user.id}, attempt ${retries + 1}`);
                const offer = await peerConnection.createOffer();
                const modifiedOffer = {
                    type: 'offer',
                    sdp: offer.sdp.replace(/a=setup:passive/, 'a=setup:actpass')
                };
                await peerConnection.setLocalDescription(modifiedOffer);
                const signalKey = `${currentUserId}-${user.id}-offer-${отпечатокСодержания(modifiedOffer.sdp)}`;
                if (!sentSignals.current.has(signalKey)) {
                    sentSignals.current.add(signalKey);
                    try {
                        sendSignal(
                            {
                                from: currentUserId,
                                to: String(user.id),
                                // Своё описание соединения подписываем — собеседник
                                // проверит подпись и увидит подмену ключа
                                signal: { ...modifiedOffer, ...(await signFor(user.id, modifiedOffer.sdp)) },
                            },
                            roomId.current
                        );
                        console.log(`Sent retry offer to user ${user.id}, attempt ${retries + 1}`);
                        answerTimeouts.current.set(
                            user.id,
                            setTimeout(() => {
                                if (peerConnection && !peerConnection.remoteDescription) {
                                    console.warn(`No answer received from user ${user.id} within 90 seconds on retry ${retries + 1}`);
                                }
                            }, 90000)
                        );
                    } catch (error) {
                        console.error(`Failed to send retry offer to user ${user.id}:`, error);
                        console.warn('Предложение не ушло к', user.id);
                    }
                }
              } else {
                console.error(`Failed to initialize PeerConnection for user ${user.id} on retry ${retries + 1}`);
                console.warn('Пересобрать связь с', user.id, 'не вышло');
              }
            } else {
              console.log(`Connection with user ${user.id} is already established, iceConnectionState: ${peerConnection.iceConnectionState}, connectionState: ${peerConnection.connectionState}`);
            }
          }
        }
      }
    } catch (error) {
      console.error('Error retrying failed connections:', error);
      console.warn('Повторная сборка связей не удалась');
    }
  };

  const handleSignal = async (signalMessage) => {
    console.log('Signal received:', { ...signalMessage, currentUserId });

    if (isCallEnded || !isMounted) {
      console.log('Ignoring signal due to call ended or component unmounted:', signalMessage);
      return;
    }

    if (signalMessage.from === currentUserId) {
      console.log('Ignoring signal from self:', signalMessage);
      return;
    }

    if (!signalMessage.from) {
      console.warn('Ignoring signal with null from:', signalMessage);
      return;
    }

    const { signal, from, to } = signalMessage;

    /*
     * Сигналы о камере приходят снова и снова — их не отсеиваем.
     *
     * Повторы ловятся по ключу «от кого — кому — какого рода — начало
     * SDP», а у «камеру включил» и «камеру выключил» своего содержания
     * нет: ключ у них всегда один и тот же. Поэтому собеседник включал
     * камеру второй раз за разговор, а у нас это считалось повтором и
     * отбрасывалось — картинка больше не появлялась до конца разговора.
     */
    if (signal.type === 'cameraOn' || signal.type === 'cameraOff') {
      console.log(`Signal about camera from ${from}: ${signal.type}`);
    } else {

    /*
     * Фильтрация повторов — по всему содержанию, а не по его началу.
     *
     * Прежде в ключ брались первые полсотни букв SDP. У предложения,
     * посланного второй раз за разговор, начало то же самое: имя
     * разговора и его номер стоят в самом начале и не меняются.
     * Поэтому собеседник включал камеру, его перепредложение считалось
     * повтором и отбрасывалось, — и картинка до него не доходила. Тот,
     * с кем связались первым, её видел, а позже вошедший — нет.
     */
    const содержание = signal.sdp || signal.iceCandidate?.candidate || '';
    let отпечаток = 5381;
    for (let i = 0; i < содержание.length; i += 1) {
      отпечаток = ((отпечаток * 33) ^ содержание.charCodeAt(i)) >>> 0;
    }
    const signalKey = `${from}-${to || 'null'}-${signal.type}-${содержание.length}-${отпечаток}`;
    if (receivedSignals.current.has(signalKey)) {
      console.log(`Ignoring duplicate signal: ${signalKey}`);
      return;
    }
    receivedSignals.current.add(signalKey);
    }

    // Очередь сигналов, если localAudioRef или srcObject не готовы
    if (!localAudioRef.current || !localAudioRef.current.srcObject) {
      if (!['groupCallInvite', 'acceptCall', 'ready'].includes(signal.type)) {
        console.warn(`localAudioRef or srcObject is null, queuing signal from ${from}`);
        if (!signalQueue.current.has(from)) {
          signalQueue.current.set(from, []);
        }
        signalQueue.current.get(from).push(signalMessage);
        return;
      }
    }

    if (!signalQueue.current.has(from)) {
      signalQueue.current.set(from, []);
      isProcessingSignal.current.set(from, false);
    }
    signalQueue.current.get(from).push(signalMessage);

    if (isProcessingSignal.current.get(from)) {
      console.log(`Queuing signal from ${from}, already processing`);
      return;
    }

    isProcessingSignal.current.set(from, true);

    while (signalQueue.current.get(from).length > 0) {
      const currentSignal = signalQueue.current.get(from).shift();
      const { signal: currentSignalData, from: currentFrom, to } = currentSignal;

      if (to && to !== String(currentUserId) && !['acceptCall', 'ready', 'groupCallInvite'].includes(currentSignalData.type)) {
        console.log('Ignoring signal not addressed to current user:', { from: currentFrom, to, type: currentSignalData.type, currentUserId });
        continue;
      }

      if (!peerConnections.current.has(currentFrom) && currentFrom !== String(currentUserId)) {
        console.log('Initializing peerConnection for user:', currentFrom);
        const peerConnection = await initializePeerConnection(currentFrom, localAudioRef.current.srcObject);
        if (!peerConnection) {
          console.warn(`Failed to initialize PeerConnection for user ${currentFrom}`);
          console.warn('Соединение не завелось');
          continue;
        }
      }

      const peerConnection = peerConnections.current.get(currentFrom);
      if (!peerConnection && !['groupCallInvite', 'acceptCall', 'ready'].includes(currentSignalData.type)) {
        console.error(`No peerConnection for user ${currentFrom} after initialization attempt`);
        continue;
      }

      try {
        switch (currentSignalData.type) {
          case 'groupCallInvite':
            if (!isInitiator) {
              if (hasProcessedInvite.current.has(currentFrom)) {
                console.log(`Ignoring duplicate groupCallInvite from ${currentFrom}`);
                continue;
              }
              hasProcessedInvite.current.add(currentFrom);
              console.log('Processing groupCallInvite:', currentSignal);
              if (!currentSignalData.roomId) {
                console.error('groupCallInvite received with null roomId');
                console.warn('Приглашение пришло неполным');
                navigate('/');
                return;
              }
              roomId.current = currentSignalData.roomId;
              console.log('Updated roomId to:', roomId.current);
              console.log('Приглашение в общий разговор от', currentSignalData.initiatorName);
              callParticipants
                .filter((p) => p.id !== Number(currentUserId))
                .forEach((user) => {
                  const signalKey = `${currentUserId}-${user.id}-acceptCall`;
                  if (!sentSignals.current.has(signalKey)) {
                    sentSignals.current.add(signalKey);
                    sendSignal(
                      { from: currentUserId, to: String(user.id), signal: { type: 'acceptCall' } },
                      roomId.current
                    );
                    console.log(`Sent acceptCall to user ${user.id}`);
                  }
                });
              const signalKey = `${currentUserId}-${currentFrom}-ready`;
              if (!sentSignals.current.has(signalKey)) {
                sentSignals.current.add(signalKey);
                sendSignal(
                  { from: currentUserId, to: currentFrom, signal: { type: 'ready' } },
                  roomId.current
                );
                console.log(`Sent ready to user ${currentFrom}`);
              }
            }
            break;

          case 'acceptCall':
            if (isInitiator) {
              const userName = callParticipants.find((u) => u.id === Number(currentFrom))?.username || `User ${currentFrom}`;
              console.log(userName, 'принял приглашение');
            }
            break;

          case 'ready':
            setReadyUsers((prev) => {
              if (prev.has(currentFrom)) {
                console.log(`Ignoring duplicate ready signal from ${currentFrom}`);
                return prev;
              }
              const newSet = new Set([...prev, currentFrom]);
              console.log('Ready users updated:', Array.from(newSet));
              const userName = callParticipants.find((u) => u.id === Number(currentFrom))?.username || `User ${currentFrom}`;
              console.log(userName, 'готов');
              console.log(`User ${userName} is ready`);
              return newSet;
            });
            if (!isInitiator && !isCallStarted) {
              startCall();
              console.log('Call started for non-initiator after receiving ready signal');
            }
            if (isInitiator && !isCallStarted) {
              startCall();
              console.log('Call started for initiator after receiving ready signal');
            }
            break;

          case 'offer':
            console.log('Processing offer from:', currentFrom, 'signal:', currentSignalData);
            // Подпись собеседника: не сошлась — отключаем его одного,
            // остальные в звонке продолжают говорить
            if (await verifyFrom(currentFrom, currentSignalData.sdp,
                currentSignalData.signature, currentSignalData.deviceId)) {
              break;
            }
            offerReceived.current.set(currentFrom, true);
            try {
              const currentState = peerConnection.signalingState;
              console.log(`Current signaling state for user ${currentFrom}: ${currentState}`);
              if (peerConnection.remoteDescription && peerConnection.remoteDescription.sdp === currentSignalData.sdp) {
                console.log(`Ignoring duplicate offer from ${currentFrom}`);
                continue;
              }
              if (currentState === 'have-local-offer' && !peerConnection.isPolite) {
                console.log(`Rude peer ignoring offer from ${currentFrom}`);
                continue; // Rude peer игнорирует входящий оффер
              }
              if (currentState !== 'stable') {
                console.log(`Rolling back signaling state for user ${currentFrom} from ${currentState}`);
                await peerConnection.setLocalDescription({ type: 'rollback' });
                console.log(`Rollback successful for user ${currentFrom}`);
              }
              await peerConnection.setRemoteDescription(new RTCSessionDescription(currentSignalData));
              console.log(`Set remoteDescription for user ${currentFrom}`);
              const answer = await peerConnection.createAnswer();
              await peerConnection.setLocalDescription(answer); // Без модификации SDP
              const signalKey = `${currentUserId}-${currentFrom}-answer-${отпечатокСодержания(answer.sdp)}`;
              if (!sentSignals.current.has(signalKey)) {
                sentSignals.current.add(signalKey);
                sendSignal(
                  {
                    from: currentUserId,
                    to: currentFrom,
                    signal: { type: answer.type, sdp: answer.sdp, ...(await signFor(currentFrom, answer.sdp)) },
                  },
                  roomId.current
                );
                console.log(`Sent answer to user ${currentFrom}`);
              }
              if (pendingCandidates.current.has(currentFrom)) {
                for (const candidate of pendingCandidates.current.get(currentFrom)) {
                  await peerConnection.addIceCandidate(candidate);
                  console.log(`Processed pending ICE candidate for ${currentFrom}`);
                }
                pendingCandidates.current.delete(currentFrom);
              }
              if (offerTimeoutRef.current) {
                clearTimeout(offerTimeoutRef.current);
                offerTimeoutRef.current = null;
                console.log('Cleared offerTimeout after receiving offer');
              }
              if (!isCallStarted && peerConnection.signalingState === 'stable') {
                startCall();
                console.log('Call started after receiving offer');
              }
            } catch (error) {
              console.error(`Error processing offer from ${currentFrom}:`, error);
              //toast.error(`Failed to process offer from ${callParticipants.find((u) => u.id === Number(currentFrom))?.username || `User ${currentFrom}`}`);
            }
            break;

          case 'answer':
            console.log('Processing answer from:', currentFrom, 'signal:', currentSignalData);
            if (await verifyFrom(currentFrom, currentSignalData.sdp,
                currentSignalData.signature, currentSignalData.deviceId)) {
              break;
            }
            try {
              if (peerConnection.signalingState === 'have-local-offer') {
                await peerConnection.setRemoteDescription(new RTCSessionDescription(currentSignalData));
                console.log(`Set remoteDescription (answer) for user ${currentFrom}`);
                // Очистка таймаута для answer
                if (answerTimeouts.current.has(Number(currentFrom))) {
                  clearTimeout(answerTimeouts.current.get(Number(currentFrom)));
                  answerTimeouts.current.delete(Number(currentFrom));
                }
              } else {
                console.warn(`Cannot process answer from ${currentFrom} in signaling state: ${peerConnection.signalingState}`);
              }
            } catch (error) {
              console.error(`Error processing answer from ${currentFrom}:`, error);
              //toast.error(`Failed to process answer from ${callParticipants.find((u) => u.id === Number(currentFrom))?.username || `User ${currentFrom}`}`);
            }
            break;

          case 'candidate':
            const { iceCandidate } = currentSignalData;
            if (peerConnection && iceCandidate.candidate) {
              const candidate = new RTCIceCandidate({
                candidate: iceCandidate.candidate,
                sdpMid: iceCandidate.sdpMid,
                sdpMLineIndex: Number(iceCandidate.sdpMLineIndex),
              });
              if (peerConnection.remoteDescription) {
                await peerConnection.addIceCandidate(candidate);
                console.log(`Processed ICE candidate from user ${currentFrom}`);
              } else {
                console.warn(`Queuing ICE candidate for ${currentFrom}: remoteDescription not set`);
                pendingCandidates.current.set(currentFrom, [
                  ...(pendingCandidates.current.get(currentFrom) || []),
                  candidate,
                ]);
              }
            } else {
              console.error(`Cannot add ICE candidate: peerConnection=${!!peerConnection}, candidate=${!!iceCandidate.candidate}`);
            }
            break;

          case 'rejectCall':
            if (isInitiator) {
              const userName = callParticipants.find((u) => u.id === Number(currentFrom))?.username || `User ${currentFrom}`;
              toast.warn(`${userName} declined`, { toastId: `declined-${userName}` });
              setCallParticipants((prev) => prev.filter((u) => u.id !== Number(currentFrom)));
              peerConnections.current.delete(currentFrom);
              if (callParticipants.length <= 1) {
                console.log('No participants left after reject, ending call');
                endCall();
              }
            }
            break;

          case 'endCall':
            /*
             * Пропускаем только того, с кем так и не связались.
             *
             * Прежде мерой знакомства было полученное от него предложение
             * соединиться — но заводящий звонок предложения не получает, он
             * их сам рассылает. Поэтому его собеседник клал трубку, а у
             * него разговор продолжался с пустой комнатой, пока он не
             * догадается нажать отбой. Считаем знакомым и того, с кем уже
             * есть соединение.
             */
            if (!offerReceived.current.has(currentFrom) && !peerConnections.current.has(currentFrom)) {
              console.log(`Ignoring endCall from ${currentFrom}: no connection with them`);
              continue;
            }
            if (to && to !== String(currentUserId)) {
              console.log('Ignoring endCall signal not addressed to current user:', { from: currentFrom, to, currentUserId });
              continue;
            }
            if (!ушедшие.current.has(String(currentFrom))) {
              ушедшие.current.add(String(currentFrom));
              const userName = callParticipants.find((u) => u.id === Number(currentFrom))?.username || `User ${currentFrom}`;
              toast.info(`${userName} left the call`, { toastId: `left-${userName}` });
              setCallParticipants((prev) => {
                const updatedParticipants = prev.filter((u) => u.id !== Number(currentFrom));
                console.log('Updated participants after endCall:', updatedParticipants);
                return updatedParticipants;
              });
              if (peerConnection) {
                peerConnection.close();
                peerConnections.current.delete(currentFrom);
              }
              /*
               * Ушёл звонивший — разговору конец; ушёл последний
               * собеседник — тоже.
               *
               * Прежде здесь сравнивали число со строкой: номер пришедшего
               * сигнала приводили к числу, а у заводившего звонок он лежит
               * строкой, и 1 никогда не равнялось «1». Поэтому звонящий
               * клал трубку, а у принявшего экран разговора оставался
               * висеть с живым списком участников, пока он не перезагрузит
               * страницу. И счёт оставшихся брали до того, как ушедшего
               * вычеркнули, — себя тоже считали собеседником.
               */
              /*
               * Считаем по памяти об ушедших, а не по списку.
               *
               * Список здесь — тот, каким он был, когда заводили этот
               * обработчик: ушедшего минуту назад он всё ещё числит
               * среди живых. Поэтому последний оставшийся сидел в пустой
               * комнате с бегущим временем, пока сам не нажмёт отбой.
               */
              const remaining = callParticipants.filter(
                (u) =>
                  String(u.id) !== String(currentUserId) &&
                  !ушедшие.current.has(String(u.id))
              );
              if (String(currentFrom) === String(initiator && initiator.id) || remaining.length === 0) {
                  console.log('Ending call: initiator left or no participants remain');
                  endCall();
              } else {
                  console.log(`Keeping call active, remaining participants: ${remaining.length}`);
              }
            }
            break;

          case 'cameraOn':
            console.log(`User ${currentFrom} turned on their camera`);
            setCameraOnUsers((prev) => (prev.includes(currentFrom) ? prev : [...prev, currentFrom]));
            setIsVideoMode(true);
            // [ДОБАВЛЕНО]: Ожидаем видеопоток через ontrack, ничего дополнительно не делаем
            break;

          case 'cameraOff':
            console.log(`User ${currentFrom} turned off their camera`);
            setCameraOnUsers((prev) => {
              const left = prev.filter((id) => id !== currentFrom);
              // Никто больше не показывает картинку и своя камера выключена —
              // возвращаемся к списку имён
              if (left.length === 0 && !isCameraOn) setIsVideoMode(false);
              return left;
            });
            const videoElement = videoRefs.current.get(currentFrom);
            if (videoElement) {
              videoElement.srcObject = null;
              console.log(`Cleared video stream for user ${currentFrom}`);
            }
            break;

          default:
            console.warn('Unknown signal type:', currentSignalData.type);
        }
      } catch (error) {
        console.error('Error processing signal:', error);
      }
    }

    isProcessingSignal.current.set(from, false);
  };

  const endCall = () => {
    console.log('endCall called'); // NEW: Лог для отладки вызовов endCall

    // NEW: Проверяем, был ли звонок уже завершён или сохранён
    if (isCallEnded || hasSavedCall.current) {
      console.log('Call already ended or saved, skipping endCall');
      return;
    }

    setIsCallEnded(true);
    setIsCallStarted(false);
    setIsWaiting(false);

    // NEW: Отправляем сигнал endCall только если звонок ещё не завершён
    if (!hasNotifiedEndCall.current) {
      callParticipants
        .filter((u) => u.id !== Number(currentUserId))
        .forEach((user) => {
          const signalKey = `${currentUserId}-${user.id}-endCall`;
          if (!sentSignals.current.has(signalKey)) {
            sentSignals.current.add(signalKey);
            sendSignal(
              {
                from: currentUserId,
                to: String(user.id),
                signal: { type: 'endCall', roomId: roomId.current },
              },
              roomId.current
            );
            console.log(`Sent endCall to user ${user.id}`);
          }
        });
      hasNotifiedEndCall.current = true;
      console.log('Общий разговор окончен');
    }

    // NEW: Отписываемся от комнаты
    if (unsubscribeRoomRef.current) {
      unsubscribeRoomRef.current();
      console.log(`Unsubscribed from room ${roomId.current} in endCall`);
      unsubscribeRoomRef.current = null;
    }

    // NEW: Закрываем все peerConnections
    peerConnections.current.forEach((pc, userId) => {
      pc.close();
      console.log(`Closed peerConnection for user ${userId}`);
    });
    peerConnections.current.clear();

    // NEW: Останавливаем локальный аудиопоток
    if (localAudioRef.current && localAudioRef.current.srcObject) {
      localAudioRef.current.srcObject.getTracks().forEach((track) => track.stop());
      localAudioRef.current.srcObject = null;
      console.log('Stopped local audio stream');
    }

    // NEW: Останавливаем удалённые аудиопотоки
    remoteAudioRefs.current.forEach((audio, userId) => {
      if (audio.srcObject) {
        audio.srcObject.getTracks().forEach((track) => track.stop());
        audio.srcObject = null;
      }
      audio.remove();
      console.log(`Removed remote audio for user ${userId}`);
    });
    remoteAudioRefs.current.clear();

    // NEW: Останавливаем локальный видеопоток
    if (localVideoStreamRef.current) {
      localVideoStreamRef.current.getTracks().forEach((track) => track.stop());
      localVideoStreamRef.current = null;
      console.log('Stopped local video stream');
    }

    // NEW: Останавливаем удалённые видеопотоки
    videoRefs.current.forEach((video, userId) => {
      if (video.srcObject) {
        video.srcObject.getTracks().forEach((track) => track.stop());
        video.srcObject = null;
      }
      video.remove();
      console.log(`Removed remote video for user ${userId}`);
    });
    videoRefs.current.clear();

    // NEW: Останавливаем рингтон
    ringtoneRef.current.pause();
    ringtoneRef.current.currentTime = 0;

    // NEW: Функция сохранения звонка
    const saveCall = async () => {
      console.log('saveCall called with readyUsers:', Array.from(readyUsers), 'isCallStarted:', isCallStarted);
      try {
        let status = readyUsers.size === 0 && !isCallStarted && isInitiator ? 'rejected' : 'completed';

        await axios.post(
          `https://unlimitedpossibilities12.org/api/calls?userId=${currentUserId}`,
          {
            caller: initiator.id,
            participants: callParticipants.map((u) => String(u.id)),
            direction: isInitiator ? 'outgoing' : 'incoming',
            status: status,
            callType: 'group',
            // Сколько длился разговор — для списка звонков
            durationSeconds: status === 'completed' ? callDuration : undefined,
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
            roomId: roomId.current, // NEW: Добавляем roomId
          }
        );
        console.log(`Group call saved to server with status: ${status}`);
        hasSavedCall.current = true;
      } catch (error) {
        console.error('Error saving group call:', error);
        console.warn('Запись о звонке не сохранилась');
      }
    };

    // NEW: Вызываем saveCall перед навигацией
    saveCall();

    if (onClose) onClose(); else navigate('/');
  };

  // [ДОБАВЛЕНО]: Функция для переключения между аудио- и видео-режимами
  /** Выключить и включить свой микрофон. */
  const toggleMute = () => {
    const stream = localVideoStreamRef.current
      || (localAudioRef.current && localAudioRef.current.srcObject);
    if (!stream) return;
    const next = !muted;
    stream.getAudioTracks().forEach((track) => { track.enabled = !next; });
    setMuted(next);
  };

  const toggleMode = () => {
    setIsVideoMode((prev) => !prev);
  };

  // Запоминает реальные пропорции входящего потока участника.
  // Разрешение может меняться по ходу звонка, поэтому вызывается и на resize.
  const updateVideoAspect = (userId, videoElement) => {
    if (!videoElement || !videoElement.videoWidth || !videoElement.videoHeight) return;
    const aspect = videoElement.videoWidth / videoElement.videoHeight;
    setVideoAspects((prev) => {
      const known = prev[userId];
      if (known && Math.abs(known - aspect) < 0.01) return prev; // без лишнего рендера
      return { ...prev, [userId]: aspect };
    });
  };

  const TILE_MODES = ['auto', 'landscape', 'portrait'];
  const TILE_MODE_LABELS = { auto: 'Auto', landscape: 'Wide', portrait: 'Tall' };

  const cycleTileMode = () => {
    const next = TILE_MODES[(TILE_MODES.indexOf(tileMode) + 1) % TILE_MODES.length];
    setTileMode(next);
    localStorage.setItem('groupCallTileMode', next);
  };

  // Пропорции конкретной плитки: в ручных режимах одинаковые для всех,
  // в auto — по потоку участника, с запасным 16/9 пока разрешение неизвестно.
  const getTileAspect = (userId) => {
    if (tileMode === 'landscape') return 16 / 9;
    if (tileMode === 'portrait') return 9 / 16;
    return videoAspects[String(userId)] || 16 / 9;
  };

  // [ДОБАВЛЕНО]: Функция для рендеринга видеоэкранов
  const renderVideoScreens = () => {
    return (
      <div className="group-call-video-grid">
        {callParticipants.map((user) => (
          <div key={user.id} className="group-call-video-container">
            <div className="group-call-participant-name">
              {user.username}
              {String(user.id) === String(currentUserId) && ' (you)'}
            </div>
            <div
              className="group-call-video-wrapper"
              style={{ aspectRatio: getTileAspect(user.id) }}
            >
              <video
                id={`remote-video-${user.id}`}
                ref={(el) => {
                  if (!el) return;
                  videoRefs.current.set(String(user.id), el);
                  // Картинка могла прийти раньше плитки — заберём её
                  const пришедший = remoteStreams.current.get(String(user.id));
                  if (пришедший && el.srcObject !== пришедший) {
                    el.srcObject = пришедший;
                    el.play().catch(() => {});
                  }
                  // Слушатели вешаем один раз на элемент: ref-колбэк
                  // вызывается на каждый ререндер.
                  if (!el.dataset.aspectBound) {
                    el.dataset.aspectBound = '1';
                    const onSize = () => updateVideoAspect(String(user.id), el);
                    el.addEventListener('loadedmetadata', onSize);
                    el.addEventListener('resize', onSize); // разрешение меняется по ходу звонка
                  }
                  updateVideoAspect(String(user.id), el);
                }}
                autoPlay
                playsInline
                className="group-call-video"
              />
              {!(user.id === Number(currentUserId) ? isCameraOn : videoRefs.current.get(String(user.id))?.srcObject) && (
                /* Камера выключена — показываем лицо или букву, а не чёрный
                   прямоугольник со словами «No Video» */
                <div className="group-call-video-placeholder">
                  {user.avatar
                    ? <img src={`https://unlimitedpossibilities12.org${user.avatar}`} alt="" />
                    : <span>{(user.username || '?').charAt(0).toUpperCase()}</span>}
                </div>
              )}
            </div>
          </div>
        ))}
      </div>
    );
  };

  // [ДОБАВЛЕНО]: Функция для включения/выключения камеры
  const toggleCamera = async () => {
    if (!localAudioRef.current || !localAudioRef.current.srcObject) {
      console.warn('Cannot toggle camera: localAudioRef or srcObject not ready');
      console.warn('Звуковой поток не готов — камеру пока не включить');
      return;
    }

    if (isCameraOn) {
      // ====================== ВЫКЛЮЧЕНИЕ КАМЕРЫ ======================
      if (localVideoStreamRef.current) {
        localVideoStreamRef.current.getVideoTracks().forEach((track) => track.stop());
        localVideoStreamRef.current = new MediaStream(localAudioRef.current.srcObject.getAudioTracks());
        const videoElement = videoRefs.current.get(String(currentUserId));
        if (videoElement) {
          videoElement.srcObject = null;
        }
        console.log('Camera turned off');
      }
      // Вынимаем дорожку из мест — сами места остаются
      for (const [кому, место] of местаПодКартинку.current) {
        try {
          await место.replaceTrack(null);
        } catch (ошибка) {
          console.warn('Не вышло вынуть дорожку у', кому, ошибка);
        }
      }
      setIsCameraOn(false);

      // Уведомляем других об отключении камеры
      callParticipants
        .filter((u) => u.id !== Number(currentUserId))
        .forEach((user) => {
          /* Это слово должно уходить при каждом выключении: прежде
             его придерживали как повтор, и со второго раза собеседник
             не узнавал, что камеру выключили. */
          sendSignal(
            {
              from: currentUserId,
              to: String(user.id),
              signal: { type: 'cameraOff' },
            },
            roomId.current
          );
          console.log(`Sent cameraOff signal to user ${user.id}`);
        });

      /*
       * Переписывать связь при выключении камеры больше не нужно:
       * место под картинку остаётся, дорожку из него вынули, и по нему
       * просто ничего не идёт. Собеседник узнаёт об этом из слова
       * «камеру выключили», посланного выше. Прежде здесь шли
       * переговоры заново — и после нескольких включений подряд
       * стороны застревали, ожидая ответа друг от друга.
       */
      // ============================================================

    } else {
      // ====================== ВКЛЮЧЕНИЕ КАМЕРЫ ======================
      try {
        const videoStream = await navigator.mediaDevices.getUserMedia({ video: true });
        // Создаём ПОЛНЫЙ поток (аудио + видео) — он пойдёт к другим участникам
        localVideoStreamRef.current = new MediaStream([
          ...localAudioRef.current.srcObject.getAudioTracks(),
          ...videoStream.getVideoTracks(),
        ]);

        // Создаём ОТДЕЛЬНЫЙ поток ТОЛЬКО с видео — для локального превью (без звука!)
        const localVideoOnlyStream = new MediaStream(videoStream.getVideoTracks());

        // Показываем себя локально — ТОЛЬКО ВИДЕО, без аудио → нет эха
        const videoElement = videoRefs.current.get(String(currentUserId));
        if (videoElement) {
          videoElement.srcObject = localVideoOnlyStream;  // ← Только видео!
          videoElement.play().catch((error) => {
            console.error('Error playing local video:', error);
            console.warn('Своя картинка не показалась');
          });
        }

        // Кладём дорожку в место у каждого собеседника; где места ещё
        // нет — заводим, и только для таких придётся договориться заново
        const дорожка = videoStream.getVideoTracks()[0];
        нужноДоговориться.current = new Set();
        for (const [userId, pc] of peerConnections.current) {
          if (pc.iceConnectionState !== 'connected' && pc.iceConnectionState !== 'completed') continue;
          const место = местаПодКартинку.current.get(userId);
          if (место) {
            try {
              await место.replaceTrack(дорожка);
              console.log(`Положили картинку в готовое место у ${userId}`);
              continue;
            } catch (ошибка) {
              console.warn('Место у', userId, 'не приняло дорожку:', ошибка);
              местаПодКартинку.current.delete(userId);
            }
          }
          местаПодКартинку.current.set(userId, pc.addTrack(дорожка, localVideoStreamRef.current));
          нужноДоговориться.current.add(userId);
          console.log(`Завели место под картинку у ${userId}`);
        }

        setIsCameraOn(true);

        // Уведомляем других о включении камеры
        callParticipants
          .filter((u) => u.id !== Number(currentUserId))
          .forEach((user) => {
            /* Это слово должно уходить при каждом включении: прежде его
               придерживали как повтор, и со второго раза собеседник не
               узнавал, что камеру включили. */
            {
              sendSignal(
                {
                  from: currentUserId,
                  to: String(user.id),
                  signal: { type: 'cameraOn' },
                },
                roomId.current
              );
              console.log(`Sent cameraOn signal to user ${user.id}`);
            }
          });

        // [НОВОЕ: Renegotiation при включении камеры]
        // Обязательно: после addTrack отправляем renegotiated offer с видео m-line
        peerConnections.current.forEach((pc, userId) => {
          // С остальными договариваться не о чем: место под картинку у них
          // уже было, мы лишь положили в него дорожку
          if (!нужноДоговориться.current.has(userId)) return;
          if (
            (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') &&
            pc.signalingState === 'stable'
          ) {
            pc.createOffer()
              .then((offer) => pc.setLocalDescription(offer))
              .then(async () => {
                const signalKey = `${currentUserId}-${userId}-renegotiated-offer-camera-on-${отпечатокСодержания(pc.localDescription.sdp)}`;
                if (!sentSignals.current.has(signalKey)) {
                  sentSignals.current.add(signalKey);
                  sendSignal(
                    {
                      from: currentUserId,
                      to: userId,
                      // Перепредложение (камеру включили или выключили) тоже
                    // подписываем: без подписи собеседник увидит «encrypted»
                    signal: {
                      type: pc.localDescription.type,
                      sdp: pc.localDescription.sdp,
                      ...(await signFor(userId, pc.localDescription.sdp)),
                    },
                    },
                    roomId.current
                  );
                  console.log(`Sent RENEGOTIATED OFFER (camera on) to user ${userId}`);
                }
              })
              .catch((err) => {
                console.error(`Renegotiation failed (camera on) for user ${userId}:`, err);
              });
          } else {
            console.warn(`Skipping renegotiation for ${userId}: ice=${pc.iceConnectionState}, signaling=${pc.signalingState}`);
          }
        });
        // ============================================================

      } catch (error) {
        console.error('Error accessing camera:', error);
        toast.error('Could not turn the camera on', { toastId: 'camera' });
      }
    }
  };

  // Пока разговор свёрнут, приложение сдвинуто на высоту полоски
  // (эффект объявлен выше любых возвратов, иначе порядок хуков плывёт)

  /*
   * Свёрнутый разговор — полоска сверху, а окно прячется, но остаётся.
   *
   * Сначала я рисовал вместо окна одну полоску — и звуковые узлы
   * разговора исчезали из страницы вместе с ним. Сторож, который ждёт
   * появления своего звукового элемента и через пять секунд решает, что
   * звонок не задался, находил пустоту и клал трубку: свернул разговор —
   * он и оборвался. Поэтому окно остаётся на месте, просто невидимое.
   */
  const others = callParticipants.filter((u) => String(u.id) !== String(currentUserId));

  return (
    <>
    {minimized && (
      <div className="voice-call-strip" onClick={onMinimize} role="presentation">
        <span className="voice-call-strip-dot" />
        <strong>{others.map((u) => u.username).join(', ') || 'Group call'}</strong>
        <span className="voice-call-strip-time">{callDuration}</span>
        <button
          type="button"
          className="voice-call-strip-end"
          onClick={(event) => { event.stopPropagation(); endCall(); }}
        >
          End call
        </button>
      </div>
    )}
    <div className={`group-call-container${isVideoMode ? ' video-mode' : ''}${minimized ? ' is-minimized' : ''}`}>
      {/* Шапка окна: разговор идёт поверх переписки и сворачивается в
          полоску — прежде он занимал страницу целиком, и пока говоришь,
          приложение было недоступно */}
      <div className="voice-call-bar">
        <span className="voice-call-bar-side" />
        <span className="voice-call-bar-title">Group call with {initiator.username}</span>
        <span className="voice-call-bar-buttons">
          <button type="button" className="voice-call-bar-button" onClick={onMinimize} title="Minimise">
            &#8211;
          </button>
          <button type="button" className="voice-call-bar-button is-close" onClick={endCall} title="End the call">
            &#10005;
          </button>
        </span>
      </div>
      {/* Имя разговора стоит в шапке окна, а время — одной строкой.
          Прежде заголовок был написан дважды, а под ним шли три строки:
          сколько идёт, когда начался и который сейчас час */}
      <div className="group-call-info">
        <p className="call-timer" title={`Started at ${startTime}`}>{callDuration}</p>
      </div>
      <div className="group-call-participants">
        {/* Заголовок «Participants» убран: в окне на четверых и так
            видно, кто в разговоре, а кнопка камеры переехала вниз, к
            остальному управлению, как в телеграме */}
        <div className="group-call-participants-header">
          {isVideoMode && (
            <button
              onClick={cycleTileMode}
              className="group-call-tile-mode-toggle"
              title="Screen shape: Auto fits each participant, Wide and Tall force the same shape for everyone"
            >
              {TILE_MODE_LABELS[tileMode]}
            </button>
          )}
        </div>
        {isVideoMode ? (
          renderVideoScreens()
        ) : (
          <>
            <ul className="group-call-participant-list">
              {callParticipants.map((user) => (
                <li key={user.id} className="group-call-participant-item">
                  {user.avatar ? (
                    <img
                      src={`https://unlimitedpossibilities12.org${user.avatar}`}
                      alt={`${user.username} avatar`}
                      className="group-call-participant-avatar"
                    />
                  ) : (
                    <div className="group-call-participant-avatar">{user.username.charAt(0).toUpperCase()}</div>
                  )}
                  <span className="group-call-participant-name">
                    {/* Себя помечаем, как это сделано на телефоне: иначе
                        в списке из трёх имён не сразу понятно, где ты */}
                    {user.username}
                    {String(user.id) === String(currentUserId) && ' (you)'}
                  </span>
                  {/* Защита — своя у каждого: групповой звонок сложен из
                      парных соединений, и подпись проверяется с каждым
                      отдельно. Нажатие открывает код для сверки голосом. */}
                  {Number(user.id) !== Number(currentUserId) && (
                    <button
                      type="button"
                      className="group-call-participant-security"
                      style={{ color: securityColor(securityByUser[String(user.id)]) }}
                      title="Show the code to read aloud"
                      onClick={async () => {
                        const guard = guards.current.get(String(user.id));
                        if (!guard) return;
                        setCodeFor({
                          id: user.id,
                          name: user.username,
                          code: await guard.fingerprint(),
                          // Цифры — для тех, кому значки неудобно читать вслух;
                          // в обычном звонке показываются обе записи, здесь тоже
                          digits: await guard.fingerprintDigits(),
                        });
                      }}
                    >
                      🔒 {securityWord(securityByUser[String(user.id)])}
                    </button>
                  )}
                </li>
              ))}
            </ul>
            {/* Голос — живой полоской: на экране разговора важно видеть,
                что человек говорит, а не измерять это по осям от нуля до
                ста с отметкой времени под каждой секундой */}
            <div className="voice-call-levels" aria-hidden="true">
              {(chartData.datasets[0]?.data || []).slice(-28).map((value, index) => (
                <span key={index} style={{ height: `${Math.max(3, Math.min(100, value))}%` }} />
              ))}
            </div>
          </>
        )}
      </div>
      {codeFor && (
        <div
          className="group-call-code-backdrop"
          onClick={(event) => { if (event.target === event.currentTarget) setCodeFor(null); }}
          role="presentation"
        >
          <div className="group-call-code" onClick={(event) => event.stopPropagation()}>
            <h3>Code with {codeFor.name}</h3>
            <p className="group-call-code-digits">{codeFor.code || '—'}</p>
            {codeFor.digits && <p className="group-call-code-numbers">{codeFor.digits}</p>}
            <p className="group-call-code-hint">
              Read it aloud. If it is the same on both sides, nobody swapped the keys.
            </p>
            <div className="group-call-code-buttons">
              <button type="button" onClick={() => setCodeFor(null)}>Close</button>
              <button
                type="button"
                className="primary"
                onClick={() => {
                  const guard = guards.current.get(String(codeFor.id));
                  if (guard) {
                    guard.confirmByCode();
                    setSecurityByUser((prev) => ({ ...prev, [String(codeFor.id)]: guard.state() }));
                  }
                  setCodeFor(null);
                }}
              >
                It matches
              </button>
            </div>
          </div>
        </div>
      )}
      {peopleOpen && (
        <div className="voice-call-people-backdrop" onClick={() => setPeopleOpen(false)} role="presentation">
          <div className="voice-call-people" onClick={(event) => event.stopPropagation()}>
            <h3>Add to the call</h3>
            <p className="voice-call-people-note">They will be called and join this conversation.</p>
            <ul>
              {people.map((человек) => (
                <li key={человек.id}>
                  <button type="button" onClick={() => позвать(человек)}>
                    {человек.avatar
                      ? <img src={`https://unlimitedpossibilities12.org${человек.avatar}`} alt="" />
                      : <span className="voice-call-people-letter">{(человек.displayName || человек.username || '?').charAt(0).toUpperCase()}</span>}
                    {человек.displayName || человек.username}
                  </button>
                </li>
              ))}
              {!people.length && <li className="voice-call-people-empty">Nobody to add yet</li>}
            </ul>
            <button type="button" className="voice-call-people-close" onClick={() => setPeopleOpen(false)}>
              Cancel
            </button>
          </div>
        </div>
      )}

      <audio id="local-audio" ref={localAudioRef} autoPlay muted />
      <div className="voice-call-controls">
        <button
          type="button"
          className={`voice-call-round${isCameraOn ? ' is-on' : ''}`}
          onClick={() => {
            // Кнопка включает камеру, а вид переключается сам: прежде она
            // лишь меняла список имён на плитки, и человек, нажав её,
            // оставался без картинки, не понимая, что камера не включена
            if (!isCameraOn) setIsVideoMode(true);
            else if (cameraOnUsers.length === 0) setIsVideoMode(false);
            toggleCamera();
          }}
          title={isCameraOn ? 'Turn the camera off' : 'Turn the camera on'}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 7h9a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2Z" />
            <path d="M15 11.5 21 8v8l-6-3.5Z" />
          </svg>
          <small>{isCameraOn ? 'Video off' : 'Video'}</small>
        </button>
        <button
          type="button"
          className={`voice-call-round${muted ? ' is-on' : ''}`}
          onClick={toggleMute}
          title={muted ? 'Turn the microphone back on' : 'Turn the microphone off'}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 14a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v5a3 3 0 0 0 3 3Z" />
            <path d="M18 11a6 6 0 0 1-12 0M12 17v4M9 21h6" fill="none" strokeWidth="2" strokeLinecap="round" />
            {muted && <path d="M4 3 L20 21" fill="none" strokeWidth="2" strokeLinecap="round" />}
          </svg>
          <small>{muted ? 'Unmute' : 'Mute'}</small>
        </button>
        <button
          type="button"
          className="voice-call-round"
          onClick={() => setPeopleOpen(true)}
          title="Add someone to the call"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M9 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm0 2c-3.3 0-6 1.8-6 4v2h12v-2c0-2.2-2.7-4-6-4Z" />
            <path d="M18 8v8M14 12h8" fill="none" strokeWidth="2" strokeLinecap="round" />
          </svg>
          <small>Add</small>
        </button>
        <button type="button" className="voice-call-round is-end" onClick={endCall} title="End the call">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 9c-2.6 0-5.1.5-7.3 1.5-.8.4-1.3 1.2-1.2 2.1l.2 1.8c.1.9.9 1.5 1.8 1.4l2.6-.4c.8-.1 1.4-.8 1.5-1.6l.2-1.4c1.5-.4 3-.4 4.5 0l.2 1.4c.1.8.7 1.5 1.5 1.6l2.6.4c.9.1 1.7-.5 1.8-1.4l.2-1.8c.1-.9-.4-1.7-1.2-2.1C17.1 9.5 14.6 9 12 9Z" />
          </svg>
          <small>End</small>
        </button>
      </div>
    </div>
    </>
  );
};

export default GroupCallActivity;