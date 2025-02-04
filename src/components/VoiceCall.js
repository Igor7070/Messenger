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

                stompClient.current.subscribe(`/topic/room/${roomId}`, (message) => {
                    console.log('Subscribed to /topic/room/' + roomId);
                    const signalMessage = JSON.parse(message.body);
                    handleSignal(signalMessage);
                });

                /*if (!isCaller) {
                    sendSignal({ from: currentUserId, type: 'ready' }); // Отправляем сигнал
                    console.log('The receiver"s readiness signal has been sent.');
                }*/

                if (!isCaller && incomingCall) {
                    console.log('Incoming call:', incomingCall);
                    handleIncomingCall(incomingCall);
                }
            },
        });
        stompClient.current.activate();

        return () => {
            stompClient.current.deactivate();
        };
    }, [currentUserId, roomId]);

    const handleIncomingCall = async (callRequest) => {
        console.log('Function handleIncomingCall in VoiceCall.js working...');
        console.log('callRequest:', JSON.stringify(callRequest, null, 2)); // Улучшенное логирование
        setIncomingCallState(callRequest);

        // Инициализация peerConnectionRef перед обработкой сигнала с STUN и TURN серверов
        // Определение STUN и TURN серверов
        /*const iceServers = [
            { urls: 'stun:stun.l.google.com:19302' }, // STUN сервер
            {
                urls: 'turn:localhost:3478', // Укажите IP-адрес вашего TURN сервера
                username: 'user1',                // Имя пользователя
                credential: 'password123'         // Пароль
            }
        ];*/
        
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            localAudioRef.current.srcObject = stream;
            console.log('Audio stream obtained successfully.');

        const iceServers = [
            {
                urls: 'turn:16.170.203.109:3478?transport=tcp',
                username: 'username1', 
                credential: 'key1'     
            }
        ];

        // Инициализация peerConnectionRef перед обработкой сигнала
        peerConnectionRef.current = new RTCPeerConnection({ iceServers,
            iceTransportPolicy: 'all' });
        console.log('RTCPeerConnection initialized with TURN server: ', peerConnectionRef.current);

        //------------------------------------------------------------------------------------------

        peerConnectionRef.current.ontrack = (event) => {
            remoteAudioRef.current.srcObject = event.streams[0];
            console.log('Remote stream received:', event.streams[0]);
        };

        stream.getTracks().forEach(track => {
            peerConnectionRef.current.addTrack(track, stream);
        });
        
        // Шаг 2: Установка обработчика события onicecandidate
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
    };

    const acceptCall = async () => {
        console.log('Function acceptCall in VoiceCall.js working...');
        setIncomingCallState(null);
    
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            localAudioRef.current.srcObject = stream;
            console.log('Audio stream obtained successfully.');

            // Добавление TURN сервера
            const iceServers = [
                {
                    urls: 'turn:16.170.203.109:3478?transport=tcp',
                    username: 'username1', 
                    credential: 'key1'     
                }
            ];
    
            // Проверка инициализации peerConnectionRef
            if (!peerConnectionRef.current) {
                peerConnectionRef.current = new RTCPeerConnection({ iceServers });
                console.log('RTCPeerConnection initialized with STUN server: ', peerConnectionRef.current);
            }
    
            stream.getTracks().forEach(track => peerConnectionRef.current.addTrack(track, stream));
    
            peerConnectionRef.current.onicecandidate = (event) => {
                console.log('onicecandidate event fired:', event);
                if (event.candidate) {
                    console.log('ICE candidate generated:', event.candidate);
                    sendSignal({
                        from: currentUserId,
                        signal: {
                            type: 'candidate',
                            sdp: "", // Если требуется, можете оставить пустым
                            iceCandidate: {
                                candidate: event.candidate.candidate,
                                sdpMid: event.candidate.sdpMid,
                                sdpMLineIndex: event.candidate.sdpMLineIndex.toString() // Преобразуем в строку, если необходимо
                            }
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

            console.log('incomingCallState: ' + incomingCallState);
            console.log('peerConnectionRef.current: ' + peerConnectionRef.current);
    
            // Проверка состояния перед установкой удалённого описания
            if (incomingCallState && peerConnectionRef.current) {
                console.log('Setting remote description for incoming call...');

                if (!peerConnectionRef.current.remoteDescription) {
                    console.log('Installing a remote description...');
                    await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(incomingCallState.signal));
                    const answer = await peerConnectionRef.current.createAnswer();
                    await peerConnectionRef.current.setLocalDescription(answer);
                    sendSignal({ from: currentUserId, signal: answer });
                } else {
                    console.warn('Remote description has already been set.');
                }
            } else {
                console.error('No valid signal found in incomingCallState or peerConnectionRef.current is not initialized.');
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
    
        // Получаем доступ к аудиопотоку
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        localAudioRef.current.srcObject = stream;
        console.log('Local audio stream obtained.');
    
        // Определяем STUN и TURN серверы
        const iceServers = [
            //{ urls: 'stun:stun.l.google.com:19302' }, // Добавляем STUN сервер
            {
                urls: 'turn:16.170.203.109:3478?transport=tcp',
                username: 'username1',
                credential: 'key1'
            }
        ];
    
        // Инициализация RTCPeerConnection
        peerConnectionRef.current = new RTCPeerConnection({ iceServers,
            iceTransportPolicy: 'all' });
        console.log('RTCPeerConnection initialized:', peerConnectionRef.current);
    
        // Добавляем треки в peer connection
        stream.getTracks().forEach(track => {
            peerConnectionRef.current.addTrack(track, stream);
            console.log('Track added to peer connection:', track);
        });
    
        // Обработчик ICE кандидатов
        peerConnectionRef.current.onicecandidate = (event) => {
            console.log('onicecandidate event fired');
            if (event.candidate) {
                console.log('ICE candidate generated:', event.candidate);
                sendSignal({
                    from: currentUserId,
                    signal: {
                        type: 'candidate',
                        sdp: "", // Если требуется, можно оставить пустым
                        iceCandidate: {
                            candidate: event.candidate.candidate,
                            sdpMid: event.candidate.sdpMid,
                            sdpMLineIndex: event.candidate.sdpMLineIndex.toString() // Преобразуем в строку, если необходимо
                        }
                    }
                });
            } else {
                console.log('All ICE candidates have been generated.');
            }
        };
    
        // Создание и установка локального описания
        const offer = await peerConnectionRef.current.createOffer();
        await peerConnectionRef.current.setLocalDescription(offer);
        console.log('Local offer created and set:', offer);
    
        // Отправка сигнала с предложением
        sendSignal({ from: currentUserId, signal: offer, roomId });
    
        // Обработчик входящих треков
        peerConnectionRef.current.ontrack = (event) => {
            remoteAudioRef.current.srcObject = event.streams[0];
            console.log('Remote track received:', event.streams[0]);
        };
    
        // Обработчик состояния ICE
        peerConnectionRef.current.onicegatheringstatechange = () => {
            console.log('ICE Gathering State:', peerConnectionRef.current.iceGatheringState);
        };
    };

    const handleSignal = async (signalMessage) => {
        console.log('Received signal message:', signalMessage);
        
        if (signalMessage.signal) {
            console.log('Processing signal type:', signalMessage.signal.type);
            
            if (signalMessage.signal.type === 'offer') {
                console.log('Handling offer...');
                try {
                    if (peerConnectionRef.current) { // Проверка инициализации
                        console.log('Installing a remote description (offer)...');
                        await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
                        const answer = await peerConnectionRef.current.createAnswer();
                        await peerConnectionRef.current.setLocalDescription(answer);
                        console.log('Sending answer:', answer);
                        sendSignal({ from: currentUserId, signal: { type: 'answer', sdp: answer.sdp } });
                    } else {
                        console.error('peerConnectionRef.current is not initialized.');
                    }
                } catch (error) {
                    console.error('Error handling offer:', error);
                }
            } else if (signalMessage.signal.type === 'answer') {
                console.log('Handling answer...');
                if (peerConnectionRef.current && peerConnectionRef.current.remoteDescription) {
                    console.error('Cannot set remote description: Already has a remote description.');
                } else {
                    try {
                        console.log('Installing a remote description (answer)...');
                        await peerConnectionRef.current.setRemoteDescription(new RTCSessionDescription(signalMessage.signal));
                    } catch (error) {
                        console.error('Error setting remote description:', error);
                    }
                }
            } else if (signalMessage.signal.type === 'candidate') {
                console.log('Handling ICE candidate:', signalMessage.signal.iceCandidate);
    
                const { iceCandidate } = signalMessage.signal; // Извлекаем объект iceCandidate
                const { candidate, sdpMid, sdpMLineIndex } = iceCandidate; // Достаем необходимые поля

                if (candidate && sdpMid !== null && sdpMLineIndex !== null) {
                    const iceCandidateToAdd = new RTCIceCandidate({
                        candidate,
                        sdpMid,
                        sdpMLineIndex
                    });
                    await peerConnectionRef.current.addIceCandidate(iceCandidateToAdd);
                    console.log('ICE candidate added:', iceCandidateToAdd);
                } else {
                    console.error('ICE candidate is missing required fields:', signalMessage.signal);
                }
            } else if (signalMessage.signal.type === 'ready') {
                console.log('Received ready signal from the other party.');
                // Если звонящий инициировал вызов, начинаем его
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