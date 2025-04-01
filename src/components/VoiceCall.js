import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import IncomingCallNotification from './IncomingCallNotification';
import { Line } from 'react-chartjs-2';
import { Chart, registerables } from 'chart.js'; 
import { useNotification } from './NotificationContext'; // Импортируем контекст
import { ToastContainer, toast } from 'react-toastify';
import '../styles/VoiceCall.css';

Chart.register(...registerables); // Регистрация всех необходимых модулей

const VoiceCall = ({ recipientId }) => {
    const navigate = useNavigate(); // Инициализация navigate
    const notify = useNotification(); // Используем контекст уведомлений
    const { roomId } = useParams(); 
    const location = useLocation();
    const { incomingCall, isCaller, currentUserId, callerName, recipientName } = location.state || 
    { incomingCall: null, isCaller: false, currentUserId: null, callerName: '', recipientName: '' };
    const localAudioRef = useRef(null);
    const remoteAudioRef = useRef(null);
    const peerConnectionRef = useRef(null);
    const stompClient = useRef(null);
    const [incomingCallState, setIncomingCallState] = useState(incomingCall);
    const [isWaiting, setIsWaiting] = useState(false);

    const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));

    useEffect(() => {
        if (isCaller) {
            // Запускаем музыку ожидания
            setIsWaiting(true);
            ringtoneRef.current.loop = true; // Устанавливаем зацикливание
            ringtoneRef.current.play().catch(error => {
                console.error('Error playing ringtone:', error);
            });
        }

        return () => {
            // Останавливаем музыку при размонтировании
            ringtoneRef.current.pause();
            ringtoneRef.current.currentTime = 0;
        };
    }, [isCaller]);

    // Объявляем общую переменную для имени пользователя
    let userName = null;
    let terminationInitiator = false;

    // Состояние для данных графика
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

                if (!isCaller && incomingCall) {
                    console.log('Incoming call:', incomingCall);
                    handleIncomingCall(incomingCall);
                }
            },
        });
        stompClient.current.activate();

        // Пример обновления графика каждую 3 секунду
        const interval = setInterval(() => {
            setChartData(prevData => {
                const newLabels = [...prevData.labels, new Date().toLocaleTimeString()];
                const newData = [...prevData.datasets[0].data, Math.random() * 100]; // Пример случайных данных
                return {
                    labels: newLabels,
                    datasets: [{ ...prevData.datasets[0], data: newData }],
                };
            });
        }, 1000);

        return () => {
            console.log('Deactivating WebSocket connection...');
            stompClient.current.deactivate();
            console.log('WebSocket connection deactivated.');
            clearInterval(interval); // Убираем интервал при размонтировании компонента
        };
    }, [currentUserId, roomId]);

    const handleIncomingCall = async (callRequest) => {
        console.log('Function handleIncomingCall in VoiceCall.js working...');
        userName = callerName;
        console.log('userName: ' + userName);
        console.log('callRequest:', JSON.stringify(callRequest, null, 2)); // Улучшенное логирование
        setIncomingCallState(callRequest);
        
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            localAudioRef.current.srcObject = stream;
            console.log('Audio stream obtained successfully.');

        const iceServers = [
            {
                urls: 'stun:stun.l.google.com:19302'
            },
            {
                urls: 'turn:16.170.203.109:3478?transport=tcp',
                username: 'username2', 
                credential: 'key2'     
            }
        ];

        // Инициализация peerConnectionRef перед обработкой сигнала
        peerConnectionRef.current = new RTCPeerConnection({ iceServers,
            iceTransportPolicy: 'all' });
        console.log('RTCPeerConnection initialized with TURN server: ', peerConnectionRef.current);

        //------------------------------------------------------------------------------------------

        peerConnectionRef.current.ontrack = (event) => {
            /*remoteAudioRef.current.srcObject = event.streams[0];
            console.log('Remote stream received:', event.streams[0]);*/
            console.log('ontrack triggered:', event);
            console.log('Event streams:', event.streams);
            console.log('Event track:', event.track);
            if (event.streams[0]) {
                console.log('Stream active:', event.streams[0].active);
                console.log('Track enabled:', event.track.enabled);
                remoteAudioRef.current.srcObject = event.streams[0];
                remoteAudioRef.current.play().catch(err => console.error('Audio play error:', err));
            } else {
                console.error('No streams in ontrack event');
            }
        };

        stream.getTracks().forEach(track => {
            peerConnectionRef.current.addTrack(track, stream);
            console.log('Track added to peer connection:', track);
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
                        sdp: "", // Если требуется, можно оставить пустым
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
    };

    const rejectCall = () => {
        setIncomingCallState(null);
        console.log(`Call from ${incomingCallState.callerId} rejected.`);
    };

    const startCall = async () => {
        console.log('Function startCall in Home.js working...');
        userName = recipientName;
        console.log('userName: ' + userName);
    
        // Получаем доступ к аудиопотоку
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        localAudioRef.current.srcObject = stream;
        console.log('Local audio stream obtained.');
    
        // Определяем STUN и TURN серверы
        const iceServers = [
            {
                urls: 'stun:stun.l.google.com:19302'
            },
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

        //------------------------------------------------------------------------------------------
    
        // Обработчик входящих треков
        peerConnectionRef.current.ontrack = (event) => {
            /*remoteAudioRef.current.srcObject = event.streams[0];
            console.log('Remote stream received:', event.streams[0]);*/
            console.log('ontrack triggered:', event);
            console.log('Event streams:', event.streams);
            console.log('Event track:', event.track);
            if (event.streams[0]) {
                console.log('Stream active:', event.streams[0].active);
                console.log('Track enabled:', event.track.enabled);
                remoteAudioRef.current.srcObject = event.streams[0];
                remoteAudioRef.current.play().catch(err => console.error('Audio play error:', err));
            } else {
                console.error('No streams in ontrack event');
            }
        };

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
                    if (peerConnectionRef.current && !isCaller) { // Проверка инициализации
                        console.log('Installing a remote description (offer)...');
                        // Логируем полученное предложение (offer)
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
    
                const { iceCandidate } = signalMessage.signal; // Извлекаем объект iceCandidate
                const { candidate, sdpMid, sdpMLineIndex } = iceCandidate; // Достаем необходимые поля

                if (candidate && sdpMid !== null && sdpMLineIndex !== null) {
                    const iceCandidateToAdd = new RTCIceCandidate({
                        candidate,
                        sdpMid,
                        sdpMLineIndex
                    });
                    //await peerConnectionRef.current.addIceCandidate(iceCandidateToAdd);
                    //console.log('ICE candidate added:', iceCandidateToAdd);
                    setTimeout(async () => {
                        try {
                            await peerConnectionRef.current.addIceCandidate(iceCandidateToAdd);
                            console.log('ICE candidate added:', iceCandidateToAdd);
                        } catch (error) {
                            console.error('Error adding ICE candidate:', error);
                        }
                    }, 3); // Задержка в 3 миллисекунды
                } else {
                    console.error('ICE candidate is missing required fields:', signalMessage.signal);
                }
            } else if (signalMessage.signal.type === 'ready' && isCaller) {
                console.log('Received ready signal from the other party.');
                // Если принимающий принял вызов, вызываем startCall у звонящего
                setTimeout(() => {
                    startCall();
                }, 1000);
            } else if (signalMessage.signal.type === 'endCall') {
                console.log('Call ended by the other party.');
                console.log('userName: ' + userName);
                if (userName) {
                    notify(`${userName} interrupted the conversation.`); // Уведомление для принимающего
                } else {
                    notify(`Пользователь с ID ${signalMessage.from} interrupted the conversation.`); // Уведомление с ID если имя не найдено
                }
                terminationInitiator = true;
                endCall(); // Завершение локального вызова
            } else if (signalMessage.signal.type === 'rejectCall' && isCaller) {
                // Останавливаем музыку ожидания
                setIsWaiting(false);
                ringtoneRef.current.pause();
                ringtoneRef.current.currentTime = 0;

                notify(`${recipientName} rejected the call.`);
                endCall();
            } else if (signalMessage.signal.type === 'acceptCall' && isCaller) {
                // Останавливаем музыку ожидания
                setIsWaiting(false);
                ringtoneRef.current.pause();
                ringtoneRef.current.currentTime = 0;

                notify(`${recipientName} accepted the call.`);
                //toast.success(`${recipientName} accepted the call.`);
            }
        } else {
            console.error('Signal message does not contain signal:', signalMessage);
        }
    };

    const sendSignal = (signalMessage) => {
        console.log('Function sendSignal in VoiceCall.js working...');
        console.log("Sending signal:", JSON.stringify(signalMessage, null, 2));
        console.log("Raw message:", `/app/signal/${roomId}`, JSON.stringify(signalMessage));
        stompClient.current.publish({
            destination: `/app/signal/${roomId}`, 
            body: JSON.stringify(signalMessage),
        });
        console.log('Signal sent successfully.');
    };

    const endCall = () => {
        console.log('Ending call...');
    
        // Закрываем RTCPeerConnection, если он существует
        if (peerConnectionRef.current) {
            peerConnectionRef.current.close();
            peerConnectionRef.current = null; // Очищаем ссылку
            console.log('Peer connection closed.');
        }
    
        // Очищаем аудиопотоки
        if (localAudioRef.current) {
            localAudioRef.current.srcObject = null;
        }
        if (remoteAudioRef.current) {
            remoteAudioRef.current.srcObject = null;
        }

        if (stompClient.current) {
            stompClient.current.deactivate();
            console.log('WebSocket connection deactivated in endCall.');
        }
    
        // Отправляем сигнал о завершении вызова
        if (!terminationInitiator) {
            // Выполняем sendSignal только если terminationInitiator равно false
            sendSignal({
                from: currentUserId,
                signal: {
                    type: 'endCall'
                }
            });
        }

        // Перенаправляем пользователя на компонент Home
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
                <Line data={chartData} options={{
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
                }}/>
            </div>
            <button className="end-call-button" onClick={endCall}>End Call</button>
        </div>
    );
};

export default VoiceCall;