import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import IncomingCallNotification from './IncomingCallNotification';

const VoiceCall = ({ recipientId }) => {
    const { roomId } = useParams(); 
    const location = useLocation();
    const { incomingCall, isCaller, currentUserId } = location.state || { incomingCall: null, isCaller: false, currentUserId: null };
    const localAudioRef = useRef(null);
    const remoteAudioRef = useRef(null);
    const peerConnectionRef = useRef(null);
    const stompClient = useRef(null);
    const [incomingCallState, setIncomingCallState] = useState(incomingCall);
    const [isReadyToCall, setIsReadyToCall] = useState(false); 

    useEffect(() => {
        console.log('isCaller: ' + isCaller);
        console.log("currentUserId:", currentUserId);

        if (!currentUserId) {
            console.error('currentUserId is undefined. Check the props being passed to VoiceCall.');
            return;
        }

        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            onConnect: () => {
                console.log('Connected to WebSocket');
                console.log("Room ID from URL:", roomId); 

                // Устанавливаем состояние готовности только для принимающего
                if (!isCaller) {
                    setIsReadyToCall(true);
                }

                if (!isCaller && incomingCall) {
                    console.log('Incoming call:', incomingCall);
                    handleIncomingCall(incomingCall);
                }

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
        if (callRequest.signal) {
            console.log('Function handleSignal in VoiceCall.js calling...');
            handleSignal(callRequest.signal);
        }
    };

    const acceptCall = async () => {
        console.log('Function acceptCall in VoiceCall.js working...');
        setIncomingCallState(null);
    
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            localAudioRef.current.srcObject = stream;
            console.log('Audio stream obtained successfully.');
    
            peerConnectionRef.current = new RTCPeerConnection();
            console.log('RTCPeerConnection initialized:', peerConnectionRef.current);
            stream.getTracks().forEach(track => peerConnectionRef.current.addTrack(track, stream));
    
            peerConnectionRef.current.onicecandidate = (event) => {
                console.log('onicecandidate event fired:', event);
                if (event.candidate) {
                    console.log('ICE candidate generated:', event.candidate);
                    sendSignal({
                        from: currentUserId,
                        signal: {
                            type: 'candidate', // Указываем тип
                            candidate: event.candidate.candidate,
                            sdpMid: event.candidate.sdpMid,
                            sdpMLineIndex: event.candidate.sdpMLineIndex
                        }
                    });
                } else {
                    console.log('No more ICE candidates.');
                }
            };
    
            peerConnectionRef.current.ontrack = (event) => {
                remoteAudioRef.current.srcObject = event.streams[0];
                console.log('Remote stream received:', event.streams[0]);
            };
    
            if (incomingCallState.signal) {
                console.log('Setting remote description for incoming call...');
                await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(incomingCallState.signal));
                const answer = await peerConnectionRef.current.createAnswer();
                await peerConnectionRef.current.setLocalDescription(answer);
                sendSignal({ from: currentUserId, signal: { type: 'answer', sdp: answer.sdp }, roomId });
            }
        } catch (error) {
            console.error('Error in acceptCall:', error);
        }
    };

    const rejectCall = () => {
        setIncomingCallState(null);
        console.log(`Call from ${incomingCallState.callerId} rejected.`);
    };

    const startCall = async () => {
        console.log('Function startCall in Home.js working...');

        // Ожидание готовности принимающего
        while (!isReadyToCall) {
            await new Promise(resolve => setTimeout(resolve, 100)); // Пауза в 100 мс
        }

        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            localAudioRef.current.srcObject = stream;
            console.log('Local audio stream obtained.');
    
            peerConnectionRef.current = new RTCPeerConnection();
            console.log('RTCPeerConnection initialized:', peerConnectionRef.current);
    
            stream.getTracks().forEach(track => {
                peerConnectionRef.current.addTrack(track, stream);
                console.log('Track added to peer connection:', track);
            });
    
            peerConnectionRef.current.onicecandidate = (event) => {
                if (event.candidate) {
                    console.log('ICE candidate generated:', event.candidate);
                    sendSignal({
                        from: currentUserId,
                        signal: {
                            type: 'candidate', // Указываем тип
                            candidate: event.candidate.candidate,
                            sdpMid: event.candidate.sdpMid,
                            sdpMLineIndex: event.candidate.sdpMLineIndex
                        }
                    });
                }
            };
    
            peerConnectionRef.current.ontrack = (event) => {
                remoteAudioRef.current.srcObject = event.streams[0];
                console.log('Remote track received:', event.streams[0]);
            };
    
            const offer = await peerConnectionRef.current.createOffer();
            await peerConnectionRef.current.setLocalDescription(offer);
            console.log('Local offer created and set:', offer);
    
            sendSignal({ 
                from: currentUserId, 
                signal: { type: 'offer', sdp: offer.sdp }, // Указываем тип
                roomId 
            });
        } catch (error) {
            console.error('Error in startCall:', error);
        }
    };

    const handleSignal = async (signalMessage) => {
        console.log('Received signal message:', signalMessage);
        
        if (signalMessage.signal) {
            console.log('Processing signal type:', signalMessage.signal.type);
            
            if (signalMessage.signal.type === 'offer') {
                console.log('Handling offer...');
                try {
                    // Устанавливаем удаленное описание
                    await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
                    const answer = await peerConnectionRef.current.createAnswer();
                    await peerConnectionRef.current.setLocalDescription(answer);
                    console.log('Sending answer:', answer);
                    sendSignal({ from: currentUserId, signal: { type: 'answer', sdp: answer.sdp } });
                } catch (error) {
                    console.error('Error handling offer:', error);
                }
            } else if (signalMessage.signal.type === 'answer') {
                console.log('Handling answer...');
                if (peerConnectionRef.current.remoteDescription) {
                    try {
                        await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
                    } catch (error) {
                        console.error('Error setting remote description:', error);
                    }
                } else {
                    console.error('Cannot set remote description: No remote offer found.');
                }
            } else if (signalMessage.signal.type === 'candidate') {
                console.log('Handling ICE candidate:', signalMessage.signal.candidate);
                const { candidate, sdpMid, sdpMLineIndex } = signalMessage.signal;
                if (candidate && sdpMid !== null && sdpMLineIndex !== null) {
                    const iceCandidate = new RTCIceCandidate({
                        candidate,
                        sdpMid,
                        sdpMLineIndex
                    });
                    await peerConnectionRef.current.addIceCandidate(iceCandidate);
                    console.log('ICE candidate added:', iceCandidate);
                } else {
                    console.error('ICE candidate is missing required fields:', signalMessage.signal);
                }
            }
        } else {
            console.error('Signal message does not contain signal:', signalMessage);
        }
    };

    const sendSignal = (signalMessage) => {
        console.log('Function sendSignal in VoiceCall.js working...');
        console.log("Sending signal:", JSON.stringify(signalMessage, null, 2));
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`, 
            body: JSON.stringify(signalMessage),
        });
        console.log('Signal sent successfully.');
    };

    return (
        <div>
            <h2>Calling User ID: {recipientId}</h2>
            <audio ref={localAudioRef} autoPlay muted />
            <audio ref={remoteAudioRef} autoPlay />
            {!isCaller && incomingCallState && (
                <IncomingCallNotification 
                    callerName={incomingCallState.callerId} 
                    onAccept={acceptCall} 
                    onReject={rejectCall} 
                />
            )}
            {isCaller && (
                <button onClick={startCall}>Start Call</button>
            )}
        </div>
    );
};

export default VoiceCall;