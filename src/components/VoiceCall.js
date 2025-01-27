import React, { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import IncomingCallNotification from './IncomingCallNotification';

const VoiceCall = ({ currentUserId, roomId, recipientId }) => {
    const localAudioRef = useRef(null);
    const remoteAudioRef = useRef(null);
    const peerConnectionRef = useRef(null);
    const stompClient = useRef(null);
    const [incomingCall, setIncomingCall] = useState(null); // Состояние для входящего вызова

    useEffect(() => {
        const socket = new SockJS('https://unlimitedpossibilities12.org/ws');
        stompClient.current = new Client({
            webSocketFactory: () => socket,
            onConnect: () => {
                console.log('Connected to WebSocket');
                console.log("Room ID from URL:", roomId); // Логируем roomId

                stompClient.current.subscribe(`/topic/calls/${currentUserId}`, (message) => {
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
        setIncomingCall(callRequest); // Устанавливаем входящий вызов
    };

    const acceptCall = async () => {
        console.log('Function acceptCall in VoiceCall.js working...');
        setIncomingCall(null); // Скрыть уведомление

        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        localAudioRef.current.srcObject = stream;

        peerConnectionRef.current = new RTCPeerConnection();
        stream.getTracks().forEach(track => peerConnectionRef.current.addTrack(track, stream));

        peerConnectionRef.current.onicecandidate = (event) => {
            if (event.candidate) {
                sendSignal({ from: currentUserId, signal: event.candidate });
            }
        };

        peerConnectionRef.current.ontrack = (event) => {
            remoteAudioRef.current.srcObject = event.streams[0];
        };

        if (incomingCall.signal) {
            await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(incomingCall.signal));
            const answer = await peerConnectionRef.current.createAnswer();
            await peerConnectionRef.current.setLocalDescription(answer);
            sendSignal({ from: currentUserId, signal: answer });
        }
    };

    const rejectCall = () => {
        setIncomingCall(null); // Скрыть уведомление
        console.log(`Call from ${incomingCall.callerId} rejected.`);
    };

    const startCall = async () => {
        console.log('Function startCall in Home.js working...');
        
        // Создайте уникальный roomId
        const uniqueRoomId = `room-${currentUserId}-${recipientId}`; // Пример формирования roomId
        
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        localAudioRef.current.srcObject = stream;

        peerConnectionRef.current = new RTCPeerConnection();
        stream.getTracks().forEach(track => peerConnectionRef.current.addTrack(track, stream));

        peerConnectionRef.current.onicecandidate = (event) => {
            if (event.candidate) {
                sendSignal({ from: currentUserId, signal: event.candidate });
            }
        };

        peerConnectionRef.current.ontrack = (event) => {
            remoteAudioRef.current.srcObject = event.streams[0];
        };

        const offer = await peerConnectionRef.current.createOffer();
        await peerConnectionRef.current.setLocalDescription(offer);

        // Отправьте сигнал вызова с roomId
        sendSignal({ from: currentUserId, signal: offer, roomId: uniqueRoomId });
    };

    const handleSignal = async (signalMessage) => {
        if (signalMessage.signal) {
            if (signalMessage.signal.type === 'offer') {
                const expectedCallerId = roomId; // Это значение из URL
                if (incomingCall && incomingCall.callerId === expectedCallerId) {
                    console.log("Caller ID matches with roomId.");
                } else {
                    console.log("Caller ID does not match with roomId.");
                }
                await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
                const answer = await peerConnectionRef.current.createAnswer();
                await peerConnectionRef.current.setLocalDescription(answer);
                sendSignal({ from: currentUserId, signal: answer });
            } else if (signalMessage.signal.type === 'answer') {
                await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
            } else {
                await peerConnectionRef.current.addIceCandidate(new RTCIceCandidate(signalMessage.signal));
            }
        }
    };

    const sendSignal = (signalMessage) => {
        stompClient.current.publish({
            destination: `/api/webrtc/signal/${roomId}`, // Используем roomId здесь
            body: JSON.stringify(signalMessage),
        });
    };

    return (
        <div>
            <audio ref={localAudioRef} autoPlay muted />
            <audio ref={remoteAudioRef} autoPlay />
            {incomingCall && (
                <IncomingCallNotification 
                    callerName={incomingCall.callerId} 
                    onAccept={acceptCall} 
                    onReject={rejectCall} 
                />
            )}
            {!incomingCall && (
                <button onClick={startCall}>Start Call</button>
            )}
        </div>
    );
};

export default VoiceCall;