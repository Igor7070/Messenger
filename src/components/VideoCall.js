import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useNotification } from './NotificationContext';
import { ToastContainer, toast } from 'react-toastify';
import '../styles/VideoCall.css'; // Новый файл стилей

const VideoCall = ({ recipientId }) => {
    const navigate = useNavigate();
    const notify = useNotification();
    const { roomId } = useParams();
    const location = useLocation();
    const { incomingCall, isCaller, currentUserId, callerName, recipientName } = location.state || 
        { incomingCall: null, isCaller: false, currentUserId: null, callerName: '', recipientName: '' };
    const localVideoRef = useRef(null);
    const remoteVideoRef = useRef(null);
    const peerConnectionRef = useRef(null);
    const stompClient = useRef(null);
    const [incomingCallState, setIncomingCallState] = useState(incomingCall);
    const [isWaiting, setIsWaiting] = useState(false);

    const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));

    useEffect(() => {
        if (isCaller) {
            setIsWaiting(true);
            ringtoneRef.current.loop = true;
            ringtoneRef.current.play().catch(error => {
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
            },
            onWebSocketError: (error) => {
                console.error('WebSocket error:', error);
            },
            onDisconnect: () => {
                console.log('WebSocket disconnected');
            }
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
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
            localVideoRef.current.srcObject = stream;
            console.log('Video and audio stream obtained successfully.');
    
            const iceServers = [
                { urls: 'stun:stun.l.google.com:19302' },
                {
                    urls: 'turn:16.170.203.109:3478?transport=tcp',
                    username: 'username2',
                    credential: 'key2'
                }
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
                    remoteVideoRef.current.srcObject = stream;
                    remoteVideoRef.current.play().catch(err => console.error('Video play error:', err));
                } else {
                    console.error('No streams received in ontrack event');
                }
            };
    
            stream.getTracks().forEach(track => {
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
                                sdpMLineIndex: event.candidate.sdpMLineIndex.toString()
                            }
                        }
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
                    notify('Failed to establish video connection.');
                    endCall();
                }
            };
    
            sendSignal({ from: currentUserId, signal: { type: 'ready' } });
        } catch (error) {
            console.error('Error accessing media devices:', error);
            notify('Failed to access camera or microphone.');
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
        console.log('userName: ' + userName);

        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
            localVideoRef.current.srcObject = stream;
            console.log('Local video and audio stream obtained.');

            const iceServers = [
                { urls: 'stun:stun.l.google.com:19302' },
                {
                    urls: 'turn:16.170.203.109:3478?transport=tcp',
                    username: 'username1',
                    credential: 'key1'
                }
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
                    remoteVideoRef.current.srcObject = stream;
                    remoteVideoRef.current.play().catch(err => console.error('Video play error:', err));
                } else {
                    console.error('No streams received in ontrack event');
                }
            };

            stream.getTracks().forEach(track => {
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
                            sdp: "",
                            iceCandidate: {
                                candidate: event.candidate.candidate,
                                sdpMid: event.candidate.sdpMid,
                                sdpMLineIndex: event.candidate.sdpMLineIndex.toString()
                            }
                        }
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
            console.error('Error starting video call:', error);
        }
    };

    const handleSignal = async (signalMessage) => {
        console.log('Received signal message:', JSON.stringify(signalMessage, null, 2));
    
        if (!signalMessage.from || !signalMessage.signal) {
            console.error('Invalid signal message format:', signalMessage);
            return;
        }
    
        // Игнорируем собственные сообщения
        if (signalMessage.from === currentUserId) {
            console.log('Ignoring own message from:', signalMessage.from);
            return;
        }
    
        const { type, sdp, iceCandidate } = signalMessage.signal;
        console.log('Processing signal type:', type);
    
        try {
            switch (type) {
                case 'offer':
                    console.log('Handling offer...');
                    if (!isCaller) {
                        if (!peerConnectionRef.current) {
                            console.error('PeerConnection is not initialized for offer, initializing...');
                            await handleIncomingCall(incomingCall); // Повторная инициализация, если PeerConnection отсутствует
                        }
    
                        console.log('Received offer SDP:', sdp);
                        await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
                        const answer = await peerConnectionRef.current.createAnswer();
                        await peerConnectionRef.current.setLocalDescription(answer);
                        console.log('Sending answer:', answer);
                        sendSignal({ from: currentUserId, signal: { type: 'answer', sdp: answer.sdp } });
                    } else {
                        console.log('Offer received by caller, ignoring.');
                    }
                    break;
    
                case 'answer':
                    console.log('Handling answer...');
                    if (isCaller && peerConnectionRef.current) {
                        if (peerConnectionRef.current.remoteDescription) {
                            console.log('Remote description already set, ignoring answer.');
                        } else {
                            console.log('Setting remote answer SDP:', sdp);
                            await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp }));
                            console.log('Remote answer set successfully');
                        }
                    }
                    break;
    
                case 'candidate':
                    console.log('Handling ICE candidate:', iceCandidate);
                    if (peerConnectionRef.current && iceCandidate && iceCandidate.candidate && iceCandidate.sdpMid !== null && iceCandidate.sdpMLineIndex !== null) {
                        const iceCandidateToAdd = new RTCIceCandidate({
                            candidate: iceCandidate.candidate,
                            sdpMid: iceCandidate.sdpMid,
                            sdpMLineIndex: parseInt(iceCandidate.sdpMLineIndex, 10)
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
                    break;
    
                case 'ready':
                    console.log('Received ready signal from the other party.');
                    if (isCaller) {
                        setIsWaiting(false);
                        ringtoneRef.current.pause();
                        ringtoneRef.current.currentTime = 0;
                        setTimeout(() => {
                            startCall();
                        }, 1000);
                    }
                    break;
    
                case 'acceptCall':
                    console.log('Received acceptCall signal.');
                    if (isCaller) {
                        setIsWaiting(false);
                        ringtoneRef.current.pause();
                        ringtoneRef.current.currentTime = 0;
                        notify(`${recipientName} accepted the video call.`);
                    }
                    break;
    
                case 'rejectCall':
                    console.log('Received rejectCall signal.');
                    if (isCaller) {
                        setIsWaiting(false);
                        ringtoneRef.current.pause();
                        ringtoneRef.current.currentTime = 0;
                        notify(`${recipientName} rejected the video call.`);
                        endCall();
                    }
                    break;
    
                case 'endCall':
                    console.log('Call ended by the other party.');
                    if (userName) {
                        notify(`${userName} interrupted the video conversation.`);
                    } else {
                        notify(`User ${signalMessage.from} interrupted the video conversation.`);
                    }
                    terminationInitiator = true;
                    endCall();
                    break;
    
                default:
                    console.warn('Unknown signal type:', type);
                    break;
            }
        } catch (error) {
            console.error('Error processing signal:', error);
        }
    };
    
    const sendSignal = (signalMessage) => {
        console.log('Function sendSignal in VideoCall.js working...');
        console.log("Sending signal:", JSON.stringify(signalMessage, null, 2));
        console.log("Raw message:", `/app/signal/${roomId}`, JSON.stringify(signalMessage));
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`,
            body: JSON.stringify(signalMessage),
        });
        console.log('Signal sent successfully.');
    };

    const endCall = () => {
        console.log('Ending video call...');

        if (peerConnectionRef.current) {
            peerConnectionRef.current.close();
            peerConnectionRef.current = null;
            console.log('Peer connection closed.');
        }

        if (localVideoRef.current) {
            localVideoRef.current.srcObject = null;
        }
        if (remoteVideoRef.current) {
            remoteVideoRef.current.srcObject = null;
        }

        if (isCaller) {
            setIsWaiting(false);
            ringtoneRef.current.pause();
            ringtoneRef.current.currentTime = 0;
        }

        if (stompClient.current) {
            if (!terminationInitiator) {
                sendSignal({
                    from: currentUserId,
                    signal: { type: 'endCall' }
                });

                if (isCaller && incomingCall?.recipientId) {
                    console.log('incomingCall:', incomingCall);
                    const endCallMessage = {
                        type: 'endCall',
                        roomId,
                        callerId: currentUserId,
                        recipientId: incomingCall.recipientId
                    };
                    console.log('Sending endCall to /app/call/', incomingCall.recipientId, ':', endCallMessage);
                    stompClient.current.publish({
                        destination: `/app/call/${incomingCall.recipientId}`,
                        body: JSON.stringify(endCallMessage)
                    });
                }
            }

            stompClient.current.deactivate();
            console.log('WebSocket connection deactivated in endCall.');
        }

        navigate('/');
    };

    return (
        <div className="video-call-container">
            <div className="video-call-header">
                <h2 className="video-call-title">Video call with {isCaller ? recipientName : callerName}</h2>
            </div>
            <div className="video-container">
                <video ref={remoteVideoRef} className="remote-video" autoPlay playsInline />
                <video ref={localVideoRef} className="local-video" autoPlay playsInline muted />
            </div>
            <button className="end-call-button" onClick={endCall}>End Call</button>
            <ToastContainer />
        </div>
    );
};

export default VideoCall;