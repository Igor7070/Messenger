import React, { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { toast } from 'react-toastify';
import { Line } from 'react-chartjs-2';
import { Chart, registerables } from 'chart.js';
import '../styles/GroupCallActivity.css';

Chart.register(...registerables);

const GroupCallActivity = () => {
  const navigate = useNavigate();
  const { state } = useLocation();
  const { participants = [], initiator = {}, roomId: incomingRoomId } = state || {};
  const isInitiator = Number(localStorage.getItem('userId')) === initiator.id;
  const currentUserId = localStorage.getItem('userId');
  const roomId = useRef(isInitiator ? crypto.randomUUID() : incomingRoomId || null);
  const peerConnections = useRef(new Map());
  const localAudioRef = useRef(null);
  const remoteAudioRefs = useRef(new Map());
  const stompClient = useRef(null);
  const signalQueue = useRef([]);
  const subscriptionRef = useRef(null);
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

  const initializeWebSocket = () => {
    if (stompClient.current && stompClient.current.connected) {
      console.log('WebSocket already connected for room:', roomId.current);
      subscribeToRoom();
      return;
    }

    if (!roomId.current) {
      console.error('Cannot initialize WebSocket: roomId is null');
      toast.error('Invalid room ID for WebSocket connection.');
      navigate('/');
      return;
    }

    stompClient.current = new Client({
      webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        if (!isMounted) return;
        console.log('Connected to WebSocket for room:', roomId.current);
        subscribeToRoom();
        if (isInitiator && !hasSentInvite.current) {
          hasSentInvite.current = true;
          sendGroupCallInvite();
        }
        while (signalQueue.current.length > 0 && stompClient.current.connected) {
          const signalMessage = signalQueue.current.shift();
          stompClient.current.publish({
            destination: `/app/groupSignal/${roomId.current}`,
            body: JSON.stringify(signalMessage),
          });
          console.log('Signal sent from queue to /app/groupSignal/' + roomId.current + ':', signalMessage);
        }
      },
      onStompError: (frame) => {
        console.error('STOMP error for room ' + roomId.current + ':', frame);
        toast.error('STOMP protocol error.');
      },
      onWebSocketError: (error) => {
        console.warn('WebSocket error for room ' + roomId.current + ':', {
          error,
          timestamp: new Date().toISOString(),
          route: window.location.pathname,
          roomId: roomId.current,
        });
        setTimeout(() => {
          if (isMounted && !stompClient.current.connected) {
            console.log('Retrying WebSocket activation for room:', roomId.current);
            stompClient.current.activate();
          }
        }, 1000);
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected for room:', roomId.current);
        if (isMounted && !isCallEnded) {
          toast.error('WebSocket connection lost.');
          endCall();
        }
      },
    });

    console.log('Activating WebSocket for room:', roomId.current);
    stompClient.current.activate();
  };

  const subscribeToRoom = () => {
    if (!roomId.current) {
      console.error('Cannot subscribe to room: roomId is null');
      return;
    }
    if (stompClient.current && stompClient.current.connected) {
      subscriptionRef.current = stompClient.current.subscribe(`/topic/room/${roomId.current}`, (message) => {
        if (!isMounted) return;
        try {
          const signalMessage = JSON.parse(message.body);
          console.log('Received signal on /topic/room/' + roomId.current + ':', signalMessage);
          handleSignal(signalMessage);
        } catch (error) {
          console.error('Error parsing WebSocket message for room ' + roomId.current + ':', error);
        }
      });
      console.log('Subscribed to /topic/room/' + roomId.current);
    } else {
      console.warn('Cannot subscribe to room: STOMP client not connected', {
        connected: stompClient.current?.connected,
        roomId: roomId.current,
      });
      initializeWebSocket();
    }
  };

  useEffect(() => {
    console.log('useEffect called with dependencies:', {
      currentUserId,
      initiatorId: initiator.id,
      isInitiator,
      isCallEnded,
      incomingRoomId,
    });

    if (!currentUserId || !initiator.id || (!isInitiator && !incomingRoomId)) {
      console.error('Missing required data:', { currentUserId, initiatorId: initiator.id, incomingRoomId });
      toast.error('User ID, initiator ID, or room ID is missing.');
      navigate('/');
      return;
    }

    // Инициализация WebSocket для всех случаев
    initializeWebSocket();

    // Установка гудка для инициатора
    if (isInitiator && isMounted) {
      setIsWaiting(true);
      ringtoneRef.current.loop = true;
      ringtoneRef.current.play().catch((error) => {
        if (isMounted) console.error('Error playing ringtone:', error);
      });
    }

    // Таймер звонка
    const timerInterval = setInterval(() => {
      if (!isMounted) return;
      const duration = Math.floor((Date.now() - callStartTime.current) / 1000);
      const minutes = Math.floor(duration / 60);
      const seconds = duration % 60;
      setCallDuration(`${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`);
    }, 1000);

    // Время начала и текущее время
    const timeInterval = setInterval(() => {
      if (!isMounted) return;
      const now = new Date();
      setCurrentTime(now.toLocaleTimeString('en-US', { hour12: false }));
      if (!startTime) {
        setStartTime(now.toLocaleTimeString('en-US', { hour12: false }));
      }
    }, 1000);

    // Анимация графика
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

    // Таймаут для ожидания сигналов ready
    const readyTimeout = setTimeout(() => {
      if (isInitiator && isWaiting && readyUsers.size === 0 && isMounted && !isCallStarted) {
        console.warn('No participants responded within 30 seconds, ending call.');
        toast.error('No participants responded to the call.');
        endCall();
      } else if (isInitiator && isWaiting && isMounted) {
        console.log('Participants responded, call status:', { isCallStarted, readyUsers: Array.from(readyUsers) });
      }
    }, 30000);

    return () => {
      isMounted = false;
      clearInterval(timerInterval);
      clearInterval(timeInterval);
      clearInterval(chartInterval);
      clearTimeout(readyTimeout);
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
      if (stompClient.current && !isCallEnded) {
        if (subscriptionRef.current) {
          subscriptionRef.current.unsubscribe();
          console.log('Unsubscribed from /topic/room/' + roomId.current);
        }
        stompClient.current.deactivate();
        console.log('WebSocket deactivated on cleanup');
      }
    };
  }, [currentUserId, initiator.id, isInitiator, navigate, isCallEnded, incomingRoomId]);

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
        if (stompClient.current && stompClient.current.connected) {
          stompClient.current.publish({
            destination: `/app/groupCall/${user.id}`,
            body: JSON.stringify(groupCallRequest),
          });
          console.log(`Sent group call invite to user ${user.id}`, groupCallRequest);
        } else {
          console.warn(`Cannot send invite to user ${user.id}: STOMP client not connected`);
        }
      });
  };

  const initializePeerConnection = async (userId) => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      localAudioRef.current.srcObject = stream;

      const iceServers = [
        { urls: 'stun:stun.l.google.com:19302' },
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
        if (stream) {
          const audioElement = document.createElement('audio');
          audioElement.srcObject = stream;
          audioElement.autoplay = true;
          remoteAudioRefs.current.set(userId, audioElement);
          document.body.appendChild(audioElement);
          console.log(`Remote audio track added for user ${userId}`);
        }
      };

      peerConnection.oniceconnectionstatechange = () => {
        console.log(`ICE Connection State for ${userId}: ${peerConnection.iceConnectionState}`);
        if (peerConnection.iceConnectionState === 'failed') {
          console.error(`WebRTC connection failed for user ${userId}`);
          toast.error(`Connection failed for user ${callParticipants.find((u) => u.id === Number(userId))?.username || `User ${userId}`}`);
          endCall();
        } else if (peerConnection.iceConnectionState === 'disconnected') {
          console.warn(`WebRTC connection disconnected for user ${userId}`);
          toast.warn(`User ${callParticipants.find((u) => u.id === Number(userId))?.username || `User ${userId}`} disconnected`);
        }
      };

      peerConnection.onicecandidate = (event) => {
        if (event.candidate) {
          sendSignal({
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
          });
        }
      };

      stream.getTracks().forEach((track) => peerConnection.addTrack(track, stream));
      console.log(`PeerConnection initialized for user ${userId}`);
      return peerConnection;
    } catch (error) {
      console.error(`Error initializing PeerConnection for user ${userId}:`, error);
      toast.error('Failed to access microphone.');
      endCall();
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
          const peerConnection = await initializePeerConnection(String(user.id));
          if (isInitiator && peerConnection) {
            const offer = await peerConnection.createOffer();
            await peerConnection.setLocalDescription(offer);
            sendSignal({
              from: currentUserId,
              to: String(user.id),
              signal: { type: 'offer', sdp: offer.sdp },
            });
            console.log(`Sent offer to user ${user.id}`);
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
    if (signalMessage.from === currentUserId) return;

    const { signal, from, to } = signalMessage;
    if (to && to !== currentUserId && to !== null) return;

    try {
      switch (signal.type) {
        case 'groupCallInvite':
          if (!isInitiator) {
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

            // Инициализация WebSocket
            initializeWebSocket();

            // Ждем подключения WebSocket
            let attempts = 0;
            const maxAttempts = 50; // 5 секунд (50 * 100ms)
            const waitForConnection = async () => {
              while (!stompClient.current?.connected && attempts < maxAttempts) {
                console.log('Waiting for WebSocket connection... Attempt:', attempts + 1);
                await new Promise((resolve) => setTimeout(resolve, 100));
                attempts++;
              }
              if (!stompClient.current?.connected) {
                console.error('WebSocket failed to connect after', maxAttempts, 'attempts for room:', roomId.current);
                toast.error('Failed to connect to WebSocket.');
                navigate('/');
                return false;
              }
              return true;
            };

            if (await waitForConnection()) {
              // Отправляем сигналы acceptCall и ready
              await sendSignal({ from: currentUserId, signal: { type: 'acceptCall' } });
              await sendSignal({ from: currentUserId, to: signal.initiatorId, signal: { type: 'ready' } });
              // Инициализируем PeerConnection
              await initializePeerConnection(signal.initiatorId);
              console.log('Processed groupCallInvite: sent acceptCall, ready, and initialized PeerConnection for initiator', signal.initiatorId);
            }
          }
          break;

        case 'acceptCall':
          if (isInitiator) {
            const userName = callParticipants.find((u) => u.id === Number(from))?.username || `User ${from}`;
            toast.success(`${userName} accepted the group call`);
          }
          break;

        case 'ready':
          if (isInitiator) {
            setReadyUsers((prev) => {
              if (prev.has(from)) {
                console.log(`Ignoring duplicate ready signal from user ${from}`);
                return prev;
              }
              const newSet = new Set([...prev, from]);
              console.log('Ready users updated:', Array.from(newSet));
              return newSet;
            });
            const userName = callParticipants.find((u) => u.id === Number(from))?.username || `User ${from}`;
            toast.success(`${userName} is ready`);
            console.log(`User ${userName} is ready`);
            if (!isCallStarted) {
              setIsWaiting(false);
              ringtoneRef.current.pause();
              ringtoneRef.current.currentTime = 0;
              if (callParticipants.length <= 2 || readyUsers.size + 1 >= callParticipants.length - 1) {
                startCall();
                console.log('Call started for ready participants');
              }
            }
          }
          break;

        case 'offer':
          if (!isInitiator) {
            const peerConnection = peerConnections.current.get(from) || (await initializePeerConnection(from));
            await peerConnection.setRemoteDescription(new RTCSessionDescription(signal));
            const answer = await peerConnection.createAnswer();
            await peerConnection.setLocalDescription(answer);
            sendSignal({ from: currentUserId, to: from, signal: { type: 'answer', sdp: answer.sdp } });
            console.log(`Sent answer to user ${from}`);
          }
          break;

        case 'answer':
          if (isInitiator) {
            const peerConnection = peerConnections.current.get(from);
            if (peerConnection && !peerConnection.remoteDescription) {
              await peerConnection.setRemoteDescription(new RTCSessionDescription(signal));
              console.log(`Processed answer from user ${from}`);
            }
          }
          break;

        case 'candidate':
          const { iceCandidate } = signal;
          const peerConnection = peerConnections.current.get(from);
          if (peerConnection && iceCandidate.candidate) {
            await peerConnection.addIceCandidate(
              new RTCIceCandidate({
                candidate: iceCandidate.candidate,
                sdpMid: iceCandidate.sdpMid,
                sdpMLineIndex: Number(iceCandidate.sdpMLineIndex),
              })
            );
            console.log(`Processed ICE candidate from user ${from}`);
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
          if (!hasNotifiedEndCall.current) {
            hasNotifiedEndCall.current = true;
            const userName = callParticipants.find((u) => u.id === Number(from))?.username || `User ${from}`;
            toast.info(`${userName} left the group call`);
            setCallParticipants((prev) => prev.filter((u) => u.id !== Number(from)));
            const peerConnection = peerConnections.current.get(from);
            if (peerConnection) {
              peerConnection.close();
              peerConnections.current.delete(from);
            }
            if (callParticipants.length <= 1) {
              console.log('Last participant left, ending call');
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

  const sendSignal = async (signalMessage) => {
    if (!roomId.current) {
      console.error('Cannot send signal: roomId is null');
      signalQueue.current.push(signalMessage);
      return;
    }
    if (!stompClient.current) {
      console.error('STOMP client is not initialized');
      signalQueue.current.push(signalMessage);
      initializeWebSocket();
      return;
    }
    const maxRetries = 3;
    let retries = 0;

    while (retries < maxRetries && !stompClient.current.connected) {
      console.warn('STOMP client not connected, queuing signal for room:', roomId.current, signalMessage);
      signalQueue.current.push(signalMessage);
      await new Promise((resolve) => setTimeout(resolve, 2000));
      retries++;
    }

    if (stompClient.current.connected) {
      while (signalQueue.current.length > 0) {
        const queuedSignal = signalQueue.current.shift();
        stompClient.current.publish({
          destination: `/app/groupSignal/${roomId.current}`,
          body: JSON.stringify(queuedSignal),
        });
        console.log('Signal sent from queue to /app/groupSignal/' + roomId.current + ':', queuedSignal);
      }
      stompClient.current.publish({
        destination: `/app/groupSignal/${roomId.current}`,
        body: JSON.stringify(signalMessage),
      });
      console.log('Signal sent to /app/groupSignal/' + roomId.current + ':', signalMessage);
    } else {
      console.error('STOMP client still not connected after retries for room:', roomId.current);
      signalQueue.current.push(signalMessage);
      toast.error('Failed to send signal due to connection issues');
      endCall();
    }
  };

  const endCall = () => {
    if (isCallEnded) {
      console.log('Call already ended, skipping endCall');
      return;
    }
    setIsCallEnded(true);
    setIsCallStarted(false);
    setIsWaiting(false);

    if (stompClient.current && stompClient.current.connected) {
      callParticipants
        .filter((u) => u.id !== Number(currentUserId))
        .forEach((user) => {
          sendSignal({ from: currentUserId, to: String(user.id), signal: { type: 'endCall' } });
          const endCallMessage = {
            type: 'endCall',
            roomId: roomId.current,
            initiatorId: initiator.id,
            recipientId: user.id,
          };
          stompClient.current.publish({
            destination: `/app/call/${user.id}`,
            body: JSON.stringify(endCallMessage),
          });
          console.log(`Sent endCall to /app/call/${user.id}:`, endCallMessage);
        });
      stompClient.current.deactivate();
      console.log('WebSocket connection deactivated in endCall.');
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
      <audio ref={localAudioRef} autoPlay muted />
      <button className="group-call-end-button" onClick={endCall}>
        End Call
      </button>
    </div>
  );
};

export default GroupCallActivity;