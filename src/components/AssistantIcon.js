import React from 'react';
import { useAssistant } from '../context/AssistantContext';
import '../styles/AssistantIcon.css';

const AssistantIcon = ({ size = 'large', position = 'bottom-right' }) => {
  const { openAssistant } = useAssistant();

  const iconSize = size === 'large' ? '56px' : (size === 'header' ? '36px' : '36px');
  const svgSize = size === 'large' ? 32 : (size === 'header' ? 36 : 36);

  // Уникальный id для градиента — на основе size и случайного суффикса
  const gradId = `assistant-grad-${size}-${Math.random().toString(36).substr(2, 9)}`;

  return (
    <div 
      className={`assistant-icon assistant-icon-${size} assistant-icon-${position}`} 
      onClick={openAssistant}
      style={{ width: iconSize, height: iconSize }}
    >
      <svg width={svgSize} height={svgSize} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="12" cy="12" r="11" fill="rgba(255, 255, 255, 0.2)" stroke={`url(#${gradId})`} strokeWidth="2"/>
        <path d="M13 2L4.09344 12.6879C3.74461 13.1064 3.5702 13.3157 3.56738 13.4925C3.5649 13.6461 3.63372 13.7923 3.75324 13.8889C3.89073 14 4.16316 14 4.70803 14H12L11 22L19.9066 11.3121C20.2554 10.8936 20.4298 10.6843 20.4326 10.5075C20.4351 10.3539 20.3663 10.2077 20.2468 10.1111C20.1093 10 19.8368 10 19.292 10H12L13 2Z" 
              fill={`url(#${gradId})`} 
              stroke={`url(#${gradId})`} 
              strokeWidth="1.5" 
              strokeLinecap="round" 
              strokeLinejoin="round"/>
        <defs>
          <linearGradient id={gradId} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#FFFFFF"/>
            <stop offset="30%" stopColor="#A5F3FC"/>
            <stop offset="60%" stopColor="#60A5FA"/>
            <stop offset="100%" stopColor="#2563EB"/>
          </linearGradient>
        </defs>
      </svg>

      <span className="assistant-pulse"></span>
    </div>
  );
};

export default AssistantIcon;