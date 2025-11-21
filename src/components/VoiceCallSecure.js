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

  // Если нет обязательных данных — выходим
  if (!currentUserId || !roomId) {
    return (
      <div className="voice-call-container">
        <h2>Ошибка доступа к звонку</h2>
        <button onClick={() => navigate('/')}>На главную</button>
      </div>
    );
  }

  // Рефы
  const localAudioRef = useRef(null);
  const remoteAudioRef = useRef(null);
  const peerConnectionRef = useRef(null);
  const stompClient = useRef(null);
  const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));

  // Состояние
  const [isWaiting, setIsWaiting] = useState(isCaller);
  const [secureStatus, setSecureStatus] = useState('checking'); // checking | secure | compromised
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
  const hasSavedCall = useRef(false);
  let terminationInitiator = false;

  const [myKeys, setMyKeys] = useState(null);
  const [partnerPublicKey, setPartnerPublicKey] = useState(null);

  // === 1. Загрузка ключей подписи (отдельные от box-чата) ===
  useEffect(() => {
    const loadKeys = async () => {
      await sodium.ready;

      const stored = localStorage.getItem(`signKeyPair_${currentUserId}`);
      let pair;

      if (!stored) {
        console.log('Генерируем новые ключи подписи');
        const newPair = sodium.crypto_sign_keypair();
        pair = {
          publicKey: sodium.to_base64(newPair.publicKey),
          privateKey: sodium.to_base64(newPair.privateKey),
        };
        localStorage.setItem(`signKeyPair_${currentUserId}`, JSON.stringify(pair));
      } else {
        pair = JSON.parse(stored);
      }

      setMyKeys(pair);

      // Синхронизация публичного ключа на сервер (если нужно)
      try {
        await fetch('https://unlimitedpossibilities12.org/api/public_keys', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: currentUserId, publicKey: pair.publicKey }),
        });
      } catch (e) {}

      // Загрузка ключа собеседника
      try {
        const targetId = isCaller ? recipientId : incomingCall?.callerId;
        const resp = await fetch(`https://unlimitedpossibilities12.org/api/public_keys/${targetId}`);
        if (resp.ok) {
          const data = await resp.json();
          setPartnerPublicKey(data.publicKey);
        }
      } catch (e) {
        notify('Не удалось получить ключ собеседника');
      }
    };

    loadKeys();
  }, [currentUserId, isCaller, recipientId, incomingCall, notify]);

  // === 2. WebSocket ===
  useEffect(() => {
    if (!myKeys || !roomId) return;

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

        // Сообщаем, что ключи готовы
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

  // === 3. Создание PeerConnection ===
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
      const state = peerConnectionRef.current?.iceConnectionState;
      if (state === 'connected') setSecureStatus('secure');
      if (state === 'failed') {
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

  // === 4. Принимающий ===
  const handleIncomingSecureCall = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      localAudioRef.current.srcObject = stream;
      createPeerConnection();
      stream.getTracks().forEach(t => peerConnectionRef.current.addTrack(t, stream));

      // Отправляем ready
      sendSignal({ from: currentUserId, signal: { type: 'ready' } });

      // Отправляем keys_ready — чтобы звонищий знал, что ключ загружен
      sendSignal({
        from: currentUserId,
        signal: { type: 'keys_ready', publicKey: myKeys.publicKey },
      });
    } catch (e) {
      notify('Нет доступа к микрофону');
      endCall();
    }
  };

  // === 5. Звонищий — старт только после keys_ready ===
  const startSecureCall = async () => {
    try {
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
    } catch (e) {
      notify('Ошибка запуска звонка');
      endCall();
    }
  };

  // === 6. Обработка сигналов ===
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

    } else if (s.type === 'keys_ready' && isCaller) {
      // Ключ получен — можно начинать offer
      console.log('Ключ собеседника получен — запускаем звонок');
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

  // === 7. Завершение звонка ===
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