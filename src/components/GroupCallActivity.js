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
  const { participants = [], initiator = {} } = state || {};
  const isInitiator = Number(localStorage.getItem('userId')) === initiator.id;
  const currentUserId = localStorage.getItem('userId');
  const roomId = useRef(crypto.randomUUID());
  const peerConnections = useRef(new Map());
  const localAudioRef = useRef(null);
  const remoteAudioRefs = useRef(new Map());
  const stompClient = useRef(null);
  const [callParticipants, setCallParticipants] = useState(participants);
  const [callDuration, setCallDuration] = useState('00:00');
  const [startTime, setStartTime] = useState('');
  const [currentTime, setCurrentTime] = useState('');
  const [readyUsers, setReadyUsers] = useState(new Set());
  const [isWaiting, setIsWaiting] = useState(isInitiator);
  const [isCallStarted, setIsCallStarted] = useState(false);
  const [isCallEnded, setIsCallEnded] = useState(false);
  const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));
  const callStartTime = useRef(null);
  const hasNotifiedEndCall = useRef(false);

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
    if (!currentUserId || !initiator.id) {
      toast.error('User ID or initiator ID is missing.');
      navigate('/');
      return;
    }

    if (isInitiator) {
      setIsWaiting(true);
      ringtoneRef.current.loop = true;
      ringtoneRef.current.play().catch((error) => console.error('Error playing ringtone:', error));
    }

    // Инициализация WebSocket
    const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
    stompClient.current = new Client({
      webSocketFactory: () => socket,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        console.log('Connected to WebSocket for room:', roomId.current);
        const subscription = stompClient.current.subscribe(`/topic/room/${roomId.current}`, (message) => {
          try {
            const signalMessage = JSON.parse(message.body);
            console.log('Received signal on /topic/room/' + roomId.current + ':', signalMessage);
            handleSignal(signalMessage);
          } catch (error) {
            console.error('Error parsing WebSocket message:', error);
          }
        });

        if (isInitiator) {
          sendGroupCallInvite();
        }

        return () => {
          console.log('Unsubscribing from /topic/room/' + roomId.current);
          subscription.unsubscribe();
        };
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame);
        toast.error('STOMP protocol error.');
      },
      onWebSocketError: (error) => {
        console.warn('WebSocket error:', {
          error,
          timestamp: new Date().toISOString(),
          route: window.location.pathname,
          roomId: roomId.current,
        });
        // Не завершаем звонок автоматически при ошибке WebSocket
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected for room:', roomId.current);
      },
    });
    stompClient.current.activate();

    // Таймер звонка
    callStartTime.current = Date.now();
    const timerInterval = setInterval(() => {
      const duration = Math.floor((Date.now() - callStartTime.current) / 1000);
      const minutes = Math.floor(duration / 60);
      const seconds = duration % 60;
      setCallDuration(`${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`);
    }, 1000);

    // Время начала и текущее время
    const timeInterval = setInterval(() => {
      const now = new Date();
      setCurrentTime(now.toLocaleTimeString('en-US', { hour12: false }));
      if (!startTime) {
        setStartTime(now.toLocaleTimeString('en-US', { hour12: false }));
      }
    }, 1000);

    // Анимация графика (заглушка)
    const chartInterval = setInterval(() => {
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
      if (isInitiator && isWaiting && readyUsers.size === 0) {
        console.warn('No participants responded within 30 seconds, ending call.');
        toast.error('No participants responded to the call.');
        endCall();
      }
    }, 30000);

    return () => {
      clearInterval(timerInterval);
      clearInterval(timeInterval);
      clearInterval(chartInterval);
      clearTimeout(readyTimeout);
      // Останавливаем гудок
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
      // Вызываем endCall только если звонок был завершен явно
      if (isCallEnded) {
        if (stompClient.current) stompClient.current.deactivate();
      }
    };
  }, [currentUserId, initiator.id, isInitiator, navigate, startTime, isCallEnded, isWaiting, readyUsers]);

  const sendGroupCallInvite = () => {
    const groupCallRequest = {
      type: 'group',
      initiatorId: String(initiator.id),
      initiatorName: initiator.username,
      roomId: roomId.current,
      recipientIds: participants
        .filter((p) => p.id !== initiator.id)
        .map((p) => String(p.id)),
    };

    participants
      .filter((p) => p.id !== initiator.id)
      .forEach((user) => {
        stompClient.current.publish({
          destination: `/app/groupCall/${user.id}`,
          body: JSON.stringify(groupCallRequest),
        });
        console.log(`Sent group call invite to user ${user.id}`);
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
          toast.error(`Connection failed for user ${userId}.`);
          endCall();
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
    if (isCallStarted) return;
    setIsCallStarted(true);

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
        }
      }
    }
  };

  const handleSignal = async (signalMessage) => {
    if (signalMessage.from === currentUserId) return;

    const { signal, from, to } = signalMessage;
    if (to && to !== currentUserId) return;

    try {
      switch (signal.type) {
        case 'groupCallInvite':
          if (!isInitiator) {
            toast.info(`${signal.initiatorName} invites you to a group call`);
            roomId.current = signal.roomId;
            sendSignal({ from: currentUserId, to: signal.initiatorId, signal: { type: 'acceptCall' } });
            sendSignal({ from: currentUserId, to: signal.initiatorId, signal: { type: 'ready' } });
            await initializePeerConnection(signal.initiatorId);
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
              const newSet = new Set([...prev, from]);
              console.log('Ready users updated:', Array.from(newSet));
              return newSet;
            });
            const userName = callParticipants.find((u) => u.id === Number(from))?.username || `User ${from}`;
            toast.success(`${userName} is ready`);
            console.log(`User ${userName} is ready`);
            if (readyUsers.size + 1 >= callParticipants.length - 1) {
              setIsWaiting(false);
              ringtoneRef.current.pause();
              ringtoneRef.current.currentTime = 0;
              startCall();
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
          }
          break;

        case 'answer':
          if (isInitiator) {
            const peerConnection = peerConnections.current.get(from);
            if (peerConnection && !peerConnection.remoteDescription) {
              await peerConnection.setRemoteDescription(new RTCSessionDescription(signal));
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
          }
          break;

        case 'rejectCall':
          if (isInitiator) {
            const userName = callParticipants.find((u) => u.id === Number(from))?.username || `User ${from}`;
            toast.warn(`${userName} rejected the group call`);
            setCallParticipants((prev) => prev.filter((u) => u.id !== Number(from)));
            peerConnections.current.delete(from);
            if (callParticipants.length <= 1) endCall();
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
            if (callParticipants.length <= 1) endCall();
          }
          break;
      }
    } catch (error) {
      console.error('Error processing signal:', error);
    }
  };

  const sendSignal = (signalMessage) => {
    if (stompClient.current && stompClient.current.connected) {
      stompClient.current.publish({
        destination: `/app/groupSignal/${roomId.current}`,
        body: JSON.stringify(signalMessage),
      });
      console.log('Signal sent:', signalMessage);
    } else {
      console.error('STOMP client not connected');
      toast.error('Connection lost');
    }
  };

  const endCall = () => {
    if (isCallEnded) return;
    setIsCallEnded(true);

    if (stompClient.current && stompClient.current.connected) {
      callParticipants
        .filter((u) => u.id !== Number(currentUserId))
        .forEach((user) => {
          sendSignal({ from: currentUserId, to: String(user.id), signal: { type: 'endCall' } });
          // Отправляем endCall также через /app/call/{userId}
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

    setIsWaiting(false);
    ringtoneRef.current.pause();
    ringtoneRef.current.currentTime = 0;

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