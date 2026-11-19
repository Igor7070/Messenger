import React, { useEffect, useRef, useState } from 'react';
import { stompAuthHeaders } from '../authToken';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import axios from 'axios'; // NEW: Добавляем импорт axios
import IncomingCallNotification from './IncomingCallNotification';
import { Line } from 'react-chartjs-2';
import { createCallGuard, CallSecurity } from '../utils/callGuard';
import { Chart, registerables } from 'chart.js';
import { useNotification } from '../context/NotificationContext';
import '../styles/VoiceCall.css';

Chart.register(...registerables);


// Подпись под замком на экране звонка
const securityLabel = (state) => {
  switch (state) {
    case CallSecurity.CONFIRMED:
      return 'Encrypted \u00b7 confirmed by you';
    case CallSecurity.VERIFIED:
      return 'Encrypted \u00b7 verified';
    case CallSecurity.NEW_DEVICE:
      return 'Encrypted \u00b7 new device';
    case CallSecurity.CHANGED:
      return 'The key of a known device has changed';
    case CallSecurity.FAILED:
      return 'Could not verify the other side';
    case CallSecurity.ENCRYPTED:
      return 'Encrypted';
    default:
      return 'Checking the connection\u2026';
  }
};

// Что человеку делать с этим кодом
const securityExplanation = (state) => {
  switch (state) {
    case CallSecurity.CHANGED:
      return 'This device called you before with a different key. That happens after a reinstall — but it is also how an attempt to listen in would look. Compare the code aloud before you say anything private.';
    case CallSecurity.CONFIRMED:
      return 'You have confirmed this device by code. If its key ever changes, you will be warned.';
    case CallSecurity.NEW_DEVICE:
      return 'You have not spoken to this device before, so there was nothing to compare against. Read the code aloud once, and any later swap will show.';
    case CallSecurity.ENCRYPTED:
      return 'The other side did not sign the connection, so we could not confirm who answered. The call is still encrypted.';
    default:
      return 'Voice and video in this call are encrypted between the devices; the server only passes the connection details. The code below is the same on both sides — read it aloud once, and nobody can slip in between you.';
  }
};

const VoiceCall = ({ recipientId, call = {}, minimized = false, onMinimize, onClose }) => {
  const navigate = useNavigate();
  const notify = useNotification();
  const params = useParams();
  const location = useLocation();
  // Окно звонка живёт в каркасе и получает всё свойствами; переход по
  // ссылке остался запасным путём — на случай, если окно открыли ею
  const fromRoute = location.state || {};
  const roomId = call.roomId || params.roomId;
  const incomingCall = call.incomingCall !== undefined ? call.incomingCall : (fromRoute.incomingCall || null);
  const isCaller = call.isCaller !== undefined ? call.isCaller : Boolean(fromRoute.isCaller);
  const currentUserId = call.currentUserId || fromRoute.currentUserId || null;
  const callerName = call.callerName || fromRoute.callerName || '';
  const recipientName = call.recipientName || fromRoute.recipientName || '';
  const localAudioRef = useRef(null);
  const remoteAudioRef = useRef(null);
  const peerConnectionRef = useRef(null);
  const stompClient = useRef(null);
  const [incomingCallState, setIncomingCallState] = useState(incomingCall);
  const [isWaiting, setIsWaiting] = useState(false);
  const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));
  const hasNotifiedEndCall = useRef(false);
  const hasNotifiedAcceptCall = useRef(false); // Новый флаг для acceptCall
  const hasNotifiedRejectCall = useRef(false); // NEW: Добавляем реф для rejectCall
  const hasSavedCall = useRef(false); // NEW: Флаг для предотвращения двойного сохранения звонка
  const [callDuration, setCallDuration] = useState(0);
  /**
   * Собеседник целиком: снимок и выбранное имя.
   *
   * Прежде экран знал только логин, переданный при переходе, и показывал
   * строку «Voice Call with Kenzya3» над полем графика — ни лица, ни имени.
   * На телефоне в это время видно снимок во весь круг, имя и «On the call»,
   * поэтому берём ту же запись о человеке.
   */
  const [partner, setPartner] = useState(null);
  const [muted, setMuted] = useState(false);
  const localStreamRef = useRef(null);

  // Номер собеседника вынимаем из названия комнаты: room-<звонящий>-<кому>
  const partnerId = (() => {
    const parts = String(roomId || '').split('-');
    const pair = parts.slice(1).filter((part) => /^[0-9]+$/.test(part));
    return pair.find((id) => String(id) !== String(currentUserId)) || null;
  })();

  useEffect(() => {
    if (!partnerId) return undefined;
    let alive = true;
    (async () => {
      try {
        const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${partnerId}`);
        if (response.ok && alive) setPartner(await response.json());
      } catch (error) {
        console.warn('Не вышло узнать собеседника:', error);
      }
    })();
    return () => { alive = false; };
  }, [partnerId]);

  /** Выключить и включить свой микрофон. */
  const toggleMute = () => {
    const stream = localStreamRef.current;
    if (!stream) return;
    const next = !muted;
    stream.getAudioTracks().forEach((track) => { track.enabled = !next; });
    setMuted(next);
  };

  useEffect(() => {
    if (isCaller) {
      setIsWaiting(true);
      ringtoneRef.current.loop = true;
      ringtoneRef.current.play().catch((error) => {
        console.error('Error playing ringtone:', error);
      });
    }

    return () => {
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
    };
  }, [isCaller]);

  let userName = recipientName;
  let terminationInitiator = false;

  const [chartData, setChartData] = useState({
    labels: [],
    datasets: [
      {
        label: 'Voice Activity',
        data: [],
        borderColor: '#67e8f9',
        backgroundColor: 'rgba(103, 232, 249, 0.15)',
        borderWidth: 2.5,
        tension: 0.4,
        fill: true,
      },
    ],
  });

  useEffect(() => {
    console.log('isCaller:', isCaller);
    console.log('currentUserId:', currentUserId);

    if (!currentUserId) {
      console.error('currentUserId is undefined. Check the props being passed to VoiceCall.');
      return;
    }

    const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
    stompClient.current = new Client({
      connectHeaders: stompAuthHeaders(),
      webSocketFactory: () => socket,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        console.log('Connected to WebSocket');
        console.log('Room ID from URL:', roomId);

        const subscription = stompClient.current.subscribe(`/topic/room/${roomId}`, (message) => {
          console.log('Received message on /topic/room/' + roomId + ':', message.body);
          try {
            const signalMessage = JSON.parse(message.body);
            handleSignal(signalMessage);
          } catch (error) {
            console.error('Error parsing WebSocket message:', error);
          }
        });

        if (!isCaller && incomingCall) {
          console.log('Processing incoming call:', incomingCall);
          handleIncomingCall(incomingCall);
        }

        return () => {
          console.log('Unsubscribing from /topic/room/' + roomId);
          subscription.unsubscribe();
        };
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame);
        notify('STOMP protocol error.', { toastId: 'stomp-error' });
      },
      onWebSocketError: (error) => {
        if (error.type === 'error') {
          console.warn('Ignoring temporary WebSocket error:', {
            error,
            timestamp: new Date().toISOString(),
            route: window.location.pathname,
            roomId,
          });
          return;
        }
        console.error('Unexpected WebSocket error:', {
          error,
          timestamp: new Date().toISOString(),
          route: window.location.pathname,
          roomId,
        });
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected');
      },
    });
    stompClient.current.activate();

    // === ИСПРАВЛЕННЫЙ ГРАФИК — СКОЛЬЗЯЩЕЕ ОКНО ===
    const MAX_POINTS = 40; // сколько точек показывать на графике (примерно 40 секунд)

    const interval = setInterval(() => {
      setChartData((prevData) => {
        const newTime = new Date().toLocaleTimeString([], { 
          hour: '2-digit',
          minute: '2-digit', 
          second: '2-digit',
          hour12: false   // 24-часовой формат
        });

        const newValue = Math.random() * 100;

        let newLabels = [...prevData.labels, newTime];
        let newData = [...prevData.datasets[0].data, newValue];

        if (newLabels.length > MAX_POINTS) {
          newLabels = newLabels.slice(-MAX_POINTS);
          newData = newData.slice(-MAX_POINTS);
        }

        return {
          labels: newLabels,
          datasets: [{ ...prevData.datasets[0], data: newData }],
        };
      });
    }, 1000);

    return () => {
      console.log('Deactivating WebSocket connection...');
      if (stompClient.current) {
        stompClient.current.deactivate();
        console.log('WebSocket connection deactivated.');
      }
      clearInterval(interval);
    };
  }, [currentUserId, roomId]);

  const handleIncomingCall = async (callRequest) => {
    console.log('Function handleIncomingCall in VoiceCall.js working...');
    userName = callerName;
    console.log('userName:', userName);
    console.log('callRequest:', JSON.stringify(callRequest, null, 2));
    setIncomingCallState(callRequest);

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      localStreamRef.current = stream; // чтобы кнопка «Mute» было что выключить
      localAudioRef.current.srcObject = stream;
      console.log('Audio stream obtained successfully.');

      const iceServers = [
        { urls: 'stun:stun.l.google.com:19302' },
        {
          urls: 'turn:13.49.238.121:3478?transport=tcp',
          username: 'username2',
          credential: 'key2',
        },
      ];

      peerConnectionRef.current = new RTCPeerConnection({ iceServers, iceTransportPolicy: 'all' });
      console.log('RTCPeerConnection initialized with TURN server:', peerConnectionRef.current);

      peerConnectionRef.current.ontrack = (event) => {
        console.log('ontrack triggered:', event);
        console.log('Event streams:', event.streams);
        console.log('Event track:', event.track);

        const stream = event.streams[0];
        if (stream) {
          console.log('Stream active:', stream.active);
          console.log('Track enabled:', event.track.enabled);
          remoteAudioRef.current.srcObject = stream;
          remoteAudioRef.current.play().catch((err) => console.error('Audio play error:', err));
        } else {
          console.error('No streams received in ontrack event');
        }
      };

      peerConnectionRef.current.oniceconnectionstatechange = () => {
        console.log('ICE Connection State:', peerConnectionRef.current.iceConnectionState);
        if (peerConnectionRef.current.iceConnectionState === 'connected') {
          console.log('WebRTC connection established');
        } else if (peerConnectionRef.current.iceConnectionState === 'failed') {
          console.error('WebRTC connection failed');
          notify('Failed to establish voice connection.', { toastId: 'voice-connection-failed' });
          endCall();
        }
      };

      stream.getTracks().forEach((track) => {
        peerConnectionRef.current.addTrack(track, stream);
        console.log('Track added to peer connection:', track);
      });

      peerConnectionRef.current.onicecandidate = (event) => {
        console.log('onicecandidate event fired:', event);
        if (event.candidate) {
          console.log('ICE candidate generated:', event.candidate);
          sendSignal({
            from: currentUserId,
            signal: {
              type: 'candidate',
              sdp: '',
              iceCandidate: {
                candidate: event.candidate.candidate,
                sdpMid: event.candidate.sdpMid,
                sdpMLineIndex: event.candidate.sdpMLineIndex.toString(),
              },
            },
          });
        } else {
          console.log('No more ICE candidates.');
        }
      };

      sendSignal({ from: currentUserId, signal: { type: 'ready', sdp: '' } });
    } catch (error) {
      console.error('Error accessing audio devices:', error);
      notify('Failed to access microphone.', { toastId: 'media-access-failed' });
      endCall();
    }
  };

  const rejectCall = () => {
    setIncomingCallState(null);
    console.log(`Call from ${incomingCallState?.callerId} rejected.`);
    sendSignal({ from: currentUserId, signal: { type: 'rejectCall' } });
  };

  const startCall = async () => {
    console.log('Function startCall in VoiceCall.js working...');
    userName = recipientName;
    console.log('userName:', userName);

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      localStreamRef.current = stream; // чтобы кнопка «Mute» было что выключить
      localAudioRef.current.srcObject = stream;
      console.log('Local audio stream obtained.');

      const iceServers = [
        { urls: 'stun:stun.l.google.com:19302' },
        {
          urls: 'turn:13.49.238.121:3478?transport=tcp',
          username: 'username1',
          credential: 'key1',
        },
      ];

      peerConnectionRef.current = new RTCPeerConnection({ iceServers, iceTransportPolicy: 'all' });
      console.log('RTCPeerConnection initialized:', peerConnectionRef.current);

      peerConnectionRef.current.ontrack = (event) => {
        console.log('ontrack triggered:', event);
        console.log('Event streams:', event.streams);
        console.log('Event track:', event.track);

        const stream = event.streams[0];
        if (stream) {
          console.log('Stream active:', stream.active);
          console.log('Track enabled:', event.track.enabled);
          remoteAudioRef.current.srcObject = stream;
          remoteAudioRef.current.play().catch((err) => console.error('Audio play error:', err));
        } else {
          console.error('No streams received in ontrack event');
        }
      };

      peerConnectionRef.current.oniceconnectionstatechange = () => {
        console.log('ICE Connection State:', peerConnectionRef.current.iceConnectionState);
        if (peerConnectionRef.current.iceConnectionState === 'connected') {
          console.log('WebRTC connection established');
        } else if (peerConnectionRef.current.iceConnectionState === 'failed') {
          console.error('WebRTC connection failed');
          notify('Failed to establish voice connection.', { toastId: 'voice-connection-failed' });
          endCall();
        }
      };

      stream.getTracks().forEach((track) => {
        peerConnectionRef.current.addTrack(track, stream);
        console.log('Track added to peer connection:', track);
      });

      peerConnectionRef.current.onicecandidate = (event) => {
        console.log('onicecandidate event fired');
        if (event.candidate) {
          console.log('ICE candidate generated:', event.candidate);
          sendSignal({
            from: currentUserId,
            signal: {
              type: 'candidate',
              sdp: '',
              iceCandidate: {
                candidate: event.candidate.candidate,
                sdpMid: event.candidate.sdpMid,
                sdpMLineIndex: event.candidate.sdpMLineIndex.toString(),
              },
            },
          });
        } else {
          console.log('All ICE candidates have been generated.');
        }
      };

      const offer = await peerConnectionRef.current.createOffer();
      await peerConnectionRef.current.setLocalDescription(offer);
      console.log('Local offer created and set:', offer);

      sendSignal({
        from: currentUserId,
        signal: { type: offer.type, sdp: offer.sdp, signature: signSdp(offer.sdp), deviceId: myDeviceId() },
        roomId,
      });

      peerConnectionRef.current.onicegatheringstatechange = () => {
        console.log('ICE Gathering State:', peerConnectionRef.current.iceGatheringState);
      };
    } catch (error) {
      console.error('Error starting voice call:', error);
      notify('Failed to start voice call.', { toastId: 'start-call-failed' });
      endCall();
    }
  };

  const handleSignal = async (signalMessage) => {
    console.log('Received signal message:', JSON.stringify(signalMessage, null, 2));

    if (signalMessage.signal) {
      console.log('Processing signal type:', signalMessage.signal.type);

      try {
        if (signalMessage.signal.type === 'offer') {
          console.log('Handling offer...');
          if (peerConnectionRef.current && !isCaller) {
            console.log('Received offer SDP:', signalMessage.signal.sdp);
            if (verifySdp(signalMessage.signal.sdp, signalMessage.signal.signature, signalMessage.signal.deviceId)) return;
            await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
            const answer = await peerConnectionRef.current.createAnswer();
            await peerConnectionRef.current.setLocalDescription(answer);
            console.log('Sending answer:', answer);
            sendSignal({
              from: currentUserId,
              signal: { type: 'answer', sdp: answer.sdp, signature: signSdp(answer.sdp), deviceId: myDeviceId() },
            });
            // NEW: Устанавливаем hasNotifiedAcceptCall при получении offer
            hasNotifiedAcceptCall.current = true;
            notify(`${recipientName} accepted the call.`, { toastId: `accept-call-${roomId}` });
          } else {
            console.log('peerConnectionRef.current is not initialized or signal received by caller.');
          }
        } else if (signalMessage.signal.type === 'answer') {
          console.log('Handling answer...');
          if (isCaller && peerConnectionRef.current) {
            if (peerConnectionRef.current.remoteDescription) {
              console.log('Cannot set remote description: Already has a remote description.');
            } else {
              console.log('Setting remote answer SDP:', signalMessage.signal.sdp);
              if (verifySdp(signalMessage.signal.sdp, signalMessage.signal.signature, signalMessage.signal.deviceId)) return;
              await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
            }
          }
        } else if (signalMessage.signal.type === 'candidate') {
          console.log('Handling ICE candidate:', signalMessage.signal.iceCandidate);
          const { iceCandidate } = signalMessage.signal;
          const { candidate, sdpMid, sdpMLineIndex } = iceCandidate;

          if (candidate && sdpMid !== null && sdpMLineIndex !== null) {
            const iceCandidateToAdd = new RTCIceCandidate({
              candidate,
              sdpMid,
              sdpMLineIndex,
            });
            setTimeout(async () => {
              try {
                await peerConnectionRef.current.addIceCandidate(iceCandidateToAdd);
                console.log('ICE candidate added:', iceCandidateToAdd);
              } catch (error) {
                console.error('Error adding ICE candidate:', error);
              }
            }, 3);
          } else {
            console.error('ICE candidate is missing required fields:', signalMessage.signal);
          }
        } else if (signalMessage.signal.type === 'ready' && isCaller) {
          console.log('Received ready signal from the other party.');
          setIsWaiting(false);
          ringtoneRef.current.pause();
          ringtoneRef.current.currentTime = 0;
          setTimeout(() => {
            startCall();
          }, 1000);
        } else if (signalMessage.signal.type === 'endCall') {
          console.log('Call ended by the other party.');
          if (!hasNotifiedEndCall.current) {
            hasNotifiedEndCall.current = true;
            if (userName) {
              notify(`${userName} interrupted the conversation.`, { toastId: `end-call-${roomId}` });
            } else {
              notify(`User with ID ${signalMessage.from} interrupted the conversation.`, {
                toastId: `end-call-${roomId}`,
              });
            }
          }
          terminationInitiator = true;
          endCall();
        } else if (signalMessage.signal.type === 'rejectCall' && isCaller) {
            console.log('Received rejectCall signal.');
            if (!hasNotifiedRejectCall.current) {
              hasNotifiedRejectCall.current = true; // NEW: Устанавливаем флаг rejectCall
              setIsWaiting(false);
              ringtoneRef.current.pause();
              ringtoneRef.current.currentTime = 0;
              notify(`${recipientName} rejected the call.`, { toastId: `reject-call-${roomId}` });
              terminationInitiator = true;
              endCall();
            }
        } else if (signalMessage.signal.type === 'acceptCall' && isCaller) {
            console.log('Received acceptCall signal.');
            if (!hasNotifiedAcceptCall.current) {
              hasNotifiedAcceptCall.current = true;
              setIsWaiting(false);
              ringtoneRef.current.pause();
              ringtoneRef.current.currentTime = 0;
              notify(`${recipientName} accepted the call.`, { toastId: `accept-call-${roomId}` });
            }
        }
      } catch (error) {
        console.error('Error processing signal:', error);
      }
    } else {
      console.error('Signal message does not contain signal:', signalMessage);
    }
  };

  const sendSignal = (signalMessage) => {
    console.log('Function sendSignal in VoiceCall.js working...');
    console.log('Sending signal:', JSON.stringify(signalMessage, null, 2));
    if (stompClient.current && stompClient.current.connected) {
      stompClient.current.publish({
        destination: `/app/signal/${roomId}`,
        body: JSON.stringify(signalMessage),
      });
      console.log('Signal sent successfully.');
    } else {
      console.error('Cannot send signal: STOMP client is not connected.');
    }
  };

  const endCall = () => {
    console.log('endCall called'); // NEW: Лог для отладки вызовов endCall

    // NEW: Проверяем, был ли звонок уже сохранён, чтобы избежать дублирования
    if (hasSavedCall.current) {
      console.log('Call already saved, skipping endCall');
      return;
    }

    if (peerConnectionRef.current) {
      peerConnectionRef.current.close();
      peerConnectionRef.current = null;
      console.log('Peer connection closed.');
    }

    if (localAudioRef.current && localAudioRef.current.srcObject) {
      localAudioRef.current.srcObject.getTracks().forEach((track) => track.stop());
      localAudioRef.current.srcObject = null;
    }
    if (remoteAudioRef.current && remoteAudioRef.current.srcObject) {
      remoteAudioRef.current.srcObject.getTracks().forEach((track) => track.stop());
      remoteAudioRef.current.srcObject = null;
    }

    if (isCaller) {
      setIsWaiting(false);
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
    }

    if (stompClient.current && stompClient.current.connected) {
      if (!terminationInitiator) {
        sendSignal({
          from: currentUserId,
          signal: { type: 'endCall' },
        });

        if (isCaller && incomingCall?.recipientId) {
          console.log('incomingCall:', incomingCall);
          const endCallMessage = {
            type: 'endCall',
            roomId,
            callerId: currentUserId,
            recipientId: incomingCall.recipientId,
          };
          console.log('Sending endCall to /app/call/', incomingCall.recipientId, ':', endCallMessage);
          stompClient.current.publish({
            destination: `/app/call/${incomingCall.recipientId}`,
            body: JSON.stringify(endCallMessage),
          });
        }
      }

      stompClient.current.deactivate();
      console.log('WebSocket connection deactivated in endCall.');
    }

    // NEW: Добавляем функцию saveCall для сохранения звонка
    const saveCall = async () => {
      console.log('saveCall called with hasNotifiedAcceptCall:', hasNotifiedAcceptCall.current);
      try {
        let status = hasNotifiedRejectCall.current ? 'rejected' : 'completed';

        await axios.post(
          `https://unlimitedpossibilities12.org/api/calls?userId=${currentUserId}`,
          {
            caller: isCaller ? currentUserId : incomingCall?.callerId,
            receiver: isCaller ? incomingCall?.recipientId : currentUserId,
            direction: isCaller ? 'outgoing' : 'incoming',
            status: status,
            callType: 'voice',
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
            roomId: roomId, // NEW: Добавляем roomId
          }
        );
        console.log(`Voice call saved to server with status: ${status}`);
        hasSavedCall.current = true;
      } catch (error) {
        console.error('Error saving voice call:', error);
        notify('Failed to save call', { toastId: 'save-call-error' });
      }
    };

    // NEW: Вызываем saveCall перед сбросом флагов и навигацией
    saveCall();

    // NEW: Сброс флагов только после saveCall
    hasNotifiedAcceptCall.current = false;
    hasNotifiedEndCall.current = false;
    hasNotifiedRejectCall.current = false;

    if (onClose) onClose(); else navigate('/');
  };


  // Подпись собеседника: шифрование в звонке есть всегда, а подпись отвечает
  // на другой вопрос — тот ли человек по ту сторону. Прежде это был отдельный
  // «защищённый звонок», который надо было выбрать вручную.
  const callGuardRef = useRef(null);
  const [callSecurity, setCallSecurity] = useState(CallSecurity.CHECKING);
  const [showFingerprint, setShowFingerprint] = useState(false);
  const [fingerprintCode, setFingerprintCode] = useState(null);
  const [fingerprintDigits, setFingerprintDigits] = useState(null);

  // Код считается асинхронно — показываем его, когда он готов
  const toggleFingerprint = async () => {
    setShowFingerprint((shown) => !shown);
    if (!fingerprintCode && callGuardRef.current) {
      setFingerprintCode(await callGuardRef.current.fingerprint());
      setFingerprintDigits(await callGuardRef.current.fingerprintDigits());
    }
  };

  // Сверили вслух — запоминаем, и дальше подмена будет видна
  const confirmCodes = () => {
    const guard = callGuardRef.current;
    if (!guard) return;
    if (guard.state() === CallSecurity.CHANGED) guard.acceptChangedKey();
    guard.confirmByCode();
    setCallSecurity(guard.state());
  };

  useEffect(() => {
    let cancelled = false;
    // Собеседник: кому звоним или кто звонит нам
    // Собеседник: кому звоним или кто звонит нам. Идентификатор приходит в
    // состоянии перехода, а не свойством — свойство у этого экрана пустое.
    const partnerId = isCaller
      ? (incomingCall?.recipientId || recipientId)
      : (incomingCall?.callerId || recipientId);
    if (!currentUserId || !partnerId) return undefined;
    createCallGuard(currentUserId, partnerId)
      .then((guard) => {
        if (cancelled) return;
        callGuardRef.current = guard;
        setCallSecurity(guard.state());
      })
      .catch((error) => console.warn('Не удалось подготовить подпись звонка:', error));
    return () => {
      cancelled = true;
    };
  }, [currentUserId, recipientId, incomingCall, isCaller]);

  const signSdp = (sdp) => (callGuardRef.current ? callGuardRef.current.sign(sdp) : null);
  const myDeviceId = () => (callGuardRef.current ? callGuardRef.current.deviceId : null);

  // Возвращает true, если разговор надо прервать: подпись не сошлась
  const verifySdp = (sdp, signature, senderDeviceId) => {
    const guard = callGuardRef.current;
    if (!guard) return false;
    const state = guard.verify(sdp, signature, senderDeviceId);
    setCallSecurity(state);
    if (state === CallSecurity.FAILED) {
      console.error('Подпись собеседника не сошлась — прекращаем разговор');
      notify('Could not verify the other side — ending the call.', { toastId: `bad-signature-${roomId}` });
      setTimeout(() => endCall(), 2500);
      return true;
    }
    return false;
  };

  // Таймер продолжительности звонка
  useEffect(() => {
    const timer = setInterval(() => {
      setCallDuration(prev => prev + 1);
    }, 1000);

    return () => clearInterval(timer);
  }, []);


  /**
   * Свёрнутый разговор занимает полоску сверху — и приложение должно на
   * неё подвинуться, иначе полоска накрывает шапку со словом «Chats» и
   * поиском.
   */
  useEffect(() => {
    document.body.classList.toggle('has-call-strip', Boolean(minimized));
    return () => document.body.classList.remove('has-call-strip');
  }, [minimized]);

  const partnerName = partner
    ? (partner.displayName || partner.username)
    : (isCaller ? recipientName : callerName);

  const letter = (partnerName || '?').trim().charAt(0).toUpperCase();
  // Цвет кружка — от имени, как в списке людей и на телефоне
  const letterColor = ['#F87171', '#FB923C', '#FBBF24', '#34D399', '#38BDF8', '#A78BFA', '#F472B6']
    [(partnerName || '?').split('').reduce((sum, ch) => sum + ch.charCodeAt(0), 0) % 7];
  const levels = (chartData.datasets[0]?.data || []).slice(-28);

  // Четыре значка кода прямо в шапке: в телеграме они стоят в заголовке
  // окна, и сверить разговор можно, ничего не открывая
  const shortCode = (fingerprintCode || '').split(' ').filter(Boolean).slice(0, 4).join(' ');

  if (minimized) {
    return (
      <div className="voice-call-strip" onClick={onMinimize} role="presentation">
        <span className="voice-call-strip-dot" />
        <strong>{partnerName}</strong>
        <span className="voice-call-strip-time">
          {Math.floor(callDuration / 60).toString().padStart(2, '0')}:
          {(callDuration % 60).toString().padStart(2, '0')}
        </span>
        {shortCode && <span className="voice-call-strip-code">{shortCode}</span>}
        <button
          type="button"
          className="voice-call-strip-end"
          onClick={(event) => { event.stopPropagation(); endCall(); }}
        >
          End call
        </button>
      </div>
    );
  }

  return (
    <div className="voice-call-window">
      <div className="voice-call-bar">
        <button
          type="button"
          className={`voice-call-bar-code call-security-${callSecurity}`}
          onClick={toggleFingerprint}
          title="Show the code to read aloud"
        >
          {shortCode || '🔒'}
        </button>
        <span className="voice-call-bar-title">{partnerName}</span>
        <button type="button" className="voice-call-bar-button" onClick={onMinimize} title="Minimise">
          &#8211;
        </button>
        <button type="button" className="voice-call-bar-button is-close" onClick={endCall} title="End the call">
          &#10005;
        </button>
      </div>
      <div className="voice-call-person">
        <div className="voice-call-avatar" style={{ background: partner && partner.avatar ? 'none' : letterColor }}>
          {partner && partner.avatar
            ? <img src={`https://unlimitedpossibilities12.org${partner.avatar}`} alt="" />
            : <span>{letter}</span>}
        </div>
        <h2 className="voice-call-name">{partnerName}</h2>
        <p className="voice-call-state">{isWaiting ? 'Calling…' : 'On the call'}</p>
        {!isWaiting && (
          <div className="call-duration">
            {Math.floor(callDuration / 60).toString().padStart(2, '0')}:
            {(callDuration % 60).toString().padStart(2, '0')}
          </div>
        )}

        <button
          type="button"
          className={`call-security call-security-${callSecurity}`}
          onClick={toggleFingerprint}
        >
          <span className="call-security-lock">&#128274;</span>
          {securityLabel(callSecurity)}
        </button>

        {/* Голос собеседника — полоской, а не полем с осями и часами:
            на экране звонка важно видеть, что он говорит, а не измерять */}
        <div className="voice-call-levels" aria-hidden="true">
          {levels.map((value, index) => (
            <span key={index} style={{ height: `${Math.max(3, Math.min(100, value))}%` }} />
          ))}
        </div>
      </div>

      {showFingerprint && (
        <div className="call-fingerprint-backdrop" onClick={toggleFingerprint} role="presentation">
          <div className="call-fingerprint" onClick={(event) => event.stopPropagation()}>
            <p>{securityExplanation(callSecurity)}</p>
            <div className="call-fingerprint-code">
              {fingerprintCode || 'The code appears once both sides have exchanged keys.'}
            </div>
            {fingerprintDigits && <div className="call-fingerprint-digits">{fingerprintDigits}</div>}
            {fingerprintCode && (
              <button type="button" className="call-fingerprint-confirm" onClick={confirmCodes}>
                The codes match
              </button>
            )}
          </div>
        </div>
      )}

      <div className="audio-container">
        <audio ref={localAudioRef} autoPlay muted />
        <audio ref={remoteAudioRef} autoPlay />
      </div>

      <div className="voice-call-controls">
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
        <button type="button" className="voice-call-round is-end" onClick={endCall} title="End the call">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 9c-2.6 0-5.1.5-7.3 1.5-.8.4-1.3 1.2-1.2 2.1l.2 1.8c.1.9.9 1.5 1.8 1.4l2.6-.4c.8-.1 1.4-.8 1.5-1.6l.2-1.4c1.5-.4 3-.4 4.5 0l.2 1.4c.1.8.7 1.5 1.5 1.6l2.6.4c.9.1 1.7-.5 1.8-1.4l.2-1.8c.1-.9-.4-1.7-1.2-2.1C17.1 9.5 14.6 9 12 9Z" />
          </svg>
          <small>End</small>
        </button>
      </div>
    </div>
  );
};

export default VoiceCall;