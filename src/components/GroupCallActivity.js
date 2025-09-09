import React, { useEffect, useRef, useState, useMemo } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { Line } from 'react-chartjs-2';
import { Chart, registerables } from 'chart.js';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import '../styles/GroupCallActivity.css';

Chart.register(...registerables);

const GroupCallActivity = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { state } = location;
  const { participants = [], initiator = {}, roomId: initialRoomId } = state || {};
  const isInitiator = Number(localStorage.getItem('userId')) === initiator.id;
  const currentUserId = localStorage.getItem('userId');
  const roomId = useRef(isInitiator ? crypto.randomUUID() : initialRoomId || null);
  const peerConnections = useRef(new Map());
  const localAudioRef = useRef(null);
  const remoteAudioRefs = useRef(new Map());
  const pendingCandidates = useRef(new Map());
  const [callParticipants, setCallParticipants] = useState(participants);
  const [callDuration, setCallDuration] = useState('00:00');
  const [startTime, setStartTime] = useState('');
  const [currentTime, setCurrentTime] = useState('');
  const [readyUsers, setReadyUsers] = useState(new Set());
  const [isWaiting, setIsWaiting] = useState(isInitiator);
  const [isCallStarted, setIsCallStarted] = useState(false);
  const [isCallEnded, setIsCallEnded] = useState(false);
  const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));
  const callStartTime = useRef(Date.now());
  const hasNotifiedEndCall = useRef(false);
  const hasSentInvite = useRef(false);
  const answerTimeouts = useRef(new Map()); // Таймауты для ожидания answer
  let isMounted = true;
  const offerReceived = useRef(new Map()); // Флаг для каждого пользователя

  const memoizedParticipants = useMemo(() => participants, [JSON.stringify(participants)]);
  const hasProcessedInvite = useRef(new Set());
  const unsubscribeRoomRef = useRef(null);
  const offerTimeoutRef = useRef(null);
  const readyTimeoutRef = useRef(null);
  const sentSignals = useRef(new Set());
  const receivedSignals = useRef(new Set());
  const signalQueue = useRef(new Map());
  const isProcessingSignal = useRef(new Map());

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
      toast.error('User ID or initiator ID is missing.');
      navigate('/');
      return;
    }

    if (!roomId.current) {
      console.error('roomId is null, cannot proceed');
      toast.error('Invalid room ID');
      navigate('/');
      return;
    }

    const initializeCall = async () => {
      if (isCallEnded) {
        console.log('Call already ended, skipping initialization');
        return;
      }

      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        console.log('Local stream tracks:', stream.getTracks());
        if (localAudioRef.current) {
          localAudioRef.current.srcObject = stream;
          console.log('Assigned stream to localAudioRef');
          // Обработка очереди сигналов после установки srcObject
          handleQueuedSignals();
        } else {
          console.error('localAudioRef is null during stream assignment');
          toast.error('Audio element not ready');
          endCall();
          return;
        }

        const fetchParticipants = async () => {
          try {
            const updatedParticipants = await Promise.all(
              memoizedParticipants.map(async (user) => {
                if (user.id === Number(currentUserId)) {
                  return { ...user, username: user.username || `User ${user.id}` };
                }
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${user.id}`, {
                  headers: { Authorization: `Bearer ${localStorage.getItem('authToken')}` },
                });
                if (!response.ok) {
                  console.error(`Failed to fetch user ${user.id}`);
                  return { ...user, username: `User ${user.id}`, avatar: null };
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
            toast.error('Failed to load participant details');
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
          unsubscribeRoomRef.current = subscribeToRoom(
            roomId.current,
            () => {
              console.log(`Successfully subscribed to room ${roomId.current}`);
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
              // Повторная отправка offer, если соединение не установлено
              if (isCallStarted) {
                resendPendingSignals();
              }
            },
            (error) => {
              console.error('Subscription error:', error);
              toast.error('Failed to connect to call server. Reconnecting...');
              setTimeout(subscribeWithReconnect, 1000); // Повторная попытка через 1 секунду
            }
          );
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
        toast.error('Failed to initialize group call');
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
            toast.error('Audio element not ready');
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
          toast.error('No participants responded to the call.');
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
          toast.error('Не удалось установить звонок: не получен offer');
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

    const checkConnectionsInterval = setInterval(() => {
      console.log('Periodic connection check triggered');
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
    }, 10000); // Проверка каждые 10 секунд

    return () => {
      clearInterval(checkConnectionsInterval);
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
    peerConnections.current.forEach((pc, userId) => {
      if (pc.iceConnectionState !== 'connected' && pc.iceConnectionState !== 'completed') {
        if (pc.localDescription && pc.localDescription.type === 'offer' && !pc.remoteDescription) {
          const signalKey = `${currentUserId}-${userId}-offer-${pc.localDescription.sdp.slice(0, 50)}`;
          console.log(`Resending offer to user ${userId}`);
          sendSignal(
            {
              from: currentUserId,
              to: userId,
              signal: { type: 'offer', sdp: pc.localDescription.sdp },
            },
            roomId.current
          );
          sentSignals.current.add(signalKey);
        }
      }
    });
  };

  const initializePeerConnection = async (userId, stream) => {
    try {
      if (!localAudioRef.current || !stream) {
        console.error(`Cannot initialize PeerConnection for user ${userId}: localAudioRef or stream is null`);
        toast.error('Audio element or stream not ready');
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
      peerConnections.current.set(userId, peerConnection);

      peerConnection.ontrack = (event) => {
        const stream = event.streams[0];
        console.log(`ontrack event for user ${userId}:`, event);
        if (stream) {
          console.log(`Remote stream tracks for user ${userId}:`, stream.getTracks());
          const audioElement = document.createElement('audio');
          audioElement.id = `remote-audio-${userId}`;
          audioElement.srcObject = stream;
          audioElement.autoplay = true;
          audioElement.onloadedmetadata = () => {
            audioElement.play().then(() => {
              console.log(`Audio playing for user ${userId}`);
            }).catch((error) => {
              console.error(`Error playing audio for ${userId}:`, error);
              toast.error(`Failed to play audio for ${callParticipants.find((u) => u.id === Number(userId))?.username || `User ${userId}`}`);
            });
          };
          remoteAudioRefs.current.set(userId, audioElement);
          document.body.appendChild(audioElement);
          console.log(`Remote audio track added for user ${userId}`);
        } else {
          console.error(`No stream received for user ${userId}`);
          toast.error(`No audio stream received for ${callParticipants.find((u) => u.id === Number(userId))?.username || `User ${userId}`}`);
        }
      };

      let disconnectTimeout = null;

      peerConnection.oniceconnectionstatechange = () => {
        console.log(`ICE Connection State for ${userId}: ${peerConnection.iceConnectionState}`);
        if (peerConnection.iceConnectionState === 'connected') {
          console.log(`Connection established with user ${userId}, clearing readyTimeout`);
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
          toast.error(`Connection failed for user ${callParticipants.find((u) => u.id === Number(userId))?.username || `User ${userId}`}`);
          endCall();
        } else if (peerConnection.iceConnectionState === 'disconnected') {
          console.warn(`WebRTC connection disconnected for user ${userId}`);
          toast.warn(`User ${callParticipants.find((u) => u.id === Number(userId))?.username || `User ${userId}`} disconnected`);
          // Устанавливаем таймаут перед удалением участника
          disconnectTimeout = setTimeout(() => {
            if (peerConnection.iceConnectionState === 'disconnected') {
              console.log(`User ${userId} still disconnected after 10 seconds, removing from call`);
              setCallParticipants((prev) => prev.filter((u) => u.id !== Number(userId)));
              peerConnections.current.delete(userId);
              if (callParticipants.length < 2) {
                console.log('Ending call: initiator or less than 2 participants remain');
                endCall();
              }
            } else {
              console.log(`User ${userId} reconnected or state changed, skipping removal: iceConnectionState=${peerConnection.iceConnectionState}`);
            }
          }, 90000);
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
      console.log(`PeerConnection initialized for user ${userId}`);
      return peerConnection;
    } catch (error) {
      console.error(`Error initializing PeerConnection for user ${userId}:`, error);
      toast.error('Failed to access microphone.');
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
              if (peerConnection.signalingState === 'stable') {
                const offer = await peerConnection.createOffer();
                const modifiedOffer = {
                  type: 'offer',
                  sdp: offer.sdp.replace(/a=setup:actpass/, isInitiator ? 'a=setup:active' : 'a=setup:passive')
                };
                await peerConnection.setLocalDescription(modifiedOffer);
                const signalKey = `${currentUserId}-${user.id}-offer-${modifiedOffer.sdp.slice(0, 50)}`;
                if (!sentSignals.current.has(signalKey)) {
                  sentSignals.current.add(signalKey);
                  sendSignal(
                    {
                      from: currentUserId,
                      to: String(user.id),
                      signal: modifiedOffer,
                    },
                    roomId.current
                  );
                  console.log(`Sent offer to user ${user.id}`);
                  // Установка таймаута для ожидания answer
                  answerTimeouts.current.set(
                    user.id,
                    setTimeout(() => {
                      if (peerConnection && !peerConnection.remoteDescription) {
                        console.error(`No answer received from user ${user.id} within 10 seconds`);
                        toast.error(`No response from ${callParticipants.find((u) => u.id === Number(user.id))?.username || `User ${user.id}`}`);
                        peerConnections.current.delete(String(user.id));
                        setCallParticipants((prev) => prev.filter((u) => u.id !== Number(user.id)));
                        if (isInitiator || callParticipants.length < 2) {
                          // endCall();
                        }
                      }
                    }, 10000)
                  );
                }
              } else {
                console.log(`Skipping offer for user ${user.id}: signalingState is ${peerConnection.signalingState}`);
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
      console.error('Error starting group call:', error);
      toast.error('Failed to start group call.');
      // endCall();
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
    const retryCounts = new Map(); // Счетчик попыток для каждого пользователя

    try {
      for (const user of callParticipants) {
        if (user.id !== Number(currentUserId)) {
          let peerConnection = peerConnections.current.get(String(user.id));
          const retries = retryCounts.get(user.id) || 0;

          // Если достигнуто 9 попыток, удаляем участника
          if (retries >= 9) {
            console.error(`No answer received from user ${user.id} after 9 retries`);
            toast.error(`No response from ${callParticipants.find((u) => u.id === Number(user.id))?.username || `User ${user.id}`} after 90 seconds`);
            peerConnections.current.delete(String(user.id));
            setCallParticipants((prev) => prev.filter((u) => u.id !== Number(user.id)));
            if (callParticipants.length < 2) {
              console.log('Ending call: less than 2 participants remain after retry');
              endCall();
            }
            continue;
          }

          if (peerConnection) {
            if (
              peerConnection.iceConnectionState !== 'connected' &&
              peerConnection.iceConnectionState !== 'completed' &&
              (peerConnection.connectionState === 'new' ||
                peerConnection.connectionState === 'connecting' ||
                peerConnection.connectionState === 'failed')
            ) {
              // Увеличиваем счетчик попыток
              retryCounts.set(user.id, retries + 1);
              console.log(
                `Retrying connection for user ${user.id}, attempt ${retries + 1}, ` +
                `iceConnectionState: ${peerConnection.iceConnectionState}, ` +
                `connectionState: ${peerConnection.connectionState}, ` +
                `signalingState: ${peerConnection.signalingState}`
              );

              // Закрываем текущий peerConnection и пересоздаём новый
              peerConnection.close();
              peerConnections.current.delete(String(user.id));
              console.log(`Closed and deleted peerConnection for user ${user.id}`);

              peerConnection = await initializePeerConnection(String(user.id), localAudioRef.current.srcObject);
              if (!peerConnection) {
                console.error(`Failed to initialize new peerConnection for user ${user.id}`);
                continue;
              }

              // Создаём и отправляем новый offer с явной DTLS-ролью
              try {
                const offer = await peerConnection.createOffer();
                const modifiedOffer = {
                  type: 'offer',
                  sdp: offer.sdp.replace(/a=setup:actpass/, isInitiator ? 'a=setup:active' : 'a=setup:passive')
                };
                await peerConnection.setLocalDescription(modifiedOffer);
                const signalKey = `${currentUserId}-${user.id}-offer-${modifiedOffer.sdp.slice(0, 50)}`;
                if (!sentSignals.current.has(signalKey)) {
                  sentSignals.current.add(signalKey);
                  sendSignal(
                    {
                      from: currentUserId,
                      to: String(user.id),
                      signal: modifiedOffer,
                    },
                    roomId.current
                  );
                  console.log(`Sent retry offer to user ${user.id}`);
                  // Установка таймаута для ожидания answer
                  answerTimeouts.current.set(
                    user.id,
                    setTimeout(() => {
                      if (peerConnection && !peerConnection.remoteDescription) {
                        console.warn(`No answer received from user ${user.id} within 10 seconds on retry ${retries + 1}`);
                      }
                    }, 10000)
                  );
                }
              } catch (error) {
                console.error(`Error creating or sending offer for user ${user.id}:`, error);
                toast.error(`Failed to retry connection with ${callParticipants.find((u) => u.id === Number(user.id))?.username || `User ${user.id}`}`);
              }
            } else {
              console.log(
                `Connection with user ${user.id} is already established, ` +
                `iceConnectionState: ${peerConnection.iceConnectionState}, ` +
                `connectionState: ${peerConnection.connectionState}`
              );
            }
          } else {
            console.warn(`No peerConnection for user ${user.id}, initializing new one`);
            peerConnection = await initializePeerConnection(String(user.id), localAudioRef.current.srcObject);
            if (peerConnection) {
              try {
                const offer = await peerConnection.createOffer();
                const modifiedOffer = {
                  type: 'offer',
                  sdp: offer.sdp.replace(/a=setup:actpass/, isInitiator ? 'a=setup:active' : 'a=setup:passive')
                };
                await peerConnection.setLocalDescription(modifiedOffer);
                const signalKey = `${currentUserId}-${user.id}-offer-${modifiedOffer.sdp.slice(0, 50)}`;
                if (!sentSignals.current.has(signalKey)) {
                  sentSignals.current.add(signalKey);
                  sendSignal(
                    {
                      from: currentUserId,
                      to: String(user.id),
                      signal: modifiedOffer,
                    },
                    roomId.current
                  );
                  console.log(`Sent initial offer to user ${user.id}`);
                  answerTimeouts.current.set(
                    user.id,
                    setTimeout(() => {
                      if (peerConnection && !peerConnection.remoteDescription) {
                        console.warn(`No answer received from user ${user.id} within 10 seconds`);
                      }
                    }, 10000)
                  );
                }
              } catch (error) {
                console.error(`Error creating initial offer for user ${user.id}:`, error);
                toast.error(`Failed to initialize connection with ${callParticipants.find((u) => u.id === Number(user.id))?.username || `User ${user.id}`}`);
              }
            }
          }
        }
      }
    } catch (error) {
      console.error('Error retrying failed connections:', error);
      toast.error('Failed to retry connections.');
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

    // Фильтрация дублирующихся сигналов
    const signalKey = `${from}-${to || 'null'}-${signal.type}-${signal.sdp?.slice(0, 50) || signal.iceCandidate?.candidate || 'no-sdp'}`;
    if (receivedSignals.current.has(signalKey)) {
      console.log(`Ignoring duplicate signal: ${signalKey}`);
      return;
    }
    receivedSignals.current.add(signalKey);

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
          toast.error('Failed to initialize connection.');
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
                toast.error('Invalid call invite received.');
                navigate('/');
                return;
              }
              roomId.current = currentSignalData.roomId;
              console.log('Updated roomId to:', roomId.current);
              toast.info(`${currentSignalData.initiatorName} invites you to a group call`);
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
              toast.success(`${userName} accepted the group call`);
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
              toast.success(`${userName} is ready`);
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
            offerReceived.current.set(currentFrom, true);
            try {
              const currentState = peerConnection.signalingState;
              console.log(`Current signaling state for user ${currentFrom}: ${currentState}`);
              if (peerConnection.remoteDescription && peerConnection.remoteDescription.sdp === currentSignalData.sdp) {
                console.log(`Ignoring duplicate offer from ${currentFrom}`);
                continue;
              }
              if (currentState !== 'stable') {
                console.log(`Rolling back signaling state for user ${currentFrom} from ${currentState}`);
                await peerConnection.setLocalDescription({ type: 'rollback' });
                console.log(`Rollback successful for user ${currentFrom}`);
              }
              await peerConnection.setRemoteDescription(new RTCSessionDescription(currentSignalData));
              console.log(`Set remoteDescription for user ${currentFrom}`);
              const answer = await peerConnection.createAnswer();
              // Явно устанавливаем a=setup:passive в SDP ответа
              const modifiedAnswer = {
                type: 'answer',
                sdp: answer.sdp.replace(/a=setup:actpass|a=setup:active/, 'a=setup:passive')
              };
              await peerConnection.setLocalDescription(modifiedAnswer);
              const signalKey = `${currentUserId}-${currentFrom}-answer-${modifiedAnswer.sdp.slice(0, 50)}`;
              if (!sentSignals.current.has(signalKey)) {
                sentSignals.current.add(signalKey);
                sendSignal(
                  { from: currentUserId, to: currentFrom, signal: modifiedAnswer },
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
              if (!isCallStarted) {
                startCall();
                console.log('Call started after receiving offer');
              }
            } catch (error) {
              console.error(`Error processing offer from ${currentFrom}:`, error);
              toast.error(`Failed to process offer from ${callParticipants.find((u) => u.id === Number(currentFrom))?.username || `User ${currentFrom}`}`);
            }
            break;

          case 'answer':
            console.log('Processing answer from:', currentFrom, 'signal:', currentSignalData);
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
              toast.error(`Failed to process answer from ${callParticipants.find((u) => u.id === Number(currentFrom))?.username || `User ${currentFrom}`}`);
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
              toast.warn(`${userName} rejected the group call`);
              setCallParticipants((prev) => prev.filter((u) => u.id !== Number(currentFrom)));
              peerConnections.current.delete(currentFrom);
              if (callParticipants.length <= 1) {
                console.log('No participants left after reject, ending call');
                endCall();
              }
            }
            break;

          case 'endCall':
            if (!offerReceived.current.has(currentFrom)) {
              console.log(`Ignoring endCall from ${currentFrom} as no offer has been received yet`);
              continue; // Игнорируем endCall, если offer не получен
            }
            if (to && to !== String(currentUserId)) {
              console.log('Ignoring endCall signal not addressed to current user:', { from: currentFrom, to, currentUserId });
              continue;
            }
            if (!hasNotifiedEndCall.current) {
              hasNotifiedEndCall.current = true;
              const userName = callParticipants.find((u) => u.id === Number(currentFrom))?.username || `User ${currentFrom}`;
              toast.info(`${userName} left the group call`);
              setCallParticipants((prev) => {
                const updatedParticipants = prev.filter((u) => u.id !== Number(currentFrom));
                console.log('Updated participants after endCall:', updatedParticipants);
                return updatedParticipants;
              });
              if (peerConnection) {
                peerConnection.close();
                peerConnections.current.delete(currentFrom);
              }
              if (isInitiator || callParticipants.length <= 2) {
                console.log('Ending call: initiator or less than 2 participants remain');
                endCall();
              }
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
    if (isCallEnded) {
      console.log('Call already ended, skipping endCall');
      return;
    }
    setIsCallEnded(true);
    setIsCallStarted(false);
    setIsWaiting(false);

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
    }

    if (unsubscribeRoomRef.current) {
      unsubscribeRoomRef.current();
      console.log(`Unsubscribed from room ${roomId.current} in endCall`);
      unsubscribeRoomRef.current = null;
    }

    peerConnections.current.forEach((pc) => {
      pc.close();
    });
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

    ringtoneRef.current.pause();
    ringtoneRef.current.currentTime = 0;

    if (!hasNotifiedEndCall.current) {
      hasNotifiedEndCall.current = true;
      toast.info('Group call ended');
    }

    navigate('/');
  };

  return (
    <div className="group-call-container">
      <h1 className="group-call-title">Group Call with {initiator.username}</h1>
      <div className="group-call-info">
        <p className="call-timer">Duration: {callDuration}</p>
        <p className="call-start-time">Start: {startTime}</p>
        <p className="call-current-time">Current: {currentTime}</p>
      </div>
      <div className="group-call-participants">
        <h2>Participants</h2>
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
              <span className="group-call-participant-name">{user.username}</span>
            </li>
          ))}
        </ul>
      </div>
      <div className="group-call-chart-container">
        <Line
          data={chartData}
          options={{
            responsive: true,
            maintainAspectRatio: false,
            scales: {
              x: { title: { display: true, text: 'Time' } },
              y: { title: { display: true, text: 'Activity Level' } },
            },
          }}
        />
      </div>
      <audio id="local-audio" ref={localAudioRef} autoPlay muted />
      <button className="group-call-end-button" onClick={endCall}>
        End Call
      </button>
    </div>
  );
};

export default GroupCallActivity;