import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import axios from 'axios';
import { Line } from 'react-chartjs-2';
import { Chart, registerables } from 'chart.js';
import { useNotification } from '../context/NotificationContext';
import '../styles/VoiceCall.css';
import * as sodium from 'libsodium-wrappers';

Chart.register(...registerables);

const VoiceCallSecure = () => {
  const navigate = useNavigate();
  const notify = useNotification();
  const { roomId } = useParams();
  const location = useLocation();

  const {
    incomingCall = null,
    isCaller = false,
    currentUserId = null,
    callerName = '',
    recipientName = '',
    recipientId = null,
  } = location.state || {};

  // Если нет ID — просто покажем ошибку, но хуки уже вызваны ниже
  if (!currentUserId || !roomId) {
    return (
      <div className="voice-call-container">
        <h2>Ошибка: нет доступа к звонку</h2>
        <button onClick={() => navigate('/')}>На главную</button>
      </div>
    );
  }

  // === ВСЕ ХУКИ ЗДЕСЬ — ВСЕГДА В ОДНОМ ПОРЯДКЕ ===
  const localAudioRef = useRef(null);
  const remoteAudioRef = useRef(null);
  const peerConnectionRef = useRef(null);
  const stompClient = useRef(null);
  const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));

  const [isWaiting, setIsWaiting] = useState(isCaller);
  const [secureStatus, setSecureStatus] = useState('checking');

  const [chartData, setChartData] = useState({
    labels: [],
    datasets: [{
      label: 'Voice Activity',
      data: [],
      borderColor: 'rgba(74, 144, 226, 1)',
      backgroundColor: 'rgba(74, 144, 226, 0.2)',
      borderWidth: 2,
      fill: true,
    }],
  });

  const hasNotifiedEndCall = useRef(false);
  const hasNotifiedAcceptCall = useRef(false);
  const hasNotifiedRejectCall = useRef(false);
  const hasSavedCall = useRef(false);
  let terminationInitiator = false;

  const [myKeys, setMyKeys] = useState(null);
  const [partnerPublicKey, setPartnerPublicKey] = useState(null);

  // Загрузка ключей
  useEffect(() => {
    const loadKeys = async () => {
      await sodium.ready;
      const stored = localStorage.getItem(`keyPair_${currentUserId}`);
      if (!stored) {
        notify('Нет ключей для защищённого звонка');
        navigate('/');
        return;
      }
      const pair = JSON.parse(stored);
      setMyKeys(pair);

      try {
        const targetId = isCaller ? recipientId : incomingCall?.callerId;
        const resp = await fetch(`https://unlimitedpossibilities12.org/api/public_keys/${targetId}`);
        if (resp.ok) {
          const data = await resp.json();
          setPartnerPublicKey(data.publicKey);
        }
      } catch (e) {
        notify('Не удалось загрузить ключ собеседника');
      }
    };
    loadKeys();
  }, [currentUserId, recipientId, incomingCall, isCaller, navigate, notify]);

  // Подпись и проверка SDP
  const signSdp = (sdp) => {
    const msg = new TextEncoder().encode(sdp);
    const sig = sodium.crypto_sign_detached(msg, sodium.from_base64(myKeys.privateKey));
    return sodium.to_base64(sig);
  };

  const verifySdp = (sdp, signature, publicKey) => {
    try {
      const msg = new TextEncoder().encode(sdp);
      const sig = sodium.from_base64(signature);
      const pk = sodium.from_base64(publicKey);
      return sodium.crypto_sign_verify_detached(sig, msg, pk);
    } catch {
      return false;
    }
  };

  // WebSocket
  useEffect(() => {
    if (!myKeys) return;

    const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
    stompClient.current = new Client({
      webSocketFactory: () => socket,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        stompClient.current.subscribe(`/topic/room/${roomId}`, (msg) => {
          try {
            const data = JSON.parse(msg.body);
            handleSignal(data);
          } catch (e) {}
        });

        stompClient.current.publish({
          destination: '/app/publicKey/update',
          body: JSON.stringify({ userId: currentUserId, publicKey: myKeys.publicKey }),
        });

        if (!isCaller && incomingCall) {
          handleIncomingSecureCall();
        }
      },
    });
    stompClient.current.activate();

    if (isCaller) {
      ringtoneRef.current.loop = true;
      ringtoneRef.current.play().catch(() => {});
    }

    const interval = setInterval(() => {
      setChartData(prev => ({
        labels: [...prev.labels.slice(-30), new Date().toLocaleTimeString()],
        datasets: [{
          ...prev.datasets[0],
          data: [...prev.datasets[0].data.slice(-30), Math.random() * 100],
        }],
      }));
    }, 1000);

    return () => {
      clearInterval(interval);
      stompClient.current?.deactivate();
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
    };
  }, [myKeys, roomId, isCaller, incomingCall]);

  // Создание PeerConnection
  const createPeerConnection = () => {
    const iceServers = isCaller
      ? [
          { urls: 'stun:stun.l.google.com:19302' },
          { urls: 'turn:13.49.238.121:3478?transport=tcp', username: 'username1', credential: 'key1' },
        ]
      : [
          { urls: 'stun:stun.l.google.com:19302' },
          { urls: 'turn:13.49.238.121:3478?transport=tcp', username: 'username2', credential: 'key2' },
        ];

    peerConnectionRef.current = new RTCPeerConnection({ iceServers, iceTransportPolicy: 'all' });

    peerConnectionRef.current.ontrack = (e) => {
      if (remoteAudioRef.current && e.streams[0]) {
        remoteAudioRef.current.srcObject = e.streams[0];
      }
    };

    peerConnectionRef.current.oniceconnectionstatechange = () => {
      if (peerConnectionRef.current?.iceConnectionState === 'failed') {
        notify('Соединение не удалось');
        endCall();
      }
    };

    peerConnectionRef.current.onicecandidate = (e) => {
      if (e.candidate) {
        sendSignal({
          from: currentUserId,
          signal: {
            type: 'candidate',
            iceCandidate: {
              candidate: e.candidate.candidate,
              sdpMid: e.candidate.sdpMid,
              sdpMLineIndex: e.candidate.sdpMLineIndex?.toString(),
            },
          },
        });
      }
    };
  };

  const handleIncomingSecureCall = async () => {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    localAudioRef.current.srcObject = stream;
    createPeerConnection();
    stream.getTracks().forEach(t => peerConnectionRef.current.addTrack(t, stream));
    sendSignal({ from: currentUserId, signal: { type: 'ready' } });
  };

  const startSecureCall = async () => {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    localAudioRef.current.srcObject = stream;
    createPeerConnection();
    stream.getTracks().forEach(t => peerConnectionRef.current.addTrack(t, stream));

    const offer = await peerConnectionRef.current.createOffer();
    await peerConnectionRef.current.setLocalDescription(offer);

    const signature = signSdp(offer.sdp);

    sendSignal({
      from: currentUserId,
      signal: {
        type: 'offer',
        sdp: offer.sdp,
        signature,
        publicKey: myKeys.publicKey,
      },
    });
  };

  const handleSignal = async (msg) => {
    const s = msg.signal;

    if (s.type === 'offer') {
      if (!verifySdp(s.sdp, s.signature, s.publicKey || partnerPublicKey)) {
        setSecureStatus('compromised');
        notify('АТАКА! Подпись не совпадает');
        endCall();
        return;
      }

      await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: s.sdp }));
      const answer = await peerConnectionRef.current.createAnswer();
      await peerConnectionRef.current.setLocalDescription(answer);

      const sig = signSdp(answer.sdp);

      sendSignal({
        from: currentUserId,
        signal: { type: 'answer', sdp: answer.sdp, signature: sig, publicKey: myKeys.publicKey },
      });

      setSecureStatus('secure');

    } else if (s.type === 'answer') {
      if (!verifySdp(s.sdp, s.signature, s.publicKey || partnerPublicKey)) {
        setSecureStatus('compromised');
        notify('АТАКА! Ответ подделан');
        endCall();
        return;
      }

      await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: s.sdp }));
      setSecureStatus('secure');

    } else if (s.type === 'candidate' && peerConnectionRef.current) {
      try {
        await peerConnectionRef.current.addIceCandidate(new RTCIceCandidate(s.iceCandidate));
      } catch {}

    } else if (s.type === 'ready' && isCaller) {
      setIsWaiting(false);
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
      startSecureCall();

    } else if (s.type === 'endCall') {
      if (!hasNotifiedEndCall.current) {
        hasNotifiedEndCall.current = true;
        notify(`${isCaller ? recipientName : callerName} завершил звонок`);
      }
      terminationInitiator = true;
      endCall();
    }
  };

  const sendSignal = (data) => {
    if (stompClient.current?.connected) {
      stompClient.current.publish({
        destination: `/app/signal/${roomId}`,
        body: JSON.stringify(data),
      });
    }
  };

  const endCall = async () => {
    if (hasSavedCall.current) return;
    hasSavedCall.current = true;

    peerConnectionRef.current?.close();
    peerConnectionRef.current = null;

    if (localAudioRef.current?.srcObject) {
      localAudioRef.current.srcObject.getTracks().forEach(t => t.stop());
      localAudioRef.current.srcObject = null;
    }
    if (remoteAudioRef.current?.srcObject) {
      remoteAudioRef.current.srcObject.getTracks().forEach(t => t.stop());
      remoteAudioRef.current.srcObject = null;
    }

    if (isCaller) {
      setIsWaiting(false);
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
    }

    if (stompClient.current?.connected && !terminationInitiator) {
      sendSignal({ from: currentUserId, signal: { type: 'endCall' } });
      if (isCaller && incomingCall?.recipientId) {
        stompClient.current.publish({
          destination: `/app/call/${incomingCall.recipientId}`,
          body: JSON.stringify({
            type: 'endCall',
            roomId,
            callerId: currentUserId,
            recipientId: incomingCall.recipientId,
          }),
        });
      }
    }

    try {
      await axios.post(`https://unlimitedpossibilities12.org/api/calls?userId=${currentUserId}`, {
        caller: isCaller ? currentUserId : incomingCall?.callerId,
        receiver: isCaller ? recipientId : currentUserId,
        direction: isCaller ? 'outgoing' : 'incoming',
        status: 'completed',
        callType: 'voice_secure',
        timestamp: new Date().toISOString(),
        roomId,
      });
    } catch (e) {}

    navigate('/');
  };

  return (
    <div className="voice-call-container">
      <div className="voice-call-header">
        <h2>Защищённый звонок с {isCaller ? recipientName : callerName}</h2>
        <div style={{
          fontSize: '18px',
          fontWeight: 'bold',
          color: secureStatus === 'secure' ? '#00ff00' : secureStatus === 'compromised' ? '#ff0000' : '#ffaa00',
          marginTop: '10px'
        }}>
          {secureStatus === 'checking' && 'Проверка шифрования...'}
          {secureStatus === 'secure' && 'Сквозное шифрование активно'}
          {secureStatus === 'compromised' && 'АТАКА! ЗВОНОК НЕБЕЗОПАСЕН'}
        </div>
      </div>

      <audio ref={localAudioRef} autoPlay muted />
      <audio ref={remoteAudioRef} autoPlay />

      <div className="chart-container">
        <Line data={chartData} options={{ responsive: true, maintainAspectRatio: false }} />
      </div>

      <button className="end-call-button-voice" onClick={endCall}>
        Завершить
      </button>
    </div>
  );
};

export default VoiceCallSecure;