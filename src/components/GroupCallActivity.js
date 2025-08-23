import React from 'react';
import { useLocation } from 'react-router-dom';
import '../styles/GroupCallActivity.css';

const GroupCallActivity = () => {
  const { state } = useLocation();
  const { participants = [], initiator = {} } = state || {};

  return (
    <div className="group-call-container">
      <h1>Group Call</h1>
      <h2>Initiator: {initiator.username} (ID: {initiator.id})</h2>
      <h3>Participants:</h3>
      <ul>
        {participants.map((user) => (
          <li key={user.id}>
            {user.username} (ID: {user.id})
          </li>
        ))}
      </ul>
      <p>This is a placeholder for the group call functionality.</p>
    </div>
  );
};

export default GroupCallActivity;