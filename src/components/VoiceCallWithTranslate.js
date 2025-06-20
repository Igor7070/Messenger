import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Line } from 'react-chartjs-2';
import { Chart, registerables } from 'chart.js';
import { useNotification } from '../context/NotificationContext';
import '../styles/VoiceCallWithTranslate.css';

Chart.register(...registerables);

const VoiceCallWithTranslate = ({ recipientId }) => {
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
  const mediaRecorderRef = useRef(null);
  const [incomingCallState, setIncomingCallState] = useState(incomingCall);
  const [isWaiting, setIsWaiting] = useState(false);
  const [transcription, setTranscription] = useState('Transcription: Waiting...');
  const [friendTranscription, setFriendTranscription] = useState("Friend's Transcription: Waiting...");
  const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));
  const hasNotifiedEndCall = useRef(false);
  const hasNotifiedAcceptCall = useRef(false);
  const transcriptionRef = useRef(null);
  const friendTranscriptionRef = useRef(null);

  let userName = recipientName;
  let terminationInitiator = false;

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

  useEffect(() => {
    if (!currentUserId) {
      console.error('currentUserId is undefined.');
      notify('User ID is missing.', { toastId: 'user-id-missing' });
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
        console.log('Room ID:', roomId);

        // Подписка на сигнализацию
        stompClient.current.subscribe(`/topic/room/${roomId}`, (message) => {
          console.log('Received message on /topic/room/' + roomId + ':', message.body);
          try {
            const signalMessage = JSON.parse(message.body);
            handleSignal(signalMessage);
          } catch (error) {
            console.error('Error parsing WebSocket message:', error);
          }
        });

        // Подписка на собственную транскрипцию
        // МЕТКА: Обновить подписку на /topic/transcription
        stompClient.current.subscribe(`/topic/transcription/${roomId}`, (message) => {
          console.log('Received transcription message:', message.body);
          try {
            const json = JSON.parse(message.body);
            if (json.error) {
              setTranscription(`Error: ${json.error}`);
              scrollToBottom(transcriptionRef);
            } else if (json.transcription) {
              setTranscription((prev) =>
                prev === 'Transcription: Waiting...' || prev.startsWith('Error')
                  ? json.transcription
                  : `${prev}\n${json.transcription}`
              );
              scrollToBottom(transcriptionRef);
            }
          } catch (error) {
            console.error('Error parsing transcription JSON:', error);
            setTranscription(`Error: ${error.message}`);
            scrollToBottom(transcriptionRef);
          }
        });

        // Подписка на транскрипцию собеседника
        stompClient.current.subscribe(`/topic/friend-transcription/${roomId}/${currentUserId}`, (message) => {
          console.log('Received friend transcription:', message.body);
          try {
            const json = JSON.parse(message.body);
            if (json.error) {
              setFriendTranscription(`Error: ${json.error}`);
              scrollToBottom(friendTranscriptionRef);
            } else if (json.transcription) {
              setFriendTranscription((prev) =>
                prev === "Friend's Transcription: Waiting..." || prev.startsWith('Error')
                  ? json.transcription
                  : `${prev}\n${json.transcription}`
              );
              scrollToBottom(friendTranscriptionRef);
            }
          } catch (error) {
            console.error('Error parsing friend transcription JSON:', error);
            setFriendTranscription(`Error: ${error.message}`);
            scrollToBottom(friendTranscriptionRef);
          }
        });

        // Регистрация recipientId
        // МЕТКА: Обновить вызов registerRecipient
        if (incomingCall && (incomingCall.recipientId || incomingCall.callerId)) {
          registerRecipient();
        } else {
          console.warn('Cannot register recipient: incomingCall is missing required fields', incomingCall);
        }

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
        console.warn('WebSocket error:', error);
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected');
      },
    });
    stompClient.current.activate();

    const interval = setInterval(() => {
      setChartData((prevData) => {
        const newLabels = [...prevData.labels, new Date().toLocaleTimeString()];
        const newData = [...prevData.datasets[0].data, Math.random() * 100];
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
      }
      clearInterval(interval);
      stopAudioCapture();
    };
  }, [currentUserId, roomId, incomingCall]);

  const scrollToBottom = (ref) => {
    if (ref.current) {
      ref.current.scrollTop = ref.current.scrollHeight;
    }
  };

  const startAudioCapture = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorderRef.current = new MediaRecorder(stream, {
        mimeType: 'audio/webm',
        audioBitsPerSecond: 128000,
      });

      mediaRecorderRef.current.ondataavailable = (event) => {
        if (event.data.size > 0 && stompClient.current && stompClient.current.connected) {
          const reader = new FileReader();
          reader.onload = () => {
            const base64Audio = reader.result.split(',')[1]; // Удаляем префикс data:audio/webm;base64,
            console.log('Encoded Base64 audio length:', base64Audio.length);
            const audioMessage = {
              audio: base64Audio,
              sessionId: currentUserId,
            };
            stompClient.current.publish({
              destination: `/app/audio-transcription/${roomId}`,
              body: JSON.stringify(audioMessage),
            });
            console.log('Sent audio buffer, sessionId:', currentUserId);
          };
          reader.readAsDataURL(event.data);
        }
      };

      mediaRecorderRef.current.start(100); // Захват каждые 100 мс
      console.log('Audio capture started');
    } catch (error) {
      console.error('Error starting audio capture:', error);
      notify('Failed to access microphone for transcription.', { toastId: 'audio-capture-failed' });
    }
  };

  const stopAudioCapture = () => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
      mediaRecorderRef.current.stop();
      mediaRecorderRef.current.stream.getTracks().forEach((track) => track.stop());
      console.log('Audio capture stopped');
    }
  };

  const registerRecipient = () => {
    if (stompClient.current && stompClient.current.connected) {
      const registerMessage = {
        sessionId: `${currentUserId}_${Date.now()}`, // Уникальный sessionId
        recipientId: isCaller ? incomingCall?.recipientId || recipientName : incomingCall?.callerId || callerName, // ID собеседника
      };
      stompClient.current.publish({
        destination: `/app/register-recipient/${roomId}`,
        body: JSON.stringify(registerMessage),
      });
      console.log('Sent register recipient: sessionId=', registerMessage.sessionId, ', recipientId=', registerMessage.recipientId); // МЕТКА: Лог для отладки
    } else {
      console.error('STOMP client not connected, cannot send register-recipient');
    }
  };

  const handleIncomingCall = async (callRequest) => {
    console.log('Handling incoming call:', callRequest);
    userName = callerName;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      localAudioRef.current.srcObject = stream;
      console.log('Audio stream obtained successfully.');

      const iceServers = [
        { urls: 'stun:stun.l.google.com:19302' },
        {
          urls: 'turn:16.170.203.109:3478?transport=tcp',
          username: 'username2',
          credential: 'key2',
        },
      ];

      peerConnectionRef.current = new RTCPeerConnection({ iceServers, iceTransportPolicy: 'all' });
      peerConnectionRef.current.ontrack = (event) => {
        console.log('ontrack triggered:', event);
        const stream = event.streams[0];
        if (stream) {
          remoteAudioRef.current.srcObject = stream;
          remoteAudioRef.current.play().catch((err) => console.error('Audio play error:', err));
        }
      };

      peerConnectionRef.current.oniceconnectionstatechange = () => {
        console.log('ICE Connection State:', peerConnectionRef.current.iceConnectionState);
        if (['connected', 'completed'].includes(peerConnectionRef.current.iceConnectionState)) {
          console.log('WebRTC connection established');
          startAudioCapture();
        } else if (peerConnectionRef.current.iceConnectionState === 'failed') {
          console.error('WebRTC connection failed');
          notify('Failed to establish voice connection.', { toastId: 'voice-connection-failed' });
          endCall();
        }
      };

      stream.getTracks().forEach((track) => {
        peerConnectionRef.current.addTrack(track, stream);
      });

      peerConnectionRef.current.onicecandidate = (event) => {
        if (event.candidate) {
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
        }
      };

      sendSignal({ from: currentUserId, signal: { type: 'ready', sdp: '' } });
    } catch (error) {
      console.error('Error accessing audio devices:', error);
      notify('Failed to access microphone.', { toastId: 'media-access-failed' });
      endCall();
    }
  };

  const startCall = async () => {
    console.log('Starting call...');
    userName = recipientName;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      localAudioRef.current.srcObject = stream;

      const iceServers = [
        { urls: 'stun:stun.l.google.com:19302' },
        {
          urls: 'turn:16.170.203.109:3478?transport=tcp',
          username: 'username1',
          credential: 'key1',
        },
      ];

      peerConnectionRef.current = new RTCPeerConnection({ iceServers, iceTransportPolicy: 'all' });
      peerConnectionRef.current.ontrack = (event) => {
        const stream = event.streams[0];
        if (stream) {
          remoteAudioRef.current.srcObject = stream;
          remoteAudioRef.current.play().catch((err) => console.error('Audio play error:', err));
        }
      };

      peerConnectionRef.current.oniceconnectionstatechange = () => {
        console.log('ICE Connection State:', peerConnectionRef.current.iceConnectionState);
        if (peerConnectionRef.current.iceConnectionState === 'connected') {
          console.log('WebRTC connection established');
          startAudioCapture();
        } else if (peerConnectionRef.current.iceConnectionState === 'failed') {
          console.error('WebRTC connection failed');
          notify('Failed to establish voice connection.', { toastId: 'voice-connection-failed' });
          endCall();
        }
      };

      stream.getTracks().forEach((track) => {
        peerConnectionRef.current.addTrack(track, stream);
      });

      peerConnectionRef.current.onicecandidate = (event) => {
        if (event.candidate) {
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
        }
      };

      const offer = await peerConnectionRef.current.createOffer();
      await peerConnectionRef.current.setLocalDescription(offer);
      sendSignal({ from: currentUserId, signal: offer, roomId });
    } catch (error) {
      console.error('Error starting voice call:', error);
      notify('Failed to start voice call.', { toastId: 'start-call-failed' });
      endCall();
    }
  };

  const handleSignal = async (signalMessage) => {
    console.log('Received signal message:', JSON.stringify(signalMessage, null, 2));
    if (signalMessage.signal) {
      try {
        if (signalMessage.signal.type === 'offer') {
          if (peerConnectionRef.current && !isCaller) {
            await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
            const answer = await peerConnectionRef.current.createAnswer();
            await peerConnectionRef.current.setLocalDescription(answer);
            sendSignal({ from: currentUserId, signal: { type: 'answer', sdp: answer.sdp } });
          }
        } else if (signalMessage.signal.type === 'answer') {
          if (isCaller && peerConnectionRef.current) {
            if (!peerConnectionRef.current.remoteDescription) {
              await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
            }
          }
        } else if (signalMessage.signal.type === 'candidate') {
          const { iceCandidate } = signalMessage.signal;
          if (iceCandidate.candidate && iceCandidate.sdpMid !== null && iceCandidate.sdpMLineIndex !== null) {
            const iceCandidateToAdd = new RTCIceCandidate({
              candidate: iceCandidate.candidate,
              sdpMid: iceCandidate.sdpMid,
              sdpMLineIndex: iceCandidate.sdpMLineIndex,
            });
            try {
              await peerConnectionRef.current.addIceCandidate(iceCandidateToAdd);
              console.log('ICE candidate added:', iceCandidateToAdd);
            } catch (error) {
              console.error('Error adding ICE candidate:', error);
            }
          }
        } else if (signalMessage.signal.type === 'ready' && isCaller) {
          setIsWaiting(false);
          ringtoneRef.current.pause();
          ringtoneRef.current.currentTime = 0;
          setTimeout(() => {
            startCall();
          }, 1000);
        } else if (signalMessage.signal.type === 'endCall') {
          if (!hasNotifiedEndCall.current) {
            hasNotifiedEndCall.current = true;
            notify(`${userName || signalMessage.from} interrupted the conversation.`, {
              toastId: `end-call-${roomId}`,
            });
          }
          terminationInitiator = true;
          endCall();
        } else if (signalMessage.signal.type === 'rejectCall' && isCaller) {
          setIsWaiting(false);
          ringtoneRef.current.pause();
          ringtoneRef.current.currentTime = 0;
          notify(`${recipientName} rejected the call.`, { toastId: `reject-call-${roomId}` });
          terminationInitiator = true;
          endCall();
        } else if (signalMessage.signal.type === 'acceptCall' && isCaller) {
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
    }
  };

  const sendSignal = (signalMessage) => {
    if (stompClient.current && stompClient.current.connected) {
      stompClient.current.publish({
        destination: `/app/signal/${roomId}`,
        body: JSON.stringify(signalMessage),
      });
      console.log('Signal sent successfully:', signalMessage);
    } else {
      console.error('Cannot send signal: STOMP client is not connected.');
    }
  };

  const endCall = () => {
    console.log('Ending call...');

    if (peerConnectionRef.current) {
      peerConnectionRef.current.close();
      peerConnectionRef.current = null;
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
          const endCallMessage = {
            type: 'endCall',
            roomId,
            callerId: currentUserId,
            recipientId: incomingCall.recipientId,
          };
          stompClient.current.publish({
            destination: `/app/call/${incomingCall.recipientId}`,
            body: JSON.stringify(endCallMessage),
          });
        }
      }

      stompClient.current.deactivate();
    }

    navigate('/');
  };

  return (
    <div className="voice-call-container">
      <div className="voice-call-header">
        <h2 className="voice-call-title">Voice communication with {isCaller ? recipientName : callerName}</h2>
      </div>
      <div className="audio-container">
        <audio ref={localAudioRef} autoPlay muted />
        <audio ref={remoteAudioRef} autoPlay />
      </div>
      <div className="time-container">
        <p className="start-time">Start: --:--</p>
        <p className="current-time">Current: --:--</p>
      </div>
      <div className="chart-container">
        <Line
          data={chartData}
          options={{
            responsive: true,
            maintainAspectRatio: false,
            scales: {
              x: {
                title: { display: true, text: 'Time' },
              },
              y: {
                title: { display: true, text: 'Activity Level' },
              },
            },
          }}
        />
      </div>
      <div className="transcription-container">
        <h3 className="transcription-label">Your Voice</h3>
        <div className="transcription-scroll" ref={transcriptionRef}>
          <p className="transcription-text">{transcription}</p>
        </div>
      </div>
      <div className="transcription-container">
        <h3 className="transcription-label">Friend's Voice</h3>
        <div className="transcription-scroll" ref={friendTranscriptionRef}>
          <p className="transcription-text">{friendTranscription}</p>
        </div>
      </div>
      <button className="end-call-button-voice" onClick={endCall}>
        End Call
      </button>
    </div>
  );
};

export default VoiceCallWithTranslate;