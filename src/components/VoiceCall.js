import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import IncomingCallNotification from './IncomingCallNotification';

const VoiceCall = ({ recipientId }) => {
    const { roomId } = useParams(); 
    const location = useLocation();
    const { incomingCall, isCaller, currentUserId } = location.state || { incomingCall: null, 
        isCaller: false, currentUserId: null };
    const localAudioRef = useRef(null);
    const remoteAudioRef = useRef(null);
    const peerConnectionRef = useRef(null);
    const stompClient = useRef(null);
    const [incomingCallState, setIncomingCallState] = useState(incomingCall);

    useEffect(() => {
        console.log('isCaller: ' + isCaller);
        console.log("currentUserId:", currentUserId); // Логируем currentUserId

        if (!currentUserId) {
            console.error('currentUserId is undefined. Check the props being passed to VoiceCall.');
            return; // Прерываем выполнение, если currentUserId не определен
        }

        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            onConnect: () => {
                console.log('Connected to WebSocket');
                console.log("Room ID from URL:", roomId); 

                stompClient.current.subscribe(`/topic/calls/${currentUserId}`, (message) => {
                    console.log('Incoming message:', message.body);
                    const callRequest = JSON.parse(message.body);
                    handleIncomingCall(callRequest);
                });
                stompClient.current.subscribe(`/topic/room/${roomId}`, (message) => {
                    const signalMessage = JSON.parse(message.body);
                    handleSignal(signalMessage);
                });
            },
        });
        stompClient.current.activate();

        return () => {
            stompClient.current.deactivate();
        };
    }, [currentUserId, roomId]);

    const handleIncomingCall = (callRequest) => {
        console.log('Function handleIncomingCall in VoiceCall.js working...');
        setIncomingCallState(callRequest);
        // Обработка сигнала, если он есть
        if (callRequest.signal) {
            console.log('Function handleSignal in VoiceCall.js calling...');
            handleSignal(callRequest.signal);
        }
    };

    const acceptCall = async () => {
        console.log('Function acceptCall in VoiceCall.js working...');
        setIncomingCallState(null);

        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        localAudioRef.current.srcObject = stream;

        peerConnectionRef.current = new RTCPeerConnection();
        stream.getTracks().forEach(track => peerConnectionRef.current.addTrack(track, stream));

        peerConnectionRef.current.onicecandidate = (event) => {
            if (event.candidate) {
                sendSignal({
                    from: currentUserId,
                    signal: {
                        candidate: event.candidate.candidate,
                        sdpMid: event.candidate.sdpMid,
                        sdpMLineIndex: event.candidate.sdpMLineIndex
                    }
                });
            }
        };

        peerConnectionRef.current.ontrack = (event) => {
            remoteAudioRef.current.srcObject = event.streams[0];
        };

        if (incomingCallState.signal) {
            await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(incomingCallState.signal));
            const answer = await peerConnectionRef.current.createAnswer();
            await peerConnectionRef.current.setLocalDescription(answer);
            sendSignal({ from: currentUserId, signal: answer });
        }
    };

    const rejectCall = () => {
        setIncomingCallState(null);
        console.log(`Call from ${incomingCallState.callerId} rejected.`);
    };

    const startCall = async () => {
        console.log('Function startCall in Home.js working...');
        
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        localAudioRef.current.srcObject = stream;

        peerConnectionRef.current = new RTCPeerConnection();
        stream.getTracks().forEach(track => peerConnectionRef.current.addTrack(track, stream));

        peerConnectionRef.current.onicecandidate = (event) => {
            if (event.candidate) {
                sendSignal({
                    from: currentUserId,
                    signal: {
                        candidate: event.candidate.candidate,
                        sdpMid: event.candidate.sdpMid,
                        sdpMLineIndex: event.candidate.sdpMLineIndex
                    }
                });
            }
        };

        peerConnectionRef.current.ontrack = (event) => {
            remoteAudioRef.current.srcObject = event.streams[0];
        };

        const offer = await peerConnectionRef.current.createOffer();
        await peerConnectionRef.current.setLocalDescription(offer);

        sendSignal({ from: currentUserId, signal: offer, roomId });
    };

    const handleSignal = async (signalMessage) => {
        if (signalMessage.signal) {
            if (signalMessage.signal.type === 'offer') {
                await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
                const answer = await peerConnectionRef.current.createAnswer();
                await peerConnectionRef.current.setLocalDescription(answer);
                sendSignal({ from: currentUserId, signal: answer });
            } else if (signalMessage.signal.type === 'answer') {
                await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
            } else {
                // Обработка ICE-кандидата
                const iceCandidate = new RTCIceCandidate({
                    candidate: signalMessage.signal.candidate,
                    sdpMid: signalMessage.signal.sdpMid,
                    sdpMLineIndex: signalMessage.signal.sdpMLineIndex
                });
                await peerConnectionRef.current.addIceCandidate(iceCandidate);
            }
        }
    };

    const sendSignal = (signalMessage) => {
        console.log('Function sendSignal in VoiceCall.js working...');
        console.log("Sending signal:", JSON.stringify(signalMessage, null, 2));
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`, 
            body: JSON.stringify(signalMessage),
        });
    };

    return (
        <div>
            <h2>Calling User ID: {recipientId}</h2>
            <audio ref={localAudioRef} autoPlay muted />
            <audio ref={remoteAudioRef} autoPlay />
            {incomingCallState && (
                <IncomingCallNotification 
                    callerName={incomingCallState.callerId} 
                    onAccept={acceptCall} 
                    onReject={rejectCall} 
                />
            )}
            {!incomingCallState && (
                <button onClick={startCall}>Start Call</button>
            )}
        </div>
    );
};

export default VoiceCall;