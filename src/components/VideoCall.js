import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import axios from 'axios'; // NEW: Добавляем импорт axios
import { useNotification } from '../context/NotificationContext';
import '../styles/VideoCall.css';

const VideoCall = ({ recipientId }) => {
  const navigate = useNavigate();
  const notify = useNotification();
  const { roomId } = useParams();
  const location = useLocation();
  const { incomingCall, isCaller, currentUserId, callerName, recipientName } = location.state || {
    incomingCall: null,
    isCaller: false,
    currentUserId: null,
    callerName: '',
    recipientName: '',
  };
  const localVideoRef = useRef(null);
  const remoteVideoRef = useRef(null);
  const peerConnectionRef = useRef(null);
  const stompClient = useRef(null);
  const [incomingCallState, setIncomingCallState] = useState(incomingCall);
  const [isWaiting, setIsWaiting] = useState(false);
  const [callDuration, setCallDuration] = useState(0);
  const [videoOrientation, setVideoOrientation] = useState('horizontal'); // 'horizontal' или 'vertical'
  const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));
  const hasNotifiedEndCall = useRef(false); // Флаг для предотвращения дублирования тостов в endCall
  const hasNotifiedAcceptCall = useRef(false); // Флаг для предотвращения дублирования тостов acceptCall
  const hasNotifiedRejectCall = useRef(false); // Флаг для предотвращения дублирования тостов rejectCall
  const hasSavedCall = useRef(false); // NEW: Флаг для предотвращения двойного сохранения звонка

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

  useEffect(() => {
    console.log('isCaller:', isCaller);
    console.log('currentUserId:', currentUserId);
    console.log('Room ID from URL:', roomId);

    if (!currentUserId) {
      console.error('currentUserId is undefined. Check the props being passed to VideoCall.');
      return;
    }

    const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
    stompClient.current = new Client({
      webSocketFactory: () => socket,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        console.log('Connected to WebSocket');
        stompClient.current.subscribe(`/topic/room/${roomId}`, (message) => {
          console.log('Received message on /topic/room/' + roomId + ':', message.body);
          try {
            const signalMessage = JSON.parse(message.body);
            handleSignal(signalMessage);
          } catch (error) {
            console.error('Error parsing WebSocket message:', error);
          }
        });
        console.log('Subscribed to /topic/room/' + roomId);

        if (!isCaller && incomingCall) {
          console.log('Processing incoming call:', incomingCall);
          handleIncomingCall(incomingCall);
        }
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
          });
          return;
        }
        console.error('Unexpected WebSocket error:', {
          error,
          timestamp: new Date().toISOString(),
          route: window.location.pathname,
        });
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected');
      },
    });
    stompClient.current.activate();

    return () => {
      console.log('Deactivating WebSocket connection...');
      if (stompClient.current) {
        stompClient.current.deactivate();
        console.log('WebSocket connection deactivated.');
      }
    };
  }, [currentUserId, roomId, isCaller, incomingCall]);

  const handleIncomingCall = async (callRequest) => {
    console.log('Function handleIncomingCall in VideoCall.js working...');
    userName = callerName;
    console.log('userName:', userName);
    console.log('callRequest:', JSON.stringify(callRequest, null, 2));
    setIncomingCallState(callRequest);

    try {
      let stream;
      try {
        // Пробуем получить аудио и видео
        stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
        console.log('Video and audio stream obtained successfully.');
      } catch (videoError) {
        console.warn('Failed to access camera, trying audio only:', videoError);
        // Если камера недоступна, запрашиваем только аудио
        stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        console.log('Audio stream obtained successfully.');
      }

      if (localVideoRef.current) {
        localVideoRef.current.srcObject = stream;
      }

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
        console.log('Event track:', event.track, 'kind:', event.track.kind, 'enabled:', event.track.enabled);
        const stream = event.streams[0];
        if (stream) {
          console.log('Stream active:', stream.active, 'tracks:', stream.getTracks());
          if (remoteVideoRef.current) {
            remoteVideoRef.current.srcObject = stream;
            remoteVideoRef.current.play().catch((err) => console.error('Video play error:', err));
          }
        } else {
          console.error('No streams received in ontrack event');
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

      peerConnectionRef.current.oniceconnectionstatechange = () => {
        console.log('ICE Connection State:', peerConnectionRef.current.iceConnectionState);
        if (peerConnectionRef.current.iceConnectionState === 'connected') {
          console.log('WebRTC connection established');
        } else if (peerConnectionRef.current.iceConnectionState === 'failed') {
          console.error('WebRTC connection failed');
          notify('Failed to establish video connection.', { toastId: 'video-connection-failed' });
          endCall();
        }
      };

      // Отправляем сигнал ready, даже если есть только аудио
      sendSignal({ from: currentUserId, signal: { type: 'ready' } });
    } catch (error) {
      console.error('Error accessing media devices:', error);
      notify('Failed to access microphone.', { toastId: 'media-access-failed' });
      endCall();
    }
  };

  const rejectCall = () => {
    setIncomingCallState(null);
    console.log(`Call from ${incomingCallState.callerId} rejected.`);
    sendSignal({ from: currentUserId, signal: { type: 'rejectCall' } });
  };

  const startCall = async () => {
    console.log('Function startCall in VideoCall.js working...');
    userName = recipientName;
    console.log('userName:', userName);

    try {
      let stream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
        console.log('Local video and audio stream obtained.');
      } catch (videoError) {
        console.warn('Failed to access camera, trying audio only:', videoError);
        stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        console.log('Local audio stream obtained.');
      }

      if (localVideoRef.current) {
        localVideoRef.current.srcObject = stream;
      }

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
        console.log('Event track:', event.track, 'kind:', event.track.kind, 'enabled:', event.track.enabled);
        const stream = event.streams[0];
        if (stream) {
          console.log('Stream active:', stream.active, 'tracks:', stream.getTracks());
          if (remoteVideoRef.current) {
            remoteVideoRef.current.srcObject = stream;
            remoteVideoRef.current.play().catch((err) => console.error('Video play error:', err));
          }
        } else {
          console.error('No streams received in ontrack event');
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

      peerConnectionRef.current.oniceconnectionstatechange = () => {
        console.log('ICE Connection State:', peerConnectionRef.current.iceConnectionState);
        if (peerConnectionRef.current.iceConnectionState === 'connected') {
          console.log('WebRTC connection established');
        } else if (peerConnectionRef.current.iceConnectionState === 'failed') {
          console.error('WebRTC connection failed');
          notify('Failed to establish video connection.', { toastId: 'video-connection-failed' });
          endCall();
        }
      };

      const offer = await peerConnectionRef.current.createOffer({
        offerToReceiveAudio: true,
        offerToReceiveVideo: true,
      });
      await peerConnectionRef.current.setLocalDescription(offer);
      console.log('Local offer created and set:', offer.sdp);

      sendSignal({ from: currentUserId, signal: { type: 'offer', sdp: offer.sdp } });
    } catch (error) {
      console.error('Error starting video call:', error);
      notify('Failed to start video call.', { toastId: 'start-call-failed' });
      endCall();
    }
  };

  const handleSignal = async (signalMessage) => {
    console.log('Received signal message:', JSON.stringify(signalMessage, null, 2));

    if (!signalMessage.signal) {
      console.error('Signal message does not contain signal:', signalMessage);
      return;
    }

    const { type, sdp, iceCandidate } = signalMessage.signal;
    console.log('Processing signal type:', type);

    try {
      if (type === 'offer') {
        console.log('Handling offer...');
        if (peerConnectionRef.current && !isCaller) {
          console.log('Received offer SDP:', sdp);
          await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
          const answer = await peerConnectionRef.current.createAnswer({
            offerToReceiveAudio: true,
            offerToReceiveVideo: true,
          });
          await peerConnectionRef.current.setLocalDescription(answer);
          console.log('Sending answer SDP:', answer.sdp);
          sendSignal({ from: currentUserId, signal: { type: 'answer', sdp: answer.sdp } });
          // NEW: Устанавливаем hasNotifiedAcceptCall для получателя, так как обработка offer означает принятие звонка
          hasNotifiedAcceptCall.current = true;
          notify(`${callerName} call accepted.`, { toastId: `accept-call-${roomId}` });
        } else {
          console.log('peerConnectionRef.current is not initialized or signal received by caller.');
        }
      } else if (type === 'answer') {
        console.log('Handling answer...');
        if (isCaller && peerConnectionRef.current) {
          if (peerConnectionRef.current.remoteDescription) {
            console.log('Cannot set remote description: Already has a remote description.');
          } else {
            console.log('Setting remote answer SDP:', sdp);
            await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp }));
            console.log('Remote answer set successfully');
          }
        }
      } else if (type === 'candidate') {
        console.log('Handling ICE candidate:', iceCandidate);
        if (peerConnectionRef.current && iceCandidate && iceCandidate.candidate && iceCandidate.sdpMid !== null && iceCandidate.sdpMLineIndex !== null) {
          const iceCandidateToAdd = new RTCIceCandidate({
            candidate: iceCandidate.candidate,
            sdpMid: iceCandidate.sdpMid,
            sdpMLineIndex: parseInt(iceCandidate.sdpMLineIndex, 10),
          });
          try {
            await peerConnectionRef.current.addIceCandidate(iceCandidateToAdd);
            console.log('ICE candidate added:', iceCandidateToAdd);
          } catch (error) {
            console.error('Error adding ICE candidate:', error);
          }
        } else {
          console.error('Invalid ICE candidate or PeerConnection not initialized:', iceCandidate);
        }
      } else if (type === 'ready' && isCaller) {
        console.log('Received ready signal from the other party.');
        setIsWaiting(false);
        ringtoneRef.current.pause();
        ringtoneRef.current.currentTime = 0;
        setTimeout(() => {
          startCall();
        }, 1000);
      } else if (type === 'acceptCall' && isCaller) {
        console.log('Received acceptCall signal.');
        if (!hasNotifiedAcceptCall.current) {
            hasNotifiedAcceptCall.current = true;
            setIsWaiting(false);
            ringtoneRef.current.pause();
            ringtoneRef.current.currentTime = 0;
            notify(`${recipientName} accepted the video call.`, { toastId: `accept-call-${roomId}` });
        }
      } else if (type === 'rejectCall' && isCaller) {
          console.log('Received rejectCall signal.');
          if (!hasNotifiedRejectCall.current) {
              hasNotifiedRejectCall.current = true;
              setIsWaiting(false);
              ringtoneRef.current.pause();
              ringtoneRef.current.currentTime = 0;
              notify(`${recipientName} rejected the video call.`, { toastId: `reject-call-${roomId}` });
              terminationInitiator = true;
              endCall();
          }
      } else if (type === 'endCall') {
        console.log('Call ended by the other party.');
        if (!hasNotifiedEndCall.current) {
          hasNotifiedEndCall.current = true;
          if (userName) {
            notify(`${userName} interrupted the video conversation.`, { toastId: `end-call-${Date.now()}` });
          } else {
            notify(`User with ID ${signalMessage.from} interrupted the video conversation.`, { toastId: `end-call-${Date.now()}` });
          }
        }
        terminationInitiator = true;
        endCall();
      } else {
        console.warn('Unknown signal type:', type);
      }
    } catch (error) {
      console.error('Error processing signal:', error);
    }
  };

  const sendSignal = (signalMessage) => {
    console.log('Function sendSignal in VideoCall.js working...');
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
    console.log('Ending video call...');

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

    if (localVideoRef.current && localVideoRef.current.srcObject) {
      localVideoRef.current.srcObject.getTracks().forEach((track) => track.stop());
      localVideoRef.current.srcObject = null;
    }
    if (remoteVideoRef.current && remoteVideoRef.current.srcObject) {
      remoteVideoRef.current.srcObject.getTracks().forEach((track) => track.stop());
      remoteVideoRef.current.srcObject = null;
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
      try {
        let status = hasNotifiedRejectCall.current ? 'rejected' : 'completed';

        await axios.post(
          `https://unlimitedpossibilities12.org/api/calls?userId=${currentUserId}`,
          {
            caller: isCaller ? currentUserId : incomingCall?.callerId,
            receiver: isCaller ? incomingCall?.recipientId : currentUserId,
            direction: isCaller ? 'outgoing' : 'incoming',
            status: status,
            callType: 'video',
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
        console.log(`Video call saved to server with status: ${status}`);
        hasSavedCall.current = true;
      } catch (error) {
        console.error('Error saving video call:', error);
        notify('Failed to save call', { toastId: 'save-call-error' });
      }
    };

    // NEW: Вызываем saveCall перед сбросом флагов и навигацией
    saveCall();

    hasNotifiedAcceptCall.current = false;
    hasNotifiedEndCall.current = false;
    hasNotifiedRejectCall.current = false;

    navigate('/');
  };

  // Функция для переключения ориентации
  const toggleVideoOrientation = () => {
    setVideoOrientation((prev) => (prev === 'horizontal' ? 'vertical' : 'horizontal'));
  };

  // Таймер продолжительности видеозвонка
  useEffect(() => {
    const timer = setInterval(() => {
      setCallDuration(prev => prev + 1);
    }, 1000);

    return () => clearInterval(timer);
  }, []);

  return (
    <div className="video-call-container">
      <div className="video-call-header">
        <h2 className="video-call-title">
          Video call with {isCaller ? recipientName : callerName}
        </h2>
        <div className="call-duration">
          {Math.floor(callDuration / 60).toString().padStart(2, '0')}:
          {(callDuration % 60).toString().padStart(2, '0')}
        </div>
      </div>
      <div className={`video-container ${videoOrientation}`}>
        <video ref={remoteVideoRef} className="remote-video" autoPlay playsInline />
        <video ref={localVideoRef} className="local-video" autoPlay playsInline muted />
      </div>
      <div className="video-controls">
        <button className="orientation-toggle-button" onClick={toggleVideoOrientation}>
          {videoOrientation === 'horizontal' ? 'Switch to Vertical' : 'Switch to Horizontal'}
        </button>
        <button className="end-call-button-video" onClick={endCall}>
          End Call
        </button>
      </div>
    </div>
  );
};

export default VideoCall;