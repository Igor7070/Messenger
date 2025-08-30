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
  let isMounted = true;

  const memoizedParticipants = useMemo(() => participants, [JSON.stringify(participants)]);
  const hasProcessedInvite = useRef(new Set());
  const unsubscribeRoomRef = useRef(null);
  const offerTimeoutRef = useRef(null);
  const readyTimeoutRef = useRef(null);
  const sentSignals = useRef(new Set());
  const receivedSignals = useRef(new Set()); // Добавлено для фильтрации входящих сигналов

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

      if (!localAudioRef.current) {
        console.error('localAudioRef is null');
        toast.error('Audio element not ready');
        endCall();
        return;
      }

      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        console.log('Local stream tracks:', stream.getTracks());
        localAudioRef.current.srcObject = stream;

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
          },
          (error) => {
            console.error('Subscription error:', error);
            toast.error('Failed to connect to call server');
            endCall();
          }
        );

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

    if (document.getElementById('local-audio')) {
      localAudioRef.current = document.getElementById('local-audio');
      initializeCall();
    } else {
      console.error('Local audio element not found in DOM');
      toast.error('Audio element not ready');
      endCall();
    }

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
    }, 30000);

    offerTimeoutRef.current = setTimeout(() => {
      if (!isInitiator && !isCallStarted && isMounted) {
        console.error('No offer received within 60 seconds, aborting call');
        toast.error('Failed to establish call: no offer received');
        endCall();
      }
    }, 60000);

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

  const initializePeerConnection = async (userId, stream) => {
    try {
      if (!localAudioRef.current) {
        console.error(`Cannot initialize PeerConnection for user ${userId}: localAudioRef is null`);
        toast.error('Audio element not ready');
        endCall();
        return null;
      }

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

      peerConnection.oniceconnectionstatechange = () => {
        console.log(`ICE Connection State for ${userId}: ${peerConnection.iceConnectionState}`);
        if (peerConnection.iceConnectionState === 'connected') {
          console.log(`Connection established with user ${userId}, clearing readyTimeout`);
          if (readyTimeoutRef.current) {
            clearTimeout(readyTimeoutRef.current);
            readyTimeoutRef.current = null;
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
        } else if (peerConnection.iceConnectionstate === 'disconnected') {
          console.warn(`WebRTC connection disconnected for user ${userId}`);
          toast.warn(`User ${callParticipants.find((u) => u.id === Number(userId))?.username || `User ${userId}`} disconnected`);
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
          if (peerConnection && !peerConnection.localDescription) {
            const offer = await peerConnection.createOffer();
            await peerConnection.setLocalDescription(offer);
            const signalKey = `${currentUserId}-${user.id}-offer-${offer.sdp.slice(0, 50)}`;
            if (!sentSignals.current.has(signalKey)) {
              sentSignals.current.add(signalKey);
              sendSignal(
                {
                  from: currentUserId,
                  to: String(user.id),
                  signal: { type: 'offer', sdp: offer.sdp },
                },
                roomId.current
              );
              console.log(`Sent offer to user ${user.id}`);
            }
          } else {
            console.warn(`Skipping offer for user ${user.id}: peerConnection=${!!peerConnection}, localDescription=${!!peerConnection?.localDescription}`);
          }
        }
      }
    } catch (error) {
      console.error('Error starting group call:', error);
      toast.error('Failed to start group call.');
      endCall();
    }
  };

  const handleSignal = async (signalMessage) => {
    if (isCallEnded || !isMounted || !localAudioRef.current) {
      console.log('Ignoring signal due to call ended, component unmounted, or localAudioRef null:', signalMessage);
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
    console.log('Signal received:', { from, to, type: signal.type, currentUserId });

    // Фильтрация дублирующихся сигналов
    const signalKey = `${from}-${to || 'null'}-${signal.type}-${signal.sdp?.slice(0, 50) || signal.iceCandidate?.candidate || 'no-sdp'}`;
    if (receivedSignals.current.has(signalKey)) {
      console.log(`Ignoring duplicate signal: ${signalKey}`);
      return;
    }
    receivedSignals.current.add(signalKey);

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
      if (!peerConnection) {
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
                console.log(`Ignoring duplicate ready signal from user ${currentFrom}`);
                return prev;
              }
              const newSet = new Set([...prev, currentFrom]);
              console.log('Ready users updated:', Array.from(newSet));
              const userName = callParticipants.find((u) => u.id === Number(currentFrom))?.username || `User ${currentFrom}`;
              toast.success(`${userName} is ready`);
              console.log(`User ${userName} is ready`);
              return newSet;
            });
            // Упрощённое условие: запускаем startCall при получении первого ready
            if (!isCallStarted) {
              startCall();
              console.log('Call started after receiving ready signal');
            }
            break;

          case 'offer':
            console.log('Processing offer from:', currentFrom, 'signal:', currentSignalData);
            try {
              const currentState = peerConnection.signalingState;
              console.log(`Current signaling state for user ${currentFrom}: ${currentState}`);
              if (peerConnection.remoteDescription && peerConnection.remoteDescription.sdp === currentSignalData.sdp) {
                console.log(`Ignoring duplicate offer from ${currentFrom}`);
                continue;
              }
              if (currentState === 'stable') {
                await peerConnection.setRemoteDescription(new RTCSessionDescription(currentSignalData));
                console.log(`Set remoteDescription for user ${currentFrom}`);
                const answer = await peerConnection.createAnswer();
                await peerConnection.setLocalDescription(answer);
                const signalKey = `${currentUserId}-${currentFrom}-answer-${answer.sdp.slice(0, 50)}`;
                if (!sentSignals.current.has(signalKey)) {
                  sentSignals.current.add(signalKey);
                  sendSignal(
                    { from: currentUserId, to: currentFrom, signal: { type: 'answer', sdp: answer.sdp } },
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
                // Запускаем startCall после получения offer
                if (!isCallStarted) {
                  startCall();
                  console.log('Call started after receiving offer');
                }
              } else {
                console.warn(`Cannot process offer from ${currentFrom} in signaling state: ${currentState}, resetting peerConnection`);
                peerConnections.current.delete(currentFrom);
                const newPeerConnection = await initializePeerConnection(currentFrom, localAudioRef.current.srcObject);
                if (newPeerConnection) {
                  await newPeerConnection.setRemoteDescription(new RTCSessionDescription(currentSignalData));
                  const answer = await newPeerConnection.createAnswer();
                  await newPeerConnection.setLocalDescription(answer);
                  const signalKey = `${currentUserId}-${currentFrom}-answer-${answer.sdp.slice(0, 50)}`;
                  if (!sentSignals.current.has(signalKey)) {
                    sentSignals.current.add(signalKey);
                    sendSignal(
                      { from: currentUserId, to: currentFrom, signal: { type: 'answer', sdp: answer.sdp } },
                      roomId.current
                    );
                    console.log(`Sent answer to user ${currentFrom} after resetting peerConnection`);
                  }
                  if (pendingCandidates.current.has(currentFrom)) {
                    for (const candidate of pendingCandidates.current.get(currentFrom)) {
                      await newPeerConnection.addIceCandidate(candidate);
                      console.log(`Processed pending ICE candidate for ${currentFrom}`);
                    }
                    pendingCandidates.current.delete(currentFrom);
                  }
                  if (!isCallStarted) {
                    startCall();
                    console.log('Call started after receiving offer and resetting peerConnection');
                  }
                } else {
                  console.error(`Failed to reset peerConnection for ${currentFrom}`);
                }
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