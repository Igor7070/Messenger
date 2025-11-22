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

  // Загрузка ключей (ОТДЕЛЬНО ДЛЯ SIGN)
  useEffect(() => {
    const loadKeys = async () => {
      if (!currentUserId || !roomId) return;

      await sodium.ready;

      const stored = localStorage.getItem(`signKeyPair_${currentUserId}`);
      let pair;

      if (!stored) {
        console.log(`Generating new SIGN key pair for userId: ${currentUserId}`);
        const newKeyPair = sodium.crypto_sign_keypair();
        const publicKeyBase64 = sodium.to_base64(newKeyPair.publicKey);
        const privateKeyBase64 = sodium.to_base64(newKeyPair.privateKey);
        pair = { publicKey: publicKeyBase64, privateKey: privateKeyBase64 };
        localStorage.setItem(`signKeyPair_${currentUserId}`, JSON.stringify(pair));
      } else {
        pair = JSON.parse(stored);
        console.log(`Loaded existing SIGN keys for userId: ${currentUserId}`);
      }

      setMyKeys(pair);

      // Синхронизация своего публичного ключа
      try {
        await fetch(`https://unlimitedpossibilities12.org/api/public_keys`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: currentUserId, publicKey: pair.publicKey }),
        });
        console.log('SIGN key synced');
      } catch (e) {
        console.error('Sync key error:', e);
      }

      // Загрузка ключа собеседника
      try {
        const targetId = isCaller ? recipientId : incomingCall?.callerId;
        const resp = await fetch(`https://unlimitedpossibilities12.org/api/public_keys/${targetId}`);
        if (resp.ok) {
          const data = await resp.json();
          setPartnerPublicKey(data.publicKey);
          console.log('Partner key loaded — sending keys_ready');
        }
      } catch (e) {
        notify('Не удалось загрузить ключ собеседника');
      }
    };

    loadKeys();
  }, [currentUserId, recipientId, incomingCall, isCaller, navigate, notify]);

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
        console.log('Secure WS connected');

        stompClient.current.subscribe(`/topic/room/${roomId}`, (msg) => {
          try {
            const data = JSON.parse(msg.body);
            handleSignal(data);
          } catch (e) {}
        });

        // ← ОТПРАВЛЯЕМ keys_ready ТОЛЬКО ЗДЕСЬ — когда WS точно подключён!
        sendSignal({ from: currentUserId, signal: { type: 'keys_ready', publicKey: myKeys.publicKey } });

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
  }, [currentUserId, roomId, isCaller, incomingCall]);

  // Отправляем keys_ready как только ключи загружены и WS подключён
  useEffect(() => {
    if (myKeys && stompClient.current?.connected) {
      console.log('Keys ready — sending keys_ready');
      sendSignal({ from: currentUserId, signal: { type: 'keys_ready', publicKey: myKeys.publicKey } });
    }
  }, [myKeys]);

  // Если нет ID — просто покажем ошибку
  if (!currentUserId || !roomId) {
    return (
      <div className="voice-call-container">
        <h2>Ошибка: нет доступа к звонку</h2>
        <button onClick={() => navigate('/')}>На главную</button>
      </div>
    );
  }

  // Подпись и проверка SDP
  const signSdp = (sdp) => {
    console.log('Signing SDP, privateKey length:', sodium.from_base64(myKeys.privateKey).length); // Должно быть 64
    const msg = new TextEncoder().encode(sdp);
    const sig = sodium.crypto_sign_detached(msg, sodium.from_base64(myKeys.privateKey));
    const signature = sodium.to_base64(sig);
    console.log('Signature created (length ok)');
    return signature;
  };

  const verifySdp = (sdp, signature, publicKey) => {
    console.log('Verifying SDP, publicKey length:', sodium.from_base64(publicKey).length); // 32
    try {
      const msg = new TextEncoder().encode(sdp);
      const sig = sodium.from_base64(signature);
      const pk = sodium.from_base64(publicKey);
      const valid = sodium.crypto_sign_verify_detached(sig, msg, pk);
      console.log('Verify result:', valid ? 'SUCCESS' : 'FAILED');
      return valid;
    } catch (e) {
      console.error('Verify error (length/invalid):', e.message);
      return false;
    }
  };

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

    console.log('Creating Secure PC with iceServers');
    peerConnectionRef.current = new RTCPeerConnection({ iceServers, iceTransportPolicy: 'all' });

    peerConnectionRef.current.ontrack = (e) => {
      console.log('Secure ontrack:', e.streams[0]);
      if (remoteAudioRef.current && e.streams[0]) {
        remoteAudioRef.current.srcObject = e.streams[0];
        remoteAudioRef.current.play().catch((err) => console.error('Secure audio play error:', err));
      }
    };

    peerConnectionRef.current.oniceconnectionstatechange = () => {
      const state = peerConnectionRef.current?.iceConnectionState;
      console.log('Secure ICE state:', state);
      if (state === 'failed') {
        notify('Соединение не удалось');
        endCall();
      } else if (state === 'connected') {
        console.log('Secure WebRTC connected');
      }
    };

    peerConnectionRef.current.onicecandidate = (e) => {
      console.log('Secure onicecandidate:', e.candidate ? 'candidate' : 'end');
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

    peerConnectionRef.current.onicegatheringstatechange = () => {
      console.log('Secure ICE gathering state:', peerConnectionRef.current.iceGatheringState);
    };
  };

  const handleIncomingSecureCall = async () => {
    console.log('Secure handling incoming call');
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      localAudioRef.current.srcObject = stream;
      console.log('Secure local stream obtained');

      createPeerConnection();
      stream.getTracks().forEach(t => peerConnectionRef.current.addTrack(t, stream));
      console.log('Secure tracks added');

      // Отправляем ТОЛЬКО ready — keys_ready уже отправлен в useEffect!
      sendSignal({ from: currentUserId, signal: { type: 'ready' } });

    } catch (error) {
      console.error('Secure media error:', error);
      notify('Failed to access microphone.');
      endCall();
    }
  };

  const startSecureCall = async () => {
    if (!myKeys) {
      console.error('No myKeys for signing');
      endCall();
      return;
    }
    console.log('Secure starting call');
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      localAudioRef.current.srcObject = stream;
      console.log('Secure local stream obtained');
      createPeerConnection();
      stream.getTracks().forEach(t => peerConnectionRef.current.addTrack(t, stream));
      console.log('Secure tracks added');

      const offer = await peerConnectionRef.current.createOffer();
      await peerConnectionRef.current.setLocalDescription(offer);
      console.log('Secure offer created/set');

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
    } catch (error) {
      console.error('Secure start call error:', error);
      notify('Failed to start secure call.');
      endCall();
    }
  };

  const handleSignal = async (msg) => {
    console.log('Secure handling signal:', JSON.stringify(msg.signal, null, 2));
    const s = msg.signal;

    // НОВАЯ СТРОЧКА — ждём, пока собеседник подтвердит, что у него есть наш ключ
    if (s.type === 'keys_ready' && isCaller) {
      console.log('Partner confirmed keys — starting secure offer');
      startSecureCall();
      return;
    }

    if (s.type === 'acceptCall' && isCaller) {
      console.log('Secure received acceptCall');
      if (!hasNotifiedAcceptCall.current) {
        hasNotifiedAcceptCall.current = true;
        notify(`${recipientName} accepted the call.`);
      }
      return;
    // ← оставляем return, как было
    }

    if (s.type === 'offer') {
      console.log('Secure handling offer');
      const publicKeyToUse = s.publicKey || partnerPublicKey;
      if (!publicKeyToUse) {
        console.error('No public key for verify');
        endCall();
        return;
      }
      if (!verifySdp(s.sdp, s.signature, publicKeyToUse)) {
        console.error('Secure verify FAILED');
        setSecureStatus('compromised');
        notify('АТАКА! Подпись не совпадает');
        endCall();
        return;
      }

      // ← СОЗДАЁМ PC, ЕСЛИ ЕГО ЕЩЁ НЕТ!
      if (!peerConnectionRef.current) {
        createPeerConnection();
        const stream = localAudioRef.current?.srcObject;
        if (stream) {
          stream.getTracks().forEach(t => peerConnectionRef.current.addTrack(t, stream));
        }
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
      console.log('Secure handling answer');
      const publicKeyToUse = s.publicKey || partnerPublicKey;
      if (!publicKeyToUse) {
        console.error('No public key for verify');
        endCall();
        return;
      }
      if (!verifySdp(s.sdp, s.signature, publicKeyToUse)) {
        console.error('Secure verify FAILED');
        setSecureStatus('compromised');
        notify('АТАКА! Ответ подделан');
        endCall();
        return;
      }
      console.log('Secure verify SUCCESS');

      try {
        await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: s.sdp }));
        setSecureStatus('secure');
      } catch (e) {
        console.error('Secure answer processing error:', e);
        endCall();
      }

    } else if (s.type === 'candidate' && peerConnectionRef.current) {
      console.log('Secure handling candidate');
      setTimeout(async () => {
        try {
          await peerConnectionRef.current.addIceCandidate(new RTCIceCandidate(s.iceCandidate));
          console.log('Secure candidate added');
        } catch (e) {
          console.error('Secure add candidate error:', e);
        }
      }, 100);

    } else if (s.type === 'ready' && isCaller) {
      console.log('Secure received ready — waiting for keys_ready');
      setIsWaiting(false);
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
      // НЕ запускаем startSecureCall() здесь! Ждём keys_ready

    } else if (s.type === 'endCall') {
      console.log('Secure endCall received');
      if (!hasNotifiedEndCall.current) {
        hasNotifiedEndCall.current = true;
        notify(`${isCaller ? recipientName : callerName} завершил звонок`);
      }
      terminationInitiator = true;
      endCall();

    } else if (s.type === 'rejectCall' && isCaller) {
      console.log('Secure rejectCall received');
      if (!hasNotifiedRejectCall.current) {
        hasNotifiedRejectCall.current = true;
        setIsWaiting(false);
        ringtoneRef.current.pause();
        ringtoneRef.current.currentTime = 0;
        notify(`${recipientName} rejected the call.`);
        terminationInitiator = true;
        endCall();
      }
    }
  };

  const sendSignal = (data) => {
    console.log('Secure sending signal:', JSON.stringify(data, null, 2));
    if (stompClient.current?.connected) {
      stompClient.current.publish({
        destination: `/app/signal/${roomId}`,
        body: JSON.stringify(data),
      });
      console.log('Secure signal sent');
    } else {
      console.error('Secure: WS not connected');
    }
  };

  const endCall = async () => {
    console.log('Secure endCall called');
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
      let status = hasNotifiedRejectCall.current ? 'rejected' : 'completed';
      await axios.post(`https://unlimitedpossibilities12.org/api/calls?userId=${currentUserId}`, {
        caller: isCaller ? currentUserId : incomingCall?.callerId,
        receiver: isCaller ? recipientId : currentUserId,
        direction: isCaller ? 'outgoing' : 'incoming',
        status,
        callType: 'voice_secure',
        timestamp: new Date().toISOString(),
        roomId,
      });
      console.log(`Secure call saved: ${status}`);
    } catch (e) {
      console.error('Secure save error:', e);
    }

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