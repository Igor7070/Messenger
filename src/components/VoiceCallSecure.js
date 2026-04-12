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
      const stored = localStorage.getItem(`signKeyPair_${currentUserId}`); // ОТДЕЛЬНО ОТ BOX-КЛЮЧЕЙ ЧАТА
      let pair;
      if (!stored) {
        console.log(`Generating new SIGN key pair for userId: ${currentUserId}`);
        const newKeyPair = sodium.crypto_sign_keypair(); // Ed25519 для sign (64/32 bytes)
        const publicKeyBase64 = sodium.to_base64(newKeyPair.publicKey);
        const privateKeyBase64 = sodium.to_base64(newKeyPair.privateKey);
        pair = {
          publicKey: publicKeyBase64,
          privateKey: privateKeyBase64
        };
        localStorage.setItem(`signKeyPair_${currentUserId}`, JSON.stringify(pair));
        notify('Signing keys have been generated and synchronized');
        try {
          console.log(`Syncing SIGN public key for userId: ${currentUserId}`);
          const syncResponse = await fetch(`https://unlimitedpossibilities12.org/api/public_keys`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: currentUserId, publicKey: publicKeyBase64 }),
          });
          if (!syncResponse.ok) throw new Error(`Sync failed: ${syncResponse.status}`);
          console.log(`SIGN key synced for userId: ${currentUserId}`);
        } catch (syncError) {
          console.error('Sync error:', syncError);
          notify('Signature key synchronization failed - try restarting the call');
        }
      } else {
        pair = JSON.parse(stored);
        console.log(`Loaded existing SIGN keys for userId: ${currentUserId}, private length: ${sodium.from_base64(pair.privateKey).length}`); // Должно быть 64
        // Синхронизируем существующие ключи (на случай обновления)
        try {
          console.log(`Syncing existing SIGN public key for userId: ${currentUserId}`);
          const syncResponse = await fetch(`https://unlimitedpossibilities12.org/api/public_keys`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: currentUserId, publicKey: pair.publicKey }),
          });
          if (!syncResponse.ok) throw new Error(`Sync failed: ${syncResponse.status}`);
          console.log(`Existing SIGN key synced for userId: ${currentUserId}`);
          notify('Signing keys have been updated and synchronized');
        } catch (syncError) {
          console.error('Sync error:', syncError);
          notify('Signature key synchronization failed - try restarting the call');
        }
      }
      setMyKeys(pair);

      try {
        const targetId = isCaller ? recipientId : incomingCall?.callerId;
        console.log(`Fetching partner SIGN public key for targetId: ${targetId}`);
        const resp = await fetch(`https://unlimitedpossibilities12.org/api/public_keys/${targetId}`);
        if (resp.ok) {
          const data = await resp.json();
          setPartnerPublicKey(data.publicKey);
          console.log(`Partner SIGN public key fetched: length ${sodium.from_base64(data.publicKey).length}`); // 32 bytes
        } else {
          console.error(`Fetch partner key failed: ${resp.status}`);
          notify('Failed to load the other party s signature key');
        }
      } catch (e) {
        console.error('Error fetching partner key:', e);
        notify('Failed to load the other party s signature key');
      }
    };
    loadKeys();
  }, [currentUserId, recipientId, incomingCall, isCaller, navigate, notify]);

  // WebSocket
  useEffect(() => {
    if (!myKeys || !currentUserId || !roomId) {
      console.log('Secure WS setup skipped: missing myKeys/currentUserId/roomId');
      return;
    }

    console.log('Initializing Secure WS');
    const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
    stompClient.current = new Client({
      webSocketFactory: () => socket,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        console.log('Secure WS connected');
        stompClient.current.subscribe(`/topic/room/${roomId}`, (msg) => {
          console.log('Secure WS received:', msg.body);
          try {
            const data = JSON.parse(msg.body);
            handleSignal(data);
          } catch (e) {
            console.error('Secure WS parse error:', e);
          }
        });

        if (!isCaller && incomingCall) {
          console.log('Secure: handling incoming call');
          handleIncomingSecureCall();
        }
      },
      onStompError: (frame) => {
        console.error('Secure STOMP error:', frame);
        notify('STOMP protocol error.');
      },
      onWebSocketError: (error) => {
        console.error('Secure WS error:', error);
        notify('WebSocket connection error');
      },
      onDisconnect: () => {
        console.log('Secure WS disconnected');
      },
    });
    if (!stompClient.current.connected) {
      stompClient.current.activate();
    }

    if (isCaller) {
      console.log('Secure caller: starting ringtone');
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
      if (stompClient.current) stompClient.current.deactivate();
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
    };
  }, [myKeys, roomId, isCaller]); // Убрал incomingCall — redundant

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
        notify('Connection failed');
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
    // КРИТИЧЕСКИ ВАЖНО: игнорируем свои собственные сообщения (эхо от STOMP)
    if (msg.from == currentUserId) {
      return;
    }

    console.log('Secure handling signal:', JSON.stringify(msg.signal, null, 2));
    const s = msg.signal;

    // acceptCall — только звонящий
    if (s.type === 'acceptCall' && isCaller) {
      if (!hasNotifiedAcceptCall.current) {
        hasNotifiedAcceptCall.current = true;
        notify(`${recipientName} accepted the call.`);
      }
      return;
    }

    // offer — только принимающий (не звонящий!)
    if (s.type === 'offer' && !isCaller) {
      console.log('Secure handling offer (callee)');
      
      const publicKeyToUse = s.publicKey || partnerPublicKey;
      if (!publicKeyToUse || !s.signature) {
        console.error('Missing key or signature in offer');
        setSecureStatus('compromised');
        notify('ATTACK! Missing key or signature');
        endCall();
        return;
      }

      if (!verifySdp(s.sdp, s.signature, publicKeyToUse)) {
        console.error('Secure verify FAILED (offer)');
        setSecureStatus('compromised');
        notify('ATTACK! The offer signature does not match');
        endCall();
        return;
      }

      console.log('Secure verify SUCCESS (offer)');

      try {
        await peerConnectionRef.current.setRemoteDescription(
          new RTCSessionDescription({ type: 'offer', sdp: s.sdp })
        );

        const answer = await peerConnectionRef.current.createAnswer();
        await peerConnectionRef.current.setLocalDescription(answer);

        const answerSig = signSdp(answer.sdp);

        sendSignal({
          from: currentUserId,
          signal: {
            type: 'answer',
            sdp: answer.sdp,
            signature: answerSig,
            publicKey: myKeys.publicKey,
          },
        });

        setSecureStatus('secure');
        if (!hasNotifiedAcceptCall.current) {
          hasNotifiedAcceptCall.current = true;
          notify(`${recipientName} accepted the call.`);
        }
      } catch (e) {
        console.error('Secure offer processing error:', e);
        endCall();
      }

      return;
    }

    // answer — только звонящий!
    if (s.type === 'answer' && isCaller) {
      console.log('Secure handling answer (caller)');

      const publicKeyToUse = s.publicKey || partnerPublicKey;
      if (!publicKeyToUse || !s.signature) {
        console.error('Missing key or signature in answer');
        setSecureStatus('compromised');
        notify('ATTACK! Unsigned response');
        endCall();
        return;
      }

      if (!verifySdp(s.sdp, s.signature, publicKeyToUse)) {
        console.error('Secure verify FAILED (answer)');
        setSecureStatus('compromised');
        notify('ATTACK! Response signature does not match');
        endCall();
        return;
      }

      console.log('Secure verify SUCCESS (answer)');

      try {
        await peerConnectionRef.current.setRemoteDescription(
          new RTCSessionDescription({ type: 'answer', sdp: s.sdp })
        );
        setSecureStatus('secure');
      } catch (e) {
        console.error('Failed to set remote answer:', e);
        endCall();
      }

      return;
    }

    // ICE candidate
    if (s.type === 'candidate' && peerConnectionRef.current) {
      setTimeout(async () => {
        try {
          await peerConnectionRef.current.addIceCandidate(
            new RTCIceCandidate(s.iceCandidate)
          );
        } catch (e) {
          console.error('Add ICE candidate error:', e);
        }
      }, 100);
      return;
    }

    // ready — только звонящий
    if (s.type === 'ready' && isCaller) {
      setIsWaiting(false);
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
      setTimeout(() => startSecureCall(), 1000);
      return;
    }

    // endCall
    if (s.type === 'endCall') {
      if (!hasNotifiedEndCall.current) {
        hasNotifiedEndCall.current = true;
        notify(`${isCaller ? recipientName : callerName} ended the call`);
      }
      terminationInitiator = true;
      endCall();
      return;
    }

    // rejectCall
    if (s.type === 'rejectCall' && isCaller) {
      if (!hasNotifiedRejectCall.current) {
        hasNotifiedRejectCall.current = true;
        setIsWaiting(false);
        ringtoneRef.current.pause();
        ringtoneRef.current.currentTime = 0;
        notify(`${recipientName} rejected the call`);
      }
      terminationInitiator = true;
      endCall();
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
        <h2>Secure call with {isCaller ? recipientName : callerName}</h2>
        <div style={{
          fontSize: '18px',
          fontWeight: 'bold',
          color: secureStatus === 'secure' ? '#00ff00' : secureStatus === 'compromised' ? '#ff0000' : '#ffaa00',
          marginTop: '10px'
        }}>
          {secureStatus === 'checking' && 'Encryption verification...'}
          {secureStatus === 'secure' && 'End-to-end encryption is active'}
          {secureStatus === 'compromised' && 'ATTACK! CALL UNSAFE'}
        </div>
      </div>

      <audio ref={localAudioRef} autoPlay muted />
      <audio ref={remoteAudioRef} autoPlay />

      <div className="chart-container">
        <Line data={chartData} options={{ responsive: true, maintainAspectRatio: false }} />
      </div>

      <button className="end-call-button-voice" onClick={endCall}>
        End Call
      </button>
    </div>
  );
};

export default VoiceCallSecure;