import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import IncomingCallNotification from './IncomingCallNotification';
import { Line } from 'react-chartjs-2';
import { Chart, registerables } from 'chart.js';
import { useNotification } from '../context/NotificationContext';
import '../styles/VoiceCall.css';

Chart.register(...registerables); // Регистрация всех необходимых модулей

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
  const hasNotifiedEndCall = useRef(false); // Флаг для предотвращения дублирования тостов в endCall

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
        borderColor: 'rgba(74, 144, 226, 1)',
        backgroundColor: 'rgba(74, 144, 226, 0.2)',
        borderWidth: 2,
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

        stompClient.current.subscribe(`/topic/room/${roomId}`, (message) => {
          console.log('Subscribed to /topic/room/' + roomId);
          const signalMessage = JSON.parse(message.body);
          handleSignal(signalMessage);
        });

        if (!isCaller && incomingCall) {
          console.log('Incoming call:', incomingCall);
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
          urls: 'turn:16.170.203.109:3478?transport=tcp',
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
          urls: 'turn:16.170.203.109:3478?transport=tcp',
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
    console.log('Received signal message:', signalMessage);

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
              notify(`${userName} interrupted the conversation.`, { toastId: `end-call-${Date.now()}` });
            } else {
              notify(`Пользователь с ID ${signalMessage.from} interrupted the conversation.`, {
                toastId: `end-call-${Date.now()}`,
              });
            }
          }
          terminationInitiator = true;
          endCall();
        } else if (signalMessage.signal.type === 'rejectCall' && isCaller) {
          console.log('Received rejectCall signal.');
          setIsWaiting(false);
          ringtoneRef.current.pause();
          ringtoneRef.current.currentTime = 0;
          notify(`${recipientName} rejected the call.`, { toastId: `reject-call-${Date.now()}` });
          endCall();
        } else if (signalMessage.signal.type === 'acceptCall' && isCaller) {
          console.log('Received acceptCall signal.');
          setIsWaiting(false);
          ringtoneRef.current.pause();
          ringtoneRef.current.currentTime = 0;
          notify(`${recipientName} accepted the call.`, { toastId: `accept-call-${Date.now()}` });
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
    console.log('Ending call...');

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
      <div className="chart-container">
        <Line
          data={chartData}
          options={{
            responsive: true,
            maintainAspectRatio: false,
            scales: {
              x: {
                title: {
                  display: true,
                  text: 'Time',
                },
              },
              y: {
                title: {
                  display: true,
                  text: 'Activity Level',
                },
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