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
  const pendingSignals = useRef(new Map());
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
  const processedSignals = useRef(new Set()); // Для фильтрации дублирующихся сигналов
  let isMounted = true;

  const memoizedParticipants = useMemo(() => participants, [JSON.stringify(participants)]);
  const hasProcessedInvite = useRef(new Set());
  const unsubscribeRoomRef = useRef(null);
  const offerTimeoutRef = useRef(null);
  const readyTimeoutRef = useRef(null);

  const { sendSignal, setGroupSignalHandler, subscribeToRoom, isStompConnected } = useCallWebSocket();

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

      let localStream;
      try {
        localStream = await navigator.mediaDevices.getUserMedia({ audio: true });
        console.log('Local stream tracks:', localStream.getTracks());
        localAudioRef.current.srcObject = localStream;
      } catch (error) {
        console.error('Error getting user media:', error);
        toast.error('Failed to access microphone');
        endCall();
        return;
      }

      try {
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
            await initializePeerConnection(String(user.id), localStream);
          }
        }

        setGroupSignalHandler(handleSignal);

        unsubscribeRoomRef.current = subscribeToRoom(
          roomId.current,
          () => {
            console.log(`Successfully subscribed to room ${roomId.current}`);
            if (isInitiator && !hasSentInvite.current) {
              hasSentInvite.current = true;
              sendGroupCallInvite();
            } else if (!isInitiator && !hasSentInvite.current) {
              hasSentInvite.current = true;
              console.log('Sending acceptCall and ready signals');
              const acceptSignal = { from: currentUserId, signal: { type: 'acceptCall' } };
              if (isStompConnected) {
                sendSignal(acceptSignal, roomId.current);
              } else {
                pendingSignals.current.set(`acceptCall-${currentUserId}`, acceptSignal);
              }
              setTimeout(() => {
                memoizedParticipants
                  .filter((p) => p.id !== Number(currentUserId))
                  .forEach((user) => {
                    const readySignal = { from: currentUserId, to: String(user.id), signal: { type: 'ready' } };
                    if (isStompConnected) {
                      sendSignal(readySignal, roomId.current);
                    } else {
                      pendingSignals.current.set(`ready-${user.id}`, readySignal);
                    }
                  });
              }, 1000);
            }
            for (const [key, signal] of pendingSignals.current) {
              sendSignal(signal, roomId.current, signal.to);
              pendingSignals.current.delete(key);
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
  }, [currentUserId, initiator.id, isInitiator, isCallEnded, memoizedParticipants, sendSignal, setGroupSignalHandler, subscribeToRoom, isStompConnected, navigate]);

  const sendGroupCallInvite = () => {
    if (!roomId.current) {
      console.error('Cannot send group call invite: roomId is null');
      return;
    }
    const groupCallRequest = {
      type: 'groupCallInvite',
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
        console.log('Sending groupCallInvite to user:', user.id, groupCallRequest);
        const signalKey = `groupCallInvite-${user.id}`;
        if (!processedSignals.current.has(signalKey)) {
          processedSignals.current.add(signalKey);
          if (isStompConnected) {
            sendSignal(groupCallRequest, roomId.current, String(user.id));
          } else {
            pendingSignals.current.set(signalKey, groupCallRequest);
          }
          console.log(`Sent group call invite to user ${user.id}`);
        }
      });
  };

  const initializePeerConnection = async (userId, localStream) => {
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
          audioElement.srcObject = stream;
          audioElement.autoplay = true;
          remoteAudioRefs.current.set(userId, audioElement);
          document.body.appendChild(audioElement);
          console.log(`Remote audio track added for user ${userId}`);
        } else {
          console.error(`No stream received for user ${userId}`);
        }
      };

      peerConnection.oniceconnectionstatechange = () => {
        console.log(`ICE Connection State for ${userId}: ${peerConnection.iceConnectionState}, Connection State: ${peerConnection.connectionState}`);
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
          console.error(`WebRTC connection failed for user ${userId}:`, peerConnection.iceConnectionState);
          toast.error(`Connection failed for user ${callParticipants.find((u) => u.id === Number(userId))?.username || `User ${userId}`}`);
          endCall();
        } else if (peerConnection.iceConnectionState === 'disconnected') {
          console.warn(`WebRTC connection disconnected for user ${userId}`);
          toast.warn(`User ${callParticipants.find((u) => u.id === Number(userId))?.username || `User ${userId}`} disconnected`);
        }
      };

      peerConnection.onsignalingstatechange = () => {
        console.log(`Signaling state for user ${userId}: ${peerConnection.signalingState}`);
      };

      peerConnection.onicecandidate = (event) => {
        if (event.candidate) {
          const signal = {
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
          };
          const signalKey = `candidate-${userId}-${event.candidate.candidate}`;
          if (!processedSignals.current.has(signalKey)) {
            processedSignals.current.add(signalKey);
            if (isStompConnected) {
              sendSignal(signal, roomId.current);
              console.log(`Sent ICE candidate for user ${userId}:`, event.candidate);
            } else {
              console.log(`Queuing ICE candidate for user ${userId}: WebSocket not connected`);
              pendingSignals.current.set(signalKey, signal);
            }
          }
        }
      };

      localStream.getTracks().forEach((track) => peerConnection.addTrack(track, localStream));
      console.log(`PeerConnection initialized for user ${userId}`);
      return peerConnection;
    } catch (error) {
      console.error(`Error initializing PeerConnection for user ${userId}:`, error);
      toast.error('Failed to initialize peer connection');
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
        if (user.id !== initiator.id && user.id !== Number(currentUserId)) {
          const peerConnection = peerConnections.current.get(String(user.id)) || (await initializePeerConnection(String(user.id), localAudioRef.current.srcObject));
          if (isInitiator && peerConnection && !peerConnection.localDescription) {
            const offer = await peerConnection.createOffer();
            await peerConnection.setLocalDescription(offer);
            const signal = {
              from: currentUserId,
              to: String(user.id),
              signal: { type: 'offer', sdp: offer.sdp },
            };
            const signalKey = `offer-${user.id}`;
            if (!processedSignals.current.has(signalKey)) {
              processedSignals.current.add(signalKey);
              if (isStompConnected) {
                sendSignal(signal, roomId.current);
                console.log(`Sent offer to user ${user.id}`);
              } else {
                console.log(`Queuing offer for user ${user.id}: WebSocket not connected`);
                pendingSignals.current.set(signalKey, signal);
              }
            }
          } else {
            console.log(`Skipping offer for user ${user.id}: already sent or not initiator`);
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
    const signalKey = `${signal.type}-${from}-${to || 'null'}-${signal.sdp || signal.iceCandidate?.candidate || ''}`;
    if (processedSignals.current.has(signalKey)) {
      console.log(`Ignoring duplicate signal: ${signalKey}`);
      return;
    }
    processedSignals.current.add(signalKey);

    console.log('Signal received:', { from, to, type: signal.type, currentUserId, signalingState: peerConnections.current.get(from)?.signalingState });

    if (signal.type === 'groupCallInvite' && !isInitiator) {
      if (hasProcessedInvite.current.has(from)) {
        console.log(`Ignoring duplicate groupCallInvite from ${from}`);
        return;
      }
      hasProcessedInvite.current.add(from);
      console.log('Processing groupCallInvite:', signalMessage);
      if (!signal.roomId) {
        console.error('groupCallInvite received with null roomId');
        toast.error('Invalid call invite received.');
        navigate('/');
        return;
      }
      roomId.current = signal.roomId;
      console.log('Updated roomId to:', roomId.current);
      toast.info(`${signal.initiatorName} invites you to a group call`);
      return;
    }

    if (!peerConnections.current.has(from) && from !== String(currentUserId)) {
      console.log('Initializing peerConnection for user:', from);
      const peerConnection = await initializePeerConnection(from, localAudioRef.current.srcObject);
      if (!peerConnection) {
        console.warn(`Failed to initialize PeerConnection for user ${from}`);
        return;
      }
    }

    if (to && to !== String(currentUserId) && !['acceptCall', 'ready', 'groupCallInvite'].includes(signal.type)) {
      console.log('Ignoring signal not addressed to current user:', { from, to, type: signal.type, currentUserId });
      return;
    }

    const peerConnection = peerConnections.current.get(from);

    try {
      switch (signal.type) {
        case 'acceptCall':
          if (isInitiator) {
            const userName = callParticipants.find((u) => u.id === Number(from))?.username || `User ${from}`;
            toast.success(`${userName} accepted the group call`);
          }
          break;

        case 'ready':
          setReadyUsers((prev) => {
            if (prev.has(from)) {
              console.log(`Ignoring duplicate ready signal from user ${from}`);
              return prev;
            }
            const newSet = new Set([...prev, from]);
            console.log('Ready users updated:', Array.from(newSet));
            const userName = callParticipants.find((u) => u.id === Number(from))?.username || `User ${from}`;
            toast.success(`${userName} is ready`);
            console.log(`User ${userName} is ready`);
            return newSet;
          });
          if (!isCallStarted && (callParticipants.length <= 2 || readyUsers.size + 1 >= callParticipants.length - 1)) {
            startCall();
            console.log('Call started for ready participants');
          }
          if (!isInitiator) {
            callParticipants
              .filter((p) => p.id !== Number(currentUserId) && p.id !== initiator.id)
              .forEach(async (user) => {
                const peerConnection = peerConnections.current.get(String(user.id)) || (await initializePeerConnection(String(user.id), localAudioRef.current.srcObject));
                if (peerConnection && !peerConnection.localDescription) {
                  try {
                    const offer = await peerConnection.createOffer();
                    await peerConnection.setLocalDescription(offer);
                    const signal = {
                      from: currentUserId,
                      to: String(user.id),
                      signal: { type: 'offer', sdp: offer.sdp },
                    };
                    const signalKey = `offer-${user.id}`;
                    if (!processedSignals.current.has(signalKey)) {
                      processedSignals.current.add(signalKey);
                      if (isStompConnected) {
                        sendSignal(signal, roomId.current);
                        console.log(`Sent offer to user ${user.id} after receiving ready from ${from}`);
                      } else {
                        console.log(`Queuing offer for user ${user.id}: WebSocket not connected`);
                        pendingSignals.current.set(signalKey, signal);
                      }
                    }
                  } catch (error) {
                    console.error(`Error creating offer for ${user.id}:`, error);
                  }
                } else {
                  console.log(`Skipping offer for user ${user.id}: already sent or no peerConnection`);
                }
              });
          }
          break;

        case 'offer':
          console.log('Processing offer from:', from, 'signal:', signal);
          if (!peerConnection) {
            console.error(`No PeerConnection for user ${from}`);
            return;
          }
          if (peerConnection.signalingState !== 'stable') {
            console.warn(`Ignoring offer from ${from} due to signaling state: ${peerConnection.signalingState}`);
            return;
          }
          try {
            await peerConnection.setRemoteDescription(new RTCSessionDescription(signal));
            const answer = await peerConnection.createAnswer();
            await peerConnection.setLocalDescription(answer);
            const answerSignal = {
              from: currentUserId,
              to: from,
              signal: { type: 'answer', sdp: answer.sdp },
            };
            const answerSignalKey = `answer-${from}`;
            if (!processedSignals.current.has(answerSignalKey)) {
              processedSignals.current.add(answerSignalKey);
              if (isStompConnected) {
                sendSignal(answerSignal, roomId.current);
                console.log(`Sent answer to user ${from}`);
              } else {
                console.log(`Queuing answer for user ${from}: WebSocket not connected`);
                pendingSignals.current.set(answerSignalKey, answerSignal);
              }
            }
            if (pendingCandidates.current.has(from)) {
              for (const candidate of pendingCandidates.current.get(from)) {
                await peerConnection.addIceCandidate(candidate);
                console.log(`Processed pending ICE candidate for ${from}`);
              }
              pendingCandidates.current.delete(from);
            }
            if (offerTimeoutRef.current) {
              clearTimeout(offerTimeoutRef.current);
              offerTimeoutRef.current = null;
              console.log('Cleared offerTimeout after receiving offer');
            }
          } catch (error) {
            console.error(`Error processing offer from ${from}:`, error);
            toast.error(`Failed to process offer from user ${from}`);
          }
          break;

        case 'answer':
          if (peerConnection && !peerConnection.remoteDescription) {
            await peerConnection.setRemoteDescription(new RTCSessionDescription(signal));
            console.log(`Processed answer from user ${from}`);
          } else {
            console.warn(`No peerConnection or remoteDescription already set for user ${from}`);
          }
          break;

        case 'candidate':
          const { iceCandidate } = signal;
          if (peerConnection && iceCandidate.candidate) {
            const candidate = new RTCIceCandidate({
              candidate: iceCandidate.candidate,
              sdpMid: iceCandidate.sdpMid,
              sdpMLineIndex: Number(iceCandidate.sdpMLineIndex),
            });
            if (peerConnection.remoteDescription) {
              await peerConnection.addIceCandidate(candidate);
              console.log(`Processed ICE candidate from user ${from}`);
            } else {
              console.warn(`Queuing ICE candidate for ${from}: remoteDescription not set`);
              pendingCandidates.current.set(from, [
                ...(pendingCandidates.current.get(from) || []),
                candidate,
              ]);
            }
          } else {
            console.error(`Cannot add ICE candidate: peerConnection=${!!peerConnection}, candidate=${!!iceCandidate.candidate}`);
          }
          break;

        case 'rejectCall':
          if (isInitiator) {
            const userName = callParticipants.find((u) => u.id === Number(from))?.username || `User ${from}`;
            toast.warn(`${userName} rejected the group call`);
            setCallParticipants((prev) => prev.filter((u) => u.id !== Number(from)));
            peerConnections.current.delete(from);
            if (callParticipants.length <= 1) {
              console.log('No participants left after reject, ending call');
              endCall();
            }
          }
          break;

        case 'endCall':
          if (to && to !== String(currentUserId)) {
            console.log('Ignoring endCall signal not addressed to current user:', { from, to, currentUserId });
            return;
          }
          if (!hasNotifiedEndCall.current) {
            hasNotifiedEndCall.current = true;
            const userName = callParticipants.find((u) => u.id === Number(from))?.username || `User ${from}`;
            toast.info(`${userName} left the group call`);
            setCallParticipants((prev) => {
              const updatedParticipants = prev.filter((u) => u.id !== Number(from));
              console.log('Updated participants after endCall:', updatedParticipants);
              return updatedParticipants;
            });
            if (peerConnection) {
              peerConnection.close();
              peerConnections.current.delete(from);
            }
            if (isInitiator || callParticipants.length <= 2) {
              console.log('Ending call: initiator or less than 2 participants remain');
              endCall();
            }
          }
          break;

        default:
          console.warn('Unknown signal type:', signal.type);
      }
    } catch (error) {
      console.error('Error processing signal:', error);
    }
  };

  const endCall = () => {
    console.log('endCall triggered');
    if (isCallEnded) {
      console.log('Call already ended, skipping endCall');
      return;
    }
    setIsCallEnded(true);
    setIsCallStarted(false);
    setIsWaiting(false);

    if (!hasNotifiedEndCall.current) {
      hasNotifiedEndCall.current = true;
      callParticipants
        .filter((u) => u.id !== Number(currentUserId))
        .forEach((user) => {
          const signal = {
            from: currentUserId,
            to: String(user.id),
            signal: { type: 'endCall', roomId: roomId.current },
          };
          const signalKey = `endCall-${user.id}`;
          if (!processedSignals.current.has(signalKey)) {
            processedSignals.current.add(signalKey);
            if (isStompConnected) {
              sendSignal(signal, roomId.current);
              console.log(`Sent endCall to user ${user.id}`);
            } else {
              console.log(`Queuing endCall for user ${user.id}: WebSocket not connected`);
              pendingSignals.current.set(signalKey, signal);
            }
          }
        });
    }

    if (unsubscribeRoomRef.current) {
      unsubscribeRoomRef.current();
      console.log(`Unsubscribed from room ${roomId.current} in endCall`);
      unsubscribeRoomRef.current = null;
    }

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

    ringtoneRef.current.pause();
    ringtoneRef.current.currentTime = 0;

    toast.info('Group call ended');
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