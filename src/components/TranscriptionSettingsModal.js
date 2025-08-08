import React, { useState, useEffect, useRef } from 'react';
import '../styles/TranscriptionSettingsModal.css';

const TranscriptionSettingsModal = ({ chatId, onSave, onClose }) => {
  const [enableTranscription, setEnableTranscription] = useState(false);
  const [transcriptionMode, setTranscriptionMode] = useState('selective');
  const modalRef = useRef(null);

  useEffect(() => {
    const transcriptionEnabled = localStorage.getItem(`transcription_enabled_${chatId}`) === 'true';
    const transcriptionMode = localStorage.getItem(`transcription_mode_${chatId}`) || 'selective';
    setEnableTranscription(transcriptionEnabled);
    setTranscriptionMode(transcriptionMode);
  }, [chatId]);

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (modalRef.current && !modalRef.current.contains(event.target)) {
        onClose();
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [onClose]);

  const handleSave = () => {
    localStorage.setItem(`transcription_enabled_${chatId}`, enableTranscription);
    localStorage.setItem(`transcription_mode_${chatId}`, transcriptionMode);
    if (enableTranscription) {
      localStorage.setItem(`transcription_enabled_timestamp_${chatId}`, new Date().toISOString());
    } else {
      localStorage.removeItem(`transcription_enabled_timestamp_${chatId}`);
    }
    onSave({
      isTranscriptionEnabled: enableTranscription,
      transcriptionMode,
    });
    onClose();
  };

  return (
    <div className="transcription-modal-overlay">
      <div className="transcription-modal-content" ref={modalRef}>
        <h2>Transcription Settings</h2>
        <label>
          <input
            type="checkbox"
            checked={enableTranscription}
            onChange={(e) => setEnableTranscription(e.target.checked)}
          />
          Enable Transcription
        </label>
        <div className="transcription-mode-selection">
          <label>
            <input
              type="radio"
              name="transcriptionMode"
              value="all"
              checked={transcriptionMode === 'all'}
              onChange={() => setTranscriptionMode('all')}
              disabled={!enableTranscription}
            />
            Transcribe all audio messages
          </label>
          <label>
            <input
              type="radio"
              name="transcriptionMode"
              value="new"
              checked={transcriptionMode === 'new'}
              onChange={() => setTranscriptionMode('new')}
              disabled={!enableTranscription}
            />
            Transcribe only new audio messages
          </label>
          <label>
            <input
              type="radio"
              name="transcriptionMode"
              value="selective"
              checked={transcriptionMode === 'selective'}
              onChange={() => setTranscriptionMode('selective')}
              disabled={!enableTranscription}
            />
            Transcribe selectively
          </label>
        </div>
        <div className="transcription-modal-buttons">
          <button onClick={onClose}>Cancel</button>
          <button onClick={handleSave}>Save</button>
        </div>
      </div>
    </div>
  );
};

export default TranscriptionSettingsModal;