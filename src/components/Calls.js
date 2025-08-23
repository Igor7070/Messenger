import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from 'react-modal';
import { toast } from 'react-toastify';
import { FaUsers, FaPlus } from 'react-icons/fa';
import '../styles/Calls.css';

const Calls = () => {
  const [modalIsOpen, setModalIsOpen] = useState(false);
  const [users, setUsers] = useState([]);
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedUsers, setSelectedUsers] = useState([]);
  const [currentUserId, setCurrentUserId] = useState(null);
  const [currentUsername, setCurrentUsername] = useState('');
  const navigate = useNavigate();

  useEffect(() => {
    const fetchUsers = async () => {
      try {
        const response = await fetch('https://unlimitedpossibilities12.org/api/users');
        if (response.ok) {
          const data = await response.json();
          const storedUserId = Number(localStorage.getItem('userId'));
          setCurrentUserId(storedUserId);
          setCurrentUsername(localStorage.getItem('username') || 'Unknown');
          setUsers(data.filter((user) => user.id !== storedUserId));
        } else {
          toast.error('Failed to fetch users');
        }
      } catch (error) {
        console.error('Error fetching users:', error);
        toast.error('Error fetching users');
      }
    };

    fetchUsers();
  }, []);

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

  const filteredUsers = users.filter((user) =>
    user.username.toLowerCase().includes(searchTerm.toLowerCase())
  );

  return (
    <div className="calls-container">
      <h1>Calls</h1>
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
    </div>
  );
};

export default Calls;