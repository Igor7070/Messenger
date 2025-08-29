import React, { useEffect, useRef, useState, useMemo } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { Line } from 'react-chartjs-2';
import { Chart, registerables } from 'chart.js';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import '../styles/GroupCallActivity.css';

Chart.register(...registerables);

const GroupCallActivity = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { state } = location;
  const { participants = [], initiator = {}, roomId: initialRoomId } = state || {};
  const isInitiator = Number(localStorage.getItem('userId')) === initiator.id;
  const currentUserId = localStorage.getItem('userId');
  const roomId = useRef(isInitiator ? crypto.randomUUID() : initialRoomId || null);
  const peerConnections = useRef(new Map());
  const localAudioRef = useRef(null);
  const remoteAudioRefs = useRef(new Map());
  const pendingCandidates = useRef(new Map());
  const pendingStreams = useRef(new Map());
  const [callParticipants, setCallParticipants] = useState(participants);
  const [callDuration, setCallDuration] = useState('00:00');
  const [startTime, setStartTime] = useState('');
  const [currentTime, setCurrentTime] = useState('');
  const [readyUsers, setReadyUsers] = useState(new Set());
  const [isWaiting, setIsWaiting] = useState(isInitiator);
  const [isCallStarted, setIsCallStarted] = useState(false);
  const [isCallEnded, setIsCallEnded] = useState(false);
  const ringtoneRef = useRef(new Audio('https://igor7070.github.io/Messenger/incoming_dial_tone.mp3'));
  const callStartTime = useRef(Date.now());
  const hasNotifiedEndCall = useRef(false);
  const hasSentInvite = useRef(false);
  const hasProcessedInvite = useRef(new Set());
  const unsubscribeRoomRef = useRef(null);
  const offerTimeoutRef = useRef(null);
  const readyTimeoutRef = useRef(null);
  const isMounted = useRef(true);
  const localStream = useRef(null);
  const processedOffers = useRef(new Map());

  const { sendSignal, setGroupSignalHandler, subscribeToRoom } = useCallWebSocket();

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

  const memoizedParticipants = useMemo(() => participants, [JSON.stringify(participants)]);

  console.log('GroupCallActivity: Initial state:', {
    state,
    initialRoomId,
    isInitiator,
    roomId: roomId.current,
    initiator,
    participants: memoizedParticipants,
  });

  useEffect(() => {
    console.log('useEffect called with dependencies:', {
      currentUserId,
      initiatorId: initiator.id,
      isInitiator,
      isCallEnded,
      roomId: roomId.current,
      participants: memoizedParticipants,
    });

    if (!currentUserId || !initiator?.id) {
      console.error('User ID or initiator ID is missing', { currentUserId, initiator });
      toast.error('User ID or initiator ID is missing.');
      navigate('/');
      return;
    }

    if (!roomId.current) {
      console.error('roomId is null, cannot proceed');
      toast.error('Invalid room ID');
      navigate('/');
      return;
    }

    const initializeCall = async () => {
      if (isCallEnded || !isMounted.current) {
        console.log('Call already ended or component unmounted, skipping initialization');
        return;
      }

      if (!localAudioRef.current) {
        console.error('localAudioRef is null');
        toast.error('Audio element not ready');
        endCall();
        return;
      }

      try {
        // Fetch participant details
        const fetchParticipants = async () => {
          try {
            const updatedParticipants = await Promise.all(
              memoizedParticipants.map(async (user) => {
                if (!user?.id) {
                  console.error('Invalid user object in participants:', user);
                  return { id: Date.now(), username: 'Unknown User', avatar: null };
                }
                if (user.id === Number(currentUserId)) {
                  return { ...user, username: user.username || `User ${user.id}` };
                }
                const response = await fetch(`https://unlimitedpossibilities12.org/api/users/${user.id}`, {
                  headers: { Authorization: `Bearer ${localStorage.getItem('authToken')}` },
                });
                if (!response.ok) {
                  console.error(`Failed to fetch user ${user.id}`);
                  return { ...user, username: `User ${user.id}`, avatar: null };
                }
                const userData = await response.json();
                return {
                  id: user.id,
                  username: userData.username || `User ${user.id}`,
                  avatar: userData.avatar || null,
                };
              })
            );
            setCallParticipants(updatedParticipants.filter((user) => user.id));
            console.log('Updated callParticipants:', updatedParticipants);
          } catch (error) {
            console.error('Error fetching participants:', error);
            toast.error('Failed to load participant details');
          }
        };
        await fetchParticipants();

        // Get local stream
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        console.log('Local stream tracks:', stream.getTracks());
        localStream.current = stream;
        localAudioRef.current.srcObject = stream;

        // Assign audio elements from DOM
        memoizedParticipants.forEach((participant) => {
          if (participant?.id && participant.id !== Number(currentUserId)) {
            const audioElement = document.getElementById(`remote-audio-${participant.id}`);
            if (audioElement && !remoteAudioRefs.current.has(String(participant.id))) {
              remoteAudioRefs.current.set(String(participant.id), audioElement);
              console.log(`Assigned existing audio element for user ${participant.id}`);
            }
          }
        });

        // Assign pending streams
        pendingStreams.current.forEach((stream, userId) => {
          const audioElement = remoteAudioRefs.current.get(userId);
          if (audioElement && !audioElement.srcObject) {
            audioElement.srcObject = stream;
            audioElement.play().catch((e) => console.error(`Error playing audio for user ${userId}:`, e));
            console.log(`Assigned pending stream for user ${userId}`);
            pendingStreams.current.delete(userId);
          }
        });

        setGroupSignalHandler(handleSignal);

        // Subscribe to room
        unsubscribeRoomRef.current = subscribeToRoom(
          roomId.current,
          () => {
            console.log(`Successfully subscribed to room ${roomId.current}`);
            if (isInitiator && !hasSentInvite.current) {
              hasSentInvite.current = true;
              sendGroupCallInvite();
            } else if (!isInitiator && !hasSentInvite.current) {
              hasSentInvite.current = true;
              console.log('Sending acceptCall and ready signals');
              sendSignal({ from: currentUserId, signal: { type: 'acceptCall' } }, roomId.current);
              memoizedParticipants
                .filter((p) => p?.id && p.id !== Number(currentUserId))
                .forEach((user) => {
                  if (!hasProcessedInvite.current.has(String(user.id))) {
                    hasProcessedInvite.current.add(String(user.id));
                    sendSignal(
                      { from: currentUserId, to: String(user.id), signal: { type: 'ready' } },
                      roomId.current
                    );
                    console.log(`Sent ready signal to user ${user.id}`);
                  }
                });
              setTimeout(() => {
                if (isMounted.current && !isCallStarted) {
                  initiateOffers();
                }
              }, 1000);
            }
          },
          (error) => {
            console.error('Subscription error:', error);
            toast.error('Failed to connect to call server');
            endCall();
          }
        );

        if (isInitiator) {
          setIsWaiting(true);
          ringtoneRef.current.loop = true;
          ringtoneRef.current.play().catch((error) => {
            if (isMounted.current) console.error('Error playing ringtone:', error);
          });
        }
      } catch (error) {
        console.error('Error initializing call:', error);
        toast.error('Failed to initialize group call');
        endCall();
      }
    };

    if (document.getElementById('local-audio')) {
      localAudioRef.current = document.getElementById('local-audio');
      initializeCall();
    } else {
      console.error('Local audio element not found in DOM');
      toast.error('Audio element not ready');
      setTimeout(() => {
        if (document.getElementById('local-audio') && isMounted.current) {
          localAudioRef.current = document.getElementById('local-audio');
          initializeCall();
        } else {
          endCall();
        }
      }, 1000);
    }

    const timerInterval = setInterval(() => {
      if (!isMounted.current) return;
      const duration = Math.floor((Date.now() - callStartTime.current) / 1000);
      const minutes = Math.floor(duration / 60);
      const seconds = duration % 60;
      setCallDuration(`${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`);
    }, 1000);

    const timeInterval = setInterval(() => {
      if (!isMounted.current) return;
      const now = new Date();
      setCurrentTime(now.toLocaleTimeString('en-US', { hour12: false }));
      if (!startTime) {
        setStartTime(now.toLocaleTimeString('en-US', { hour12: false }));
      }
    }, 1000);

    const chartInterval = setInterval(() => {
      if (!isMounted.current) return;
      setChartData((prevData) => {
        const newLabels = [...prevData.labels, new Date().toLocaleTimeString()];
        const newData = [...prevData.datasets[0].data, Math.random() * 100];
        return {
          labels: newLabels.slice(-20),
          datasets: [{ ...prevData.datasets[0], data: newData.slice(-20) }],
        };
      });
    }, 1000);

    readyTimeoutRef.current = setTimeout(() => {
      if (!isMounted.current) return;
      console.log('readyTimeout check:', { isInitiator, isWaiting, readyUsers: Array.from(readyUsers), isCallStarted });
      if (isInitiator && isWaiting && !isCallStarted) {
        let hasActiveConnections = false;
        peerConnections.current.forEach((pc, userId) => {
          if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
            hasActiveConnections = true;
            console.log(`Active connection detected with user ${userId}, skipping endCall`);
          }
        });
        if (!hasActiveConnections) {
          console.warn('No active connections within 60 seconds, ending call.');
          toast.error('No participants responded to the call.');
          endCall();
        }
      }
    }, 60000);

    offerTimeoutRef.current = setTimeout(() => {
      if (!isMounted.current) return;
      if (!isInitiator && !isCallStarted) {
        console.error('No offer received within 60 seconds, aborting call');
        toast.error('Failed to establish call: no offer received');
        endCall();
      }
    }, 60000);

    return () => {
      isMounted.current = false;
      clearInterval(timerInterval);
      clearInterval(timeInterval);
      clearInterval(chartInterval);
      if (readyTimeoutRef.current) clearTimeout(readyTimeoutRef.current);
      if (offerTimeoutRef.current) {
        clearTimeout(offerTimeoutRef.current);
        offerTimeoutRef.current = null;
      }
      ringtoneRef.current.pause();
      ringtoneRef.current.currentTime = 0;
      peerConnections.current.forEach((pc) => pc.close());
      peerConnections.current.clear();
      pendingStreams.current.clear();
      pendingCandidates.current.clear();
      if (localStream.current) {
        localStream.current.getTracks().forEach((track) => track.stop());
        localStream.current = null;
      }
      if (localAudioRef.current && localAudioRef.current.srcObject) {
        localAudioRef.current.srcObject.getTracks().forEach((track) => track.stop());
        localAudioRef.current.srcObject = null;
      }
      remoteAudioRefs.current.forEach((audio) => {
        if (audio.srcObject) {
          audio.srcObject.getTracks().forEach((track) => track.stop());
          audio.srcObject = null;
        }
        audio.remove();
      });
      remoteAudioRefs.current.clear();
      if (unsubscribeRoomRef.current) {
        unsubscribeRoomRef.current();
        console.log(`Unsubscribed from room ${roomId.current}`);
      }
    };
  }, [currentUserId, initiator?.id, isInitiator, isCallEnded, memoizedParticipants, sendSignal, setGroupSignalHandler, navigate]);

  const sendGroupCallInvite = () => {
    if (!roomId.current) {
      console.error('Cannot send group call invite: roomId is null');
      return;
    }
    const groupCallRequest = {
      type: 'group',
      initiatorId: String(initiator.id || currentUserId),
      initiatorName: initiator.username || `User ${currentUserId}`,
      roomId: roomId.current,
      recipientIds: callParticipants
        .filter((p) => p?.id && p.id !== initiator.id)
        .map((p) => String(p.id)),
    };

    callParticipants
      .filter((p) => p?.id && p.id !== initiator.id)
      .forEach((user) => {
        console.log('GroupCallActivity: Preparing groupCallInvite for user:', user.id, groupCallRequest);
        sendSignal(groupCallRequest, roomId.current, String(user.id));
        console.log(`GroupCallActivity: Sent group call invite to user ${user.id}`, groupCallRequest);
      });
  };

  const initializePeerConnection = async (userId) => {
    try {
      const peerConnection = new RTCPeerConnection({
        iceServers: [
          { urls: 'stun:stun.l.google.com:19302' },
          { urls: 'stun:stun1.l.google.com:19302' },
          { urls: 'stun:stun2.l.google.com:19302' },
        ],
      });

      peerConnection.onicecandidate = (event) => {
        if (event.candidate && isMounted.current) {
          sendSignal(
            {
              from: currentUserId,
              to: userId,
              signal: { type: 'candidate', iceCandidate: event.candidate },
            },
            roomId.current
          );
          console.log(`Sent ICE candidate to user ${userId}:`, event.candidate);
        }
      };

      peerConnection.ontrack = (event) => {
        if (!isMounted.current) return;
        console.log(`ontrack event for user ${userId}:`, event);
        console.log(`Remote stream tracks for user ${userId}:`, event.streams[0].getTracks());
        if (event.streams[0]) {
          const audioElement = remoteAudioRefs.current.get(userId);
          if (audioElement) {
            audioElement.srcObject = event.streams[0];
            audioElement.play().catch((e) => console.error(`Error playing audio for user ${userId}:`, e));
            console.log(`Remote audio track added and played for user ${userId}`);
          } else {
            console.warn(`No audio element found for user ${userId}, delaying stream assignment`);
            pendingStreams.current.set(userId, event.streams[0]);
          }
        }
      };

      peerConnection.oniceconnectionstatechange = () => {
        if (!isMounted.current) return;
        console.log(`ICE Connection State for ${userId}: ${peerConnection.iceConnectionState}, signalingState: ${peerConnection.signalingState}`);
        if (peerConnection.iceConnectionState === 'disconnected' || peerConnection.iceConnectionState === 'failed') {
          console.error(`ICE connection failed for user ${userId}`);
          toast.error(`Connection lost with ${callParticipants.find((u) => u?.id === Number(userId))?.username || `User ${userId}`}`);
          peerConnection.close();
          peerConnections.current.delete(userId);
        }
      };

      if (localStream.current) {
        localStream.current.getTracks().forEach((track) => {
          peerConnection.addTrack(track, localStream.current);
          console.log(`Local stream tracks for user ${userId}:`, localStream.current.getTracks());
        });
      }

      console.log(`PeerConnection initialized for user ${userId}`);
      peerConnections.current.set(userId, peerConnection);
      return peerConnection;
    } catch (error) {
      console.error(`Error initializing PeerConnection for user ${userId}:`, error);
      return null;
    }
  };

  const initiateOffers = async () => {
    if (isCallStarted || !isMounted.current) {
      console.log('Call already started or component unmounted, skipping initiateOffers');
      return;
    }
    console.log('Initiating offers for all ready participants:', Array.from(readyUsers));

    try {
      for (const user of callParticipants) {
        if (user?.id && user.id !== Number(currentUserId) && readyUsers.has(String(user.id))) {
          let peerConnection = peerConnections.current.get(String(user.id));
          if (!peerConnection) {
            peerConnection = await initializePeerConnection(String(user.id));
            if (!peerConnection) {
              console.error(`Failed to initialize PeerConnection for user ${user.id}`);
              continue;
            }
          }
          if (!peerConnection.localDescription) {
            const offer = await peerConnection.createOffer();
            await peerConnection.setLocalDescription(offer);
            sendSignal(
              {
                from: currentUserId,
                to: String(user.id),
                signal: { type: 'offer', sdp: offer.sdp },
              },
              roomId.current
            );
            console.log(`Sent offer to user ${user.id}`);
          } else {
            console.log(`Skipping offer for user ${user.id}: PeerConnection already has localDescription`);
          }
        }
      }
    } catch (error) {
      console.error('Error initiating offers:', error);
      toast.error('Failed to initiate connections.');
    }
  };

  const startCall = async () => {
    if (isCallStarted || !isMounted.current) {
      console.log('Call already started or component unmounted, skipping startCall');
      return;
    }
    setIsCallStarted(true);
    setIsWaiting(false);
    ringtoneRef.current.pause();
    ringtoneRef.current.currentTime = 0;
    console.log('Starting group call for participants:', callParticipants.map((u) => u?.id));

    try {
      if (isInitiator) {
        for (const user of callParticipants) {
          if (user?.id && user.id !== Number(currentUserId) && readyUsers.has(String(user.id))) {
            let peerConnection = peerConnections.current.get(String(user.id));
            if (!peerConnection) {
              peerConnection = await initializePeerConnection(String(user.id));
              if (!peerConnection) {
                console.error(`Failed to initialize PeerConnection for user ${user.id}`);
                continue;
              }
            }
            if (!peerConnection.localDescription) {
              const offer = await peerConnection.createOffer();
              await peerConnection.setLocalDescription(offer);
              sendSignal(
                {
                  from: currentUserId,
                  to: String(user.id),
                  signal: { type: 'offer', sdp: offer.sdp },
                },
                roomId.current
              );
              console.log(`Sent offer to user ${user.id}`);
            } else {
              console.log(`Skipping offer for user ${user.id}: PeerConnection already has localDescription`);
            }
          }
        }
      }
    } catch (error) {
      console.error('Error starting group call:', error);
      toast.error('Failed to start group call.');
      endCall();
    }
  };

  const handleSignal = async (signalMessage) => {
    if (isCallEnded || !isMounted.current || !localAudioRef.current) {
      console.log('Ignoring signal due to call ended, component unmounted, or localAudioRef null:', signalMessage);
      return;
    }

    if (signalMessage.from === currentUserId) {
      console.log('Ignoring signal from self:', signalMessage);
      return;
    }

    if (!signalMessage.from) {
      console.warn('Ignoring signal with null from:', signalMessage);
      return;
    }

    const { signal, from, to } = signalMessage;
    console.log('Signal received for processing:', { from, to, type: signal.type, currentUserId });

    if (signal.type === 'groupCallInvite' && !isInitiator) {
      if (hasProcessedInvite.current.has(from)) {
        console.log(`Ignoring duplicate groupCallInvite from ${from}`);
        return;
      }
      hasProcessedInvite.current.add(from);
      console.log('Processing groupCallInvite:', signalMessage);
      if (!signal.roomId) {
        console.error('groupCallInvite received with null roomId');
        toast.error('Invalid call invite received.');
        navigate('/');
        return;
      }
      roomId.current = signal.roomId;
      console.log('Updated roomId to:', roomId.current);
      toast.info(`${signal.initiatorName || 'Unknown User'} invites you to a group call`);
      return;
    }

    if (to && to !== String(currentUserId) && signal.type !== 'acceptCall' && signal.type !== 'ready') {
      console.log('Ignoring signal not addressed to current user:', { from, to, currentUserId });
      return;
    }

    let peerConnection = peerConnections.current.get(from);

    try {
      switch (signal.type) {
        case 'acceptCall':
          if (isInitiator) {
            const userName = callParticipants.find((u) => u?.id === Number(from))?.username || `User ${from}`;
            toast.success(`${userName} accepted the group call`);
          }
          break;

        case 'ready':
          setReadyUsers((prev) => {
            if (prev.has(from)) {
              console.log(`Ignoring duplicate ready signal from user ${from}`);
              return prev;
            }
            const newSet = new Set([...prev, from]);
            console.log('Ready users updated:', Array.from(newSet));
            const userName = callParticipants.find((u) => u?.id === Number(from))?.username || `User ${from}`;
            toast.success(`${userName} is ready`);
            return newSet;
          });
          if (isInitiator && isMounted.current) {
            if (!peerConnection) {
              peerConnection = await initializePeerConnection(from);
              if (!peerConnection) {
                console.error(`Failed to initialize PeerConnection for user ${from}`);
                return;
              }
            }
            if (!peerConnection.localDescription) {
              const offer = await peerConnection.createOffer();
              await peerConnection.setLocalDescription(offer);
              sendSignal(
                {
                  from: currentUserId,
                  to: from,
                  signal: { type: 'offer', sdp: offer.sdp },
                },
                roomId.current
              );
              console.log(`Sent offer to user ${from} after receiving ready`);
            } else {
              console.log(`Skipping offer for user ${from}: PeerConnection already has localDescription`);
            }
            if (callParticipants.length <= 2 || new Set([...readyUsers, from]).size >= callParticipants.length - 1) {
              startCall();
            }
          } else {
            initiateOffers();
          }
          break;

        case 'offer':
          console.log('Processing offer from:', from, 'signal:', signal);
          const offerKey = `${from}:${signal.sdp}`;
          if (processedOffers.current.has(offerKey)) {
            console.warn(`Ignoring duplicate offer from ${from} with same SDP`);
            return;
          }
          processedOffers.current.set(offerKey, true);

          if (!peerConnection) {
            console.log(`No PeerConnection for user ${from}, initializing new one`);
            peerConnection = await initializePeerConnection(from);
            if (!peerConnection) {
              console.error(`Failed to initialize PeerConnection for user ${from}`);
              toast.error('Failed to initialize connection');
              return;
            }
          }

          if (peerConnection.signalingState !== 'stable') {
            console.warn(`Resetting PeerConnection for user ${from} due to signaling state: ${peerConnection.signalingState}`);
            peerConnection.close();
            peerConnections.current.delete(from);
            peerConnection = await initializePeerConnection(from);
            if (!peerConnection) {
              console.error(`Failed to reinitialize PeerConnection for user ${from}`);
              return;
            }
          }

          try {
            await peerConnection.setRemoteDescription(new RTCSessionDescription(signal));
            console.log(`Set remote description for user ${from}`);

            const answer = await peerConnection.createAnswer();
            await peerConnection.setLocalDescription(answer);
            console.log(`Created and set local answer for user ${from}`);
            sendSignal(
              { from: currentUserId, to: from, signal: { type: 'answer', sdp: answer.sdp } },
              roomId.current
            );
            console.log(`Sent answer to user ${from}`);

            if (pendingCandidates.current.has(from)) {
              const candidates = pendingCandidates.current.get(from) || [];
              for (const candidate of candidates) {
                await peerConnection.addIceCandidate(candidate);
                console.log(`Processed pending ICE candidate for ${from}:`, candidate);
              }
              pendingCandidates.current.delete(from);
            }

            if (offerTimeoutRef.current) {
              clearTimeout(offerTimeoutRef.current);
              offerTimeoutRef.current = null;
              console.log('Cleared offerTimeout after receiving offer');
            }
          } catch (error) {
            console.error(`Error processing offer from user ${from}:`, error);
            toast.error(`Failed to process offer from ${callParticipants.find((u) => u?.id === Number(from))?.username || `User ${from}`}`);
            peerConnection?.close();
            peerConnections.current.delete(from);
            processedOffers.current.delete(offerKey);
          }
          break;

        case 'answer':
          if (peerConnection && !peerConnection.remoteDescription) {
            await peerConnection.setRemoteDescription(new RTCSessionDescription(signal));
            console.log(`Processed answer from user ${from}`);
          } else {
            console.warn(`No peerConnection or remoteDescription already set for user ${from}`);
          }
          break;

        case 'candidate':
          if (peerConnection && signal.iceCandidate?.candidate) {
            const candidate = new RTCIceCandidate({
              candidate: signal.iceCandidate.candidate,
              sdpMid: signal.iceCandidate.sdpMid,
              sdpMLineIndex: Number(signal.iceCandidate.sdpMLineIndex),
            });
            if (peerConnection.remoteDescription) {
              await peerConnection.addIceCandidate(candidate);
              console.log(`Processed ICE candidate from user ${from}:`, candidate);
            } else {
              console.warn(`Queuing ICE candidate for ${from}: remoteDescription not set`);
              pendingCandidates.current.set(from, [
                ...(pendingCandidates.current.get(from) || []),
                candidate,
              ]);
            }
          } else {
            console.error(`Cannot add ICE candidate: peerConnection=${!!peerConnection}, candidate=${!!signal.iceCandidate?.candidate}`);
          }
          break;

        case 'rejectCall':
          if (isInitiator) {
            const userName = callParticipants.find((u) => u?.id === Number(from))?.username || `User ${from}`;
            toast.warn(`${userName} rejected the group call`);
            setCallParticipants((prev) => prev.filter((u) => u?.id !== Number(from)));
            peerConnections.current.delete(from);
            if (callParticipants.length <= 2) {
              console.log('No participants left after reject, ending call');
              endCall();
            }
          }
          break;

        case 'endCall':
          if (to && to !== String(currentUserId)) {
            console.log('Ignoring endCall signal not addressed to current user:', { from, to, currentUserId });
            return;
          }
          if (!hasNotifiedEndCall.current) {
            hasNotifiedEndCall.current = true;
            const userName = callParticipants.find((u) => u?.id === Number(from))?.username || `User ${from}`;
            toast.info(`${userName} left the group call`);
            setCallParticipants((prev) => {
              const updatedParticipants = prev.filter((u) => u?.id !== Number(from));
              console.log('Updated participants after endCall:', updatedParticipants);
              return updatedParticipants;
            });
            if (peerConnection) {
              peerConnection.close();
              peerConnections.current.delete(from);
            }
            if (callParticipants.length <= 2) {
              console.log('Ending call: less than 2 participants remain');
              endCall();
            }
          }
          break;

        default:
          console.warn('Unknown signal type:', signal.type);
      }
    } catch (error) {
      console.error('Error processing signal:', error);
    }
  };

  const endCall = () => {
    if (isCallEnded || !isMounted.current) {
      console.log('Call already ended or component unmounted, skipping endCall');
      return;
    }
    setIsCallEnded(true);
    setIsCallStarted(false);
    setIsWaiting(false);

    if (!hasNotifiedEndCall.current) {
      hasNotifiedEndCall.current = true;
      callParticipants
        .filter((u) => u?.id && u.id !== Number(currentUserId))
        .forEach((user) => {
          sendSignal(
            {
              from: currentUserId,
              to: String(user.id),
              signal: { type: 'endCall', roomId: roomId.current },
            },
            roomId.current
          );
          console.log(`Sent endCall to user ${user.id}`);
        });
      toast.info('Group call ended');
    }

    if (unsubscribeRoomRef.current) {
      unsubscribeRoomRef.current();
      console.log(`Unsubscribed from room ${roomId.current} in endCall`);
      unsubscribeRoomRef.current = null;
    }

    peerConnections.current.forEach((pc) => {
      pc.close();
    });
    peerConnections.current.clear();
    pendingStreams.current.clear();
    pendingCandidates.current.clear();

    if (localStream.current) {
      localStream.current.getTracks().forEach((track) => track.stop());
      localStream.current = null;
    }
    if (localAudioRef.current && localAudioRef.current.srcObject) {
      localAudioRef.current.srcObject.getTracks().forEach((track) => track.stop());
      localAudioRef.current.srcObject = null;
    }

    remoteAudioRefs.current.forEach((audio) => {
      if (audio.srcObject) {
        audio.srcObject.getTracks().forEach((track) => track.stop());
        audio.srcObject = null;
      }
      audio.remove();
    });
    remoteAudioRefs.current.clear();

    ringtoneRef.current.pause();
    ringtoneRef.current.currentTime = 0;

    navigate('/');
  };

  // Проверка данных перед рендерингом
  if (!initiator || !callParticipants?.length) {
    console.error('Invalid initiator or participants:', { initiator, callParticipants });
    toast.error('Invalid call data');
    navigate('/');
    return null;
  }

  return (
    <div className="group-call-container">
      <h1 className="group-call-title">Group Call with {initiator.username || 'Unknown User'}</h1>
      <div className="group-call-info">
        <p className="call-timer">Duration: {callDuration}</p>
        <p className="call-start-time">Start: {startTime}</p>
        <p className="call-current-time">Current: {currentTime}</p>
      </div>
      <div className="group-call-participants">
        <h2>Participants</h2>
        <ul className="group-call-participant-list">
          {callParticipants.map((user) => {
            if (!user?.id) {
              console.warn('Invalid user in callParticipants:', user);
              return null;
            }
            return (
              <li key={user.id} className="group-call-participant-item">
                {user.avatar ? (
                  <img
                    src={`https://unlimitedpossibilities12.org${user.avatar}`}
                    alt={`${user.username || `User ${user.id}`} avatar`}
                    className="group-call-participant-avatar"
                    onError={(e) => {
                      console.error(`Failed to load avatar for user ${user.id}`);
                      e.target.style.display = 'none';
                    }}
                  />
                ) : (
                  <div className="group-call-participant-avatar">
                    {(user.username || `User ${user.id}`).charAt(0).toUpperCase()}
                  </div>
                )}
                <span className="group-call-participant-name">{user.username || `User ${user.id}`}</span>
              </li>
            );
          })}
        </ul>
      </div>
      <div className="group-call-chart-container">
        <Line
          data={chartData}
          options={{
            responsive: true,
            maintainAspectRatio: false,
            scales: {
              x: { title: { display: true, text: 'Time' } },
              y: { title: { display: true, text: 'Activity Level' } },
            },
          }}
        />
      </div>
      <audio id="local-audio" ref={localAudioRef} autoPlay muted />
      {callParticipants
        .filter((user) => user?.id && user.id !== Number(currentUserId))
        .map((user) => (
          <audio key={user.id} id={`remote-audio-${user.id}`} autoPlay />
        ))}
      <button className="group-call-end-button" onClick={endCall}>
        End Call
      </button>
    </div>
  );
};

export default GroupCallActivity;