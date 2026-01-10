import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from 'react-modal';
import { toast } from 'react-toastify';
import { FaUsers, FaPlus, FaArrowUp, FaArrowDown, FaPhone, FaTimes } from 'react-icons/fa';
import axios from 'axios';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import IncomingCallNotification from './IncomingCallNotification';
import AssistantIcon from './AssistantIcon';
import '../styles/Calls.css';

const Calls = () => {
  const [modalIsOpen, setModalIsOpen] = useState(false);
  const [users, setUsers] = useState([]);
  const [calls, setCalls] = useState([]);
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedUsers, setSelectedUsers] = useState([]);
  const [currentUserId, setCurrentUserId] = useState(null);
  const [currentUsername, setCurrentUsername] = useState('');
  const [callTypeModalIsOpen, setCallTypeModalIsOpen] = useState(false);
  const [selectedCallType, setSelectedCallType] = useState(null);
  const [redialCall, setRedialCall] = useState(null);
  const navigate = useNavigate();

  const { incomingCall, acceptCall, rejectCall } = useCallWebSocket();

  useEffect(() => {
    const fetchUsers = async () => {
      try {
          const storedUserId = Number(localStorage.getItem('userId'));
          const [usersResponse, currentUserResponse] = await Promise.all([
              fetch('https://unlimitedpossibilities12.org/api/users'),
              fetch(`https://unlimitedpossibilities12.org/api/users/${storedUserId}`)
          ]);

          if (usersResponse.ok && currentUserResponse.ok) {
              const usersData = await usersResponse.json();
              const currentUserData = await currentUserResponse.json();
              setCurrentUserId(storedUserId);
              setCurrentUsername(currentUserData.username || localStorage.getItem('username') || 'Unknown');
              localStorage.setItem('avatar', currentUserData.avatar || '');
              setUsers(usersData.filter((user) => user.id !== storedUserId));
          } else {
              toast.error('Failed to fetch users');
          }
      } catch (error) {
          console.error('Error fetching users:', error);
          toast.error('Error fetching users');
      }
    };

    const fetchCalls = async () => {
      try {
          const storedUserId = Number(localStorage.getItem('userId'));
          const response = await fetch(`https://unlimitedpossibilities12.org/api/calls?userId=${storedUserId}`);
          if (response.ok) {
              const data = await response.json();
              const sortedCalls = data.sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));
              setCalls(sortedCalls);
          } else {
              toast.error('Failed to fetch calls');
          }
      } catch (error) {
          console.error('Error fetching calls:', error);
          toast.error('Error fetching calls');
      }
    };

    fetchUsers();
    fetchCalls();
  }, []);

  useEffect(() => {
    const updateScrollBar = () => {
      const callsList = document.querySelector('.calls-list');
      if (callsList) {
        callsList.style.overflowY = callsList.scrollHeight <= callsList.offsetHeight ? 'hidden' : 'auto';
      }
    };
    updateScrollBar();
    window.addEventListener('resize', updateScrollBar);
    return () => window.removeEventListener('resize', updateScrollBar);
  }, [calls]);

  const handleGroupCallButtonClick = () => {
    setModalIsOpen(true);
  };

  const handleUserSelection = (userId) => {
    if (selectedUsers.includes(userId)) {
      setSelectedUsers(selectedUsers.filter((id) => id !== userId));
    } else if (selectedUsers.length < 4) {
      setSelectedUsers([...selectedUsers, userId]);
    } else {
      toast.warn('You can select up to 4 users for a group call');
    }
  };

  const handleStartGroupCall = () => {
    if (selectedUsers.length === 0) {
      toast.warn('Please select at least one user');
      return;
    }

    const selectedUserDetails = users
      .filter((user) => selectedUsers.includes(user.id))
      .map((user) => ({ id: user.id, username: user.username }));

    navigate('/group-call', {
      state: {
        participants: selectedUserDetails,
        initiator: { id: currentUserId, username: currentUsername },
      },
    });
    setModalIsOpen(false);
  };

  const handleRedial = async (call) => {
    try {
        if (call.callType === 'group') {
            const participants = call.participants
                .map((id) => getUserById(Number(id)))
                .filter((user) => user.id !== currentUserId)
                .map((user) => ({ id: user.id, username: user.username }));
            
            if (participants.length === 0) {
                toast.warn('No valid participants for group call');
                return;
            }

            navigate('/group-call', {
                state: {
                    participants,
                    initiator: { id: currentUserId, username: currentUsername },
                },
            });
            toast.success(`Starting group call...`);
        } else if (['voice', 'video', 'translate', 'voice_with_translate', 'translated'].includes(call.callType)) {
            const recipientId = call.direction === 'outgoing' ? Number(call.receiver) : Number(call.caller);
            const recipient = getUserById(recipientId);
            const roomId = `room-${currentUserId}-${recipientId}`;
            const callRequest = {
                callerId: currentUserId,
                recipientId,
                roomId,
                callerName: currentUsername || 'Unknown',
                type: call.callType === 'voice_with_translate' || call.callType === 'translated' ? 'translate' : call.callType,
            };

            const response = await axios.post('https://unlimitedpossibilities12.org/api/webrtc/call', callRequest);
            if (response.status === 200) {
                const route = call.callType === 'voice' ? `/voice-call/${roomId}` : 
                             call.callType === 'video' ? `/video-call/${roomId}` : 
                             `/voice-call-translate/${roomId}`;
                toast.success(`Calling ${recipient.username}...`);
                navigate(route, {
                    state: {
                        incomingCall: callRequest,
                        isCaller: true,
                        currentUserId,
                        callerName: currentUsername,
                        recipientName: recipient.username,
                    },
                });
            } else {
                toast.error(`Failed to initiate ${call.callType} call`);
            }
        } else {
            // Открываем модальное окно для выбора типа звонка
            setRedialCall(call);
            setCallTypeModalIsOpen(true);
        }
    } catch (error) {
        console.error(`Error initiating ${call.callType} call:`, error);
        toast.error(`Error initiating ${call.callType} call`);
    }
  };

  // NEW: Обработчик выбора типа звонка
  const handleCallTypeSelection = (type) => {
      setSelectedCallType(type);
  };

  // NEW: Обработчик подтверждения выбора типа звонка
  const handleConfirmCallType = async () => {
      if (!selectedCallType || !redialCall) return;

      try {
          const recipientId = redialCall.direction === 'outgoing' ? Number(redialCall.receiver) : Number(redialCall.caller);
          const recipient = getUserById(recipientId);
          const roomId = `room-${currentUserId}-${recipientId}`;
          const callRequest = {
              callerId: currentUserId,
              recipientId,
              roomId,
              callerName: currentUsername || 'Unknown',
              type: selectedCallType,
          };

          const response = await axios.post('https://unlimitedpossibilities12.org/api/webrtc/call', callRequest);
          if (response.status === 200) {
              const route = selectedCallType === 'voice' ? `/voice-call/${roomId}` : 
                          selectedCallType === 'video' ? `/video-call/${roomId}` : 
                          `/voice-call-translate/${roomId}`;
              toast.success(`Calling ${recipient.username}...`);
              navigate(route, {
                  state: {
                      incomingCall: callRequest,
                      isCaller: true,
                      currentUserId,
                      callerName: currentUsername,
                      recipientName: recipient.username,
                  },
              });
          } else {
              toast.error(`Failed to initiate ${selectedCallType} call`);
          }
      } catch (error) {
          console.error(`Error initiating ${selectedCallType} call:`, error);
          toast.error(`Error initiating ${selectedCallType} call`);
      }

      setCallTypeModalIsOpen(false);
      setSelectedCallType(null);
      setRedialCall(null);
  };

  // NEW: Обработчик отмены выбора типа звонка
  const handleCancelCallType = () => {
      setCallTypeModalIsOpen(false);
      setSelectedCallType(null);
      setRedialCall(null);
  };

  // NEW: Функция для подтверждения удаления
  const handleConfirmDelete = async (callId) => {
    try {
      const response = await fetch(
        `https://unlimitedpossibilities12.org/api/calls/${callId}?userId=${currentUserId}`,
        {
          method: 'DELETE',
          headers: {
            'Content-Type': 'application/json',
          },
        }
      );
      if (response.ok) {
        setCalls(calls.filter((call) => call.id !== callId));
        toast.success('Call deleted successfully');
      } else {
        toast.error('Failed to delete call');
      }
    } catch (error) {
      console.error('Error deleting call:', error);
      toast.error('Error deleting call');
    }
  };

  const getUserById = (id) => {
      if (id === currentUserId) {
          return { id: currentUserId, username: currentUsername, avatar: localStorage.getItem('avatar') || null };
      }
      return users.find((user) => user.id === id) || { username: 'Unknown', avatar: null };
  };

  const formatTimestamp = (timestamp) => {
    try {
      const date = new Date(timestamp);
      return date.toLocaleString('en-GB', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch (error) {
      console.error('Error formatting timestamp:', error);
      return 'Unknown';
    }
  };

  const filteredUsers = users.filter((user) =>
    user.username.toLowerCase().includes(searchTerm.toLowerCase())
  );

  return (
    <>
    <div className="calls-container">
      <h1>Calls</h1>

      <div className="calls-list">
        {calls.map((call) => {
          const isGroup = call.callType === 'group';
          const participants = isGroup ? call.participants.map((id) => getUserById(Number(id))) : [];
          const singleUser = !isGroup
            ? getUserById(call.direction === 'outgoing' ? Number(call.receiver) : Number(call.caller))
            : null;

          return (
            <div key={call.id} className="call-item">
              {/* Кнопка удаления */}
              <div className="call-delete" onClick={() => handleConfirmDelete(call.id)}>
                <FaTimes className="call-delete-icon" />
              </div>

              {/* Аватары — как было */}
              <div className="call-participants">
                {isGroup ? (
                  participants.map((user) => (
                    <div key={user.id} className="call-participant">
                      {user.avatar ? (
                        <img src={`https://unlimitedpossibilities12.org${user.avatar}`} alt={`${user.username} avatar`} className="call-avatar" />
                      ) : (
                        <div className="call-avatar">{user.username.charAt(0).toUpperCase()}</div>
                      )}
                      <span className="call-name">{user.username}</span>
                    </div>
                  ))
                ) : (
                  <div className="call-participant">
                    {singleUser.avatar ? (
                      <img src={`https://unlimitedpossibilities12.org${singleUser.avatar}`} alt={`${singleUser.username} avatar`} className="call-avatar" />
                    ) : (
                      <div className="call-avatar">{singleUser.username.charAt(0).toUpperCase()}</div>
                    )}
                    <span className="call-name">{singleUser.username}</span>
                  </div>
                )}
              </div>

              {/* === НА ПК: старое расположение === */}
              <div className="call-direction">
                {call.direction === 'outgoing' ? (
                  <FaArrowUp className="call-arrow outgoing" />
                ) : (
                  <FaArrowDown className="call-arrow incoming" />
                )}
              </div>

              <div className="call-details">
                <span className="call-status">{call.status.charAt(0).toUpperCase() + call.status.slice(1)}</span>
                <span className="call-type">{call.callType.charAt(0).toUpperCase() + call.callType.slice(1)} Call</span>
                <span className="call-timestamp">{formatTimestamp(call.timestamp)}</span>
              </div>

              <div className="call-redial" onClick={() => handleRedial(call)}>
                <FaPhone className="call-phone-icon" />
              </div>

              {/* === НА ТЕЛЕФОНЕ: нижний ряд === */}
              <div className="call-bottom-row">
                <div className="call-direction">
                  {call.direction === 'outgoing' ? (
                    <FaArrowUp className="call-arrow outgoing" />
                  ) : (
                    <FaArrowDown className="call-arrow incoming" />
                  )}
                </div>

                <div className="call-details">
                  <span className="call-status">{call.status.charAt(0).toUpperCase() + call.status.slice(1)}</span>
                  <span className="call-type">{call.callType.charAt(0).toUpperCase() + call.callType.slice(1)} Call</span>
                  <span className="call-timestamp">{formatTimestamp(call.timestamp)}</span>
                </div>

                <div className="call-redial" onClick={() => handleRedial(call)}>
                  <FaPhone className="call-phone-icon" />
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <button className="calls-group-call-button" onClick={handleGroupCallButtonClick}>
        <FaUsers className="calls-group-call-icon" />
        <FaPlus className="calls-plus-icon" />
      </button>

      <Modal
        isOpen={modalIsOpen}
        onRequestClose={() => setModalIsOpen(false)}
        className="calls-group-call-modal"
        overlayClassName="calls-group-call-modal-overlay"
      >
        <h2 className="calls-modal-title">Select Users for Group Call</h2>
        <input
          type="text"
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          placeholder="Search users..."
          className="calls-search-input"
        />
        <ul className="calls-user-list">
          {filteredUsers.map((user) => (
            <li key={user.id} className="calls-user-item">
              <input
                type="checkbox"
                checked={selectedUsers.includes(user.id)}
                onChange={() => handleUserSelection(user.id)}
                disabled={!selectedUsers.includes(user.id) && selectedUsers.length >= 4}
                className="calls-user-checkbox"
              />
              {user.avatar ? (
                <img
                  src={`https://unlimitedpossibilities12.org${user.avatar}`}
                  alt={`${user.username} avatar`}
                  className="calls-user-avatar"
                />
              ) : (
                <div className="calls-user-avatar">{user.username.charAt(0).toUpperCase()}</div>
              )}
              <span className="calls-user-name">{user.username}</span>
            </li>
          ))}
        </ul>
        <button
          onClick={handleStartGroupCall}
          className="calls-start-group-call-button"
          disabled={selectedUsers.length === 0}
        >
          Start A Group Call
        </button>
        <button onClick={() => setModalIsOpen(false)} className="calls-modal-close-button">
          Close
        </button>
      </Modal>
      <Modal
          isOpen={callTypeModalIsOpen}
          onRequestClose={handleCancelCallType}
          className="calls-delete-modal" // Используем тот же стиль, что для удаления
          overlayClassName="calls-delete-modal-overlay"
      >
          <h2 className="calls-modal-title">Select call type</h2>
          <div className="calls-call-type-options">
              {['voice', 'video', 'translate'].map((type) => (
                  <label key={type} className="calls-call-type-label">
                      <input
                          type="checkbox"
                          checked={selectedCallType === type}
                          onChange={() => handleCallTypeSelection(type)}
                          className="calls-call-type-checkbox"
                      />
                      {type.charAt(0).toUpperCase() + type.slice(1)} Call
                  </label>
              ))}
          </div>
          <div className="calls-delete-modal-buttons">
              <button onClick={handleCancelCallType} className="calls-modal-cancel-button">
                  Cancel
              </button>
              <button
                  onClick={handleConfirmCallType}
                  className="calls-modal-confirm-button"
                  disabled={!selectedCallType}
              >
                  Call
              </button>
          </div>
      </Modal>
      {incomingCall && (
          <IncomingCallNotification
              callerId={incomingCall.callerId}
              callerName={incomingCall.callerName || 'Unknown'}
              avatar={incomingCall.avatar}
              callType={incomingCall.type}
              onAccept={() => acceptCall(navigate)}
              onReject={rejectCall}
          />
      )}
    </div>
    {/* Иконка вне контейнера — fixed относительно всего экрана */}
    <AssistantIcon size="large" className="fixed-app-bottom-left" />
    </>
  );
};

export default Calls;