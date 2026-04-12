import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import axios from 'axios'; // NEW: Добавляем импорт axios
import IncomingCallNotification from './IncomingCallNotification';
import { Line } from 'react-chartjs-2';
import { Chart, registerables } from 'chart.js';
import { useNotification } from '../context/NotificationContext';
import '../styles/VoiceCall.css';

Chart.register(...registerables);

const VoiceCall = ({ recipientId }) => {
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

      sendSignal({ from: currentUserId, signal: offer, roomId });

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
            await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
            const answer = await peerConnectionRef.current.createAnswer();
            await peerConnectionRef.current.setLocalDescription(answer);
            console.log('Sending answer:', answer);
            sendSignal({ from: currentUserId, signal: { type: 'answer', sdp: answer.sdp } });
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

    navigate('/');
  };

  // Таймер продолжительности звонка
  useEffect(() => {
    const timer = setInterval(() => {
      setCallDuration(prev => prev + 1);
    }, 1000);

    return () => clearInterval(timer);
  }, []);

  return (
    <div className="voice-call-container">
      <div className="voice-call-header">
        <h2 className="voice-call-title">
          Voice Call with {isCaller ? recipientName : callerName}
        </h2>
        <div className="call-duration">
          {Math.floor(callDuration / 60).toString().padStart(2, '0')}:
          {(callDuration % 60).toString().padStart(2, '0')}
        </div>
      </div>

      <div className="audio-container">
        <audio ref={localAudioRef} autoPlay muted />
        <audio ref={remoteAudioRef} autoPlay />
      </div>

      <div className="chart-container">
        <Line
          data={chartData}
          options={{
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
              legend: { display: false },
            },
            scales: {
              x: {
                grid: { color: 'rgba(148, 163, 184, 0.25)' },
                ticks: { color: '#94a3b8' },
              },
              y: {
                grid: { color: 'rgba(148, 163, 184, 0.25)' },
                ticks: { color: '#94a3b8' },
                min: 0,
                max: 100,
              },
            },
          }}
        />
      </div>

      <button className="end-call-button-voice" onClick={endCall}>
        End Call
      </button>
    </div>
  );
};

export default VoiceCall;