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
        console.log('isCaller: ' + isCaller);
        console.log("currentUserId:", currentUserId);

        if (!currentUserId) {
            console.error('currentUserId is undefined. Check the props being passed to VideoCall.');
            return;
        }

        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            onConnect: () => {
                console.log('Connected to WebSocket');
                console.log("Room ID from URL:", roomId);

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
        });
        stompClient.current.activate();

        return () => {
            console.log('Deactivating WebSocket connection...');
            if (stompClient.current) {
                stompClient.current.deactivate();
                console.log('WebSocket connection deactivated.');
            }
        };
    }, [currentUserId, roomId]);

    const handleIncomingCall = async (callRequest) => {
        console.log('Function handleIncomingCall in VideoCall.js working...');
        userName = callerName;
        console.log('userName: ' + userName);
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
            console.log('RTCPeerConnection initialized with TURN server:', peerConnectionRef.current);

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
                            sdp: "",
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

            sendSignal({ from: currentUserId, signal: { type: 'ready', sdp: "" } });
        } catch (error) {
            console.error('Error accessing media devices:', error);
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
        console.log('Received signal message:', signalMessage);

        if (signalMessage.signal) {
            console.log('Processing signal type:', signalMessage.signal.type);

            if (signalMessage.signal.type === 'offer') {
                console.log('Handling offer...');
                try {
                    if (peerConnectionRef.current && !isCaller) {
                        console.log('Installing a remote description (offer)...');
                        console.log('Received signal offer:', signalMessage.signal);
                        console.log('Received offer SDP:', signalMessage.signal.sdp);

                        await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
                        const answer = await peerConnectionRef.current.createAnswer();
                        await peerConnectionRef.current.setLocalDescription(answer);
                        console.log('Sending answer:', answer);
                        sendSignal({ from: currentUserId, signal: { type: 'answer', sdp: answer.sdp } });
                    } else {
                        console.log('peerConnectionRef.current is not initialized or signal received by caller.');
                    }
                } catch (error) {
                    console.error('Error handling offer:', error);
                }
            } else if (signalMessage.signal.type === 'answer') {
                console.log('Handling answer...');
                if (peerConnectionRef.current && peerConnectionRef.current.remoteDescription) {
                    console.log('Cannot set remote description: Already has a remote description.');
                } else if (isCaller) {
                    try {
                        console.log('Installing a remote description (answer)...');
                        await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
                    } catch (error) {
                        console.error('Error setting remote description:', error);
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
                        sdpMLineIndex
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
                setTimeout(() => {
                    startCall();
                }, 1000);
            } else if (signalMessage.signal.type === 'endCall') {
                console.log('Call ended by the other party.');
                console.log('userName: ' + userName);
                if (userName) {
                    notify(`${userName} interrupted the video conversation.`);
                } else {
                    notify(`User ${signalMessage.from} interrupted the video conversation.`);
                }
                terminationInitiator = true;
                endCall();
            } else if (signalMessage.signal.type === 'rejectCall' && isCaller) {
                setIsWaiting(false);
                ringtoneRef.current.pause();
                ringtoneRef.current.currentTime = 0;
                notify(`${recipientName} rejected the video call.`);
                endCall();
            } else if (signalMessage.signal.type === 'acceptCall' && isCaller) {
                setIsWaiting(false);
                ringtoneRef.current.pause();
                ringtoneRef.current.currentTime = 0;
                notify(`${recipientName} accepted the video call.`);
            }
        } else {
            console.error('Signal message does not contain signal:', signalMessage);
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