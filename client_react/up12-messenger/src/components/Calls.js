import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from 'react-modal';
import { toast } from 'react-toastify';
import { FaUsers, FaPlus, FaArrowUp, FaArrowDown, FaPhone, FaTimes } from 'react-icons/fa';
import axios from 'axios';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import { findOrCreatePrivateChat } from '../api/privateChats';
import { nameOf } from '../api/names';
import IncomingCallNotification from './IncomingCallNotification';
import AssistantIcon from './AssistantIcon';
import '../styles/Calls.css';


/**
 * Человеческое название разряда звонка.
 *
 * Прежде в списке стояло машинное имя поля: «Voice_with_translate Call».
 * Человеку оно ничего не говорит, да ещё и с подчёркиванием посередине.
 */
const callKind = (type) => {
  switch ((type || '').toLowerCase()) {
    case 'voice': return 'Voice call';
    case 'video': return 'Video call';
    case 'voice_with_translate':
    case 'translated':
    case 'translate': return 'Call with translation';
    case 'group': return 'Group call';
    default: return 'Call';
  }
};


/** Длительность разговора: «4:07». Пропущенных и отклонённых не касается. */
const durationLabel = (call) => {
  const seconds = Number(call && (call.durationSeconds ?? call.duration_seconds));
  if (!Number.isFinite(seconds) || seconds <= 0) return '';
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
};

/**
 * Свернуть подряд идущие однотипные звонки одному человеку в одну строку.
 *
 * Пять попыток дозвониться превращали список в пять одинаковых записей;
 * в телефонах их показывают одной строкой со счётчиком «(5)».
 */
const mergeRepeats = (list) => {
  const key = (call) => {
    const ids = (call.participants || []).map((p) => p.id).sort().join(',');
    return [ids || call.user?.id, call.callType, call.status, call.direction].join('|');
  };
  const out = [];
  list.forEach((call) => {
    const previous = out[out.length - 1];
    if (previous && key(previous) === key(call)) {
      previous.repeats = (previous.repeats || 1) + 1;
      return;
    }
    out.push({ ...call, repeats: 1 });
  });
  return out;
};

/** День звонка: «Today», «Yesterday», дальше — день недели или дата. */
const dayLabel = (timestamp) => {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '';
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (date.toDateString() === today.toDateString()) return 'Today';
  if (date.toDateString() === yesterday.toDateString()) return 'Yesterday';
  const week = (today - date) / 86400000;
  if (week < 7) return date.toLocaleDateString('en-GB', { weekday: 'long' });
  const sameYear = date.getFullYear() === today.getFullYear();
  return date.toLocaleDateString('en-GB', sameYear
    ? { day: 'numeric', month: 'long' }
    : { day: 'numeric', month: 'long', year: 'numeric' });
};

/** Часы и минуты — ими подписана каждая строка справа. */
const timeLabel = (timestamp) => {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
};

const Calls = ({ onClose = () => {} }) => {
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
              setCalls(mergeRepeats(sortedCalls));
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

  // По строке — переписка с человеком, звонок только по кнопке с трубкой:
  // мимо кнопки промахнуться легко, а звонок у собеседника зазвенит сразу.
  const openChatWith = (person) => {
    if (!person || !person.id) return;
    findOrCreatePrivateChat(person, currentUserId)
      .then((route) => { onClose(); navigate(route); })
      .catch(() => toast.error(`Could not open the chat with ${person.username}`));
  };

  useEffect(() => {
    const onKey = (event) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

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

  // Звонки, разложенные по дням: «Today», «Yesterday», дальше день недели —
  // так же, как на телефоне, где над списком стоит «Tuesday».
  const byDay = [];
  calls.forEach((call) => {
    const day = dayLabel(call.timestamp);
    const last = byDay[byDay.length - 1];
    if (last && last.day === day) last.items.push(call);
    else byDay.push({ day, items: [call] });
  });

  return (
    <>
    <div
      className="up-calls-backdrop"
      onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}
      role="presentation"
    >
      <div
        className="up-calls-panel"
        onClick={(event) => event.stopPropagation()}
        role="dialog"
        aria-label="Calls"
      >
        <header className="up-calls-head">
          <h2 className="up-calls-title">Calls</h2>
          <button type="button" className="up-calls-close" title="Close" onClick={onClose}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </header>

        <div className="up-calls-scroll">
          {calls.length === 0 && <p className="up-calls-empty">No calls yet</p>}

          {byDay.map(({ day, items }) => (
            <section key={day} className="up-calls-day">
              <h3 className="up-calls-day-title">{day}</h3>
              {items.map((call) => {
                const isGroup = call.callType === 'group';
                const participants = isGroup ? call.participants.map((id) => getUserById(Number(id))) : [];
                const singleUser = !isGroup
                  ? getUserById(call.direction === 'outgoing' ? Number(call.receiver) : Number(call.caller))
                  : null;
                const people = isGroup ? participants : [singleUser];
                const title = people.map((person) => nameOf(person)).join(', ');
                // Неотвеченный и отклонённый — красным, как на телефоне
                const missed = ['missed', 'rejected', 'declined'].includes((call.status || '').toLowerCase());
                const duration = durationLabel(call);

                return (
                  <div key={call.id} className="up-call-row">
                    <button
                      type="button"
                      className="up-call"
                      onClick={() => !isGroup && openChatWith(singleUser)}
                      title={isGroup ? title : `Open the chat with ${title}`}
                    >
                      <span className="up-call-avatars">
                        {people.slice(0, 3).map((person, index) => (
                          person.avatar ? (
                            <img
                              key={`${person.id}-${index}`}
                              className="up-call-avatar"
                              src={`https://unlimitedpossibilities12.org${person.avatar}`}
                              alt=""
                            />
                          ) : (
                            <span key={`${person.id}-${index}`} className="up-call-avatar up-call-avatar-letter">
                              {(nameOf(person) || '?').charAt(0).toUpperCase()}
                            </span>
                          )
                        ))}
                      </span>

                      <span className="up-call-body">
                        <span className="up-call-top">
                          <span className="up-call-name">
                            {title}
                            {call.repeats > 1 && <span className="up-call-repeats"> ({call.repeats})</span>}
                          </span>
                          <span className="up-call-time">{timeLabel(call.timestamp)}</span>
                        </span>
                        <span className={`up-call-about ${missed ? 'missed' : ''}`}>
                          {call.direction === 'outgoing'
                            ? <FaArrowUp className="up-call-arrow outgoing" />
                            : <FaArrowDown className="up-call-arrow incoming" />}
                          {call.status.charAt(0).toUpperCase() + call.status.slice(1)}
                          {' · '}{callKind(call.callType)}
                          {duration && <>{' · '}{duration}</>}
                        </span>
                      </span>
                    </button>

                    <button
                      type="button"
                      className="up-call-again"
                      title="Call again"
                      onClick={() => handleRedial(call)}
                    >
                      <FaPhone />
                    </button>
                    <button
                      type="button"
                      className="up-call-drop"
                      title="Remove from the list"
                      onClick={() => handleConfirmDelete(call.id)}
                    >
                      <FaTimes />
                    </button>
                  </div>
                );
              })}
            </section>
          ))}
        </div>

        <footer className="up-calls-foot">
          <button type="button" className="up-calls-group" onClick={handleGroupCallButtonClick}>
            <FaUsers />
            <FaPlus className="up-calls-group-plus" />
            <span>Group call</span>
          </button>
          <button type="button" className="up-calls-done" onClick={onClose}>Close</button>
        </footer>
      </div>
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
    <AssistantIcon size="large" className="assistant-icon-bottom-left" />
    </>
  );
};

export default Calls;