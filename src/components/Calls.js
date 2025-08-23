import React from 'react';
import { FaUsers, FaPlus } from 'react-icons/fa';
import '../styles/Calls.css';

const Calls = () => {
  const handleGroupCallButtonClick = () => {
    // Заглушка для обработки нажатия
    console.log('Group call button clicked');
  };

  return (
    <div className="calls-container">
      <h1>Calls</h1>
      <button className="group-call-button" onClick={handleGroupCallButtonClick}>
        <FaUsers className="group-call-icon" />
        <FaPlus className="plus-icon" />
      </button>
    </div>
  );
};

export default Calls;