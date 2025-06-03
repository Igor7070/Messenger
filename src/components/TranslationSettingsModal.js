import React, { useState, useEffect } from 'react';
import '../styles/TranslationSettingsModal.css';

const TranslationSettingsModal = ({ chatId, onSave, onClose }) => {
  const [enableTranslation, setEnableTranslation] = useState(false);
  const [translateNewOnly, setTranslateNewOnly] = useState(false);
  const [selectedLanguage, setSelectedLanguage] = useState('auto');

  const languages = [
    { name: 'Auto', code: 'auto', flag: '🌐' },
    { name: 'Afrikaans', code: 'af', flag: '🇿🇦' },
    { name: 'Albanian', code: 'sq', flag: '🇦🇱' },
    // ... остальные языки, как в TranslationSettingsFragment.java (список можно скопировать)
    { name: 'Zulu', code: 'zu', flag: '🇿🇦' },
  ].sort((a, b) => (a.name === 'Auto' ? -1 : b.name === 'Auto' ? 1 : a.name.localeCompare(b.name)));

  useEffect(() => {
    const translationEnabled = localStorage.getItem(`translation_enabled_${chatId}`) === 'true';
    const translationLanguage = localStorage.getItem(`translation_language_${chatId}`) || 'auto';
    const translateNewOnly = localStorage.getItem(`translate_new_only_${chatId}`) === 'true';
    setEnableTranslation(translationEnabled);
    setSelectedLanguage(translationLanguage);
    setTranslateNewOnly(translateNewOnly);
  }, [chatId]);

  const handleSave = () => {
    localStorage.setItem(`translation_enabled_${chatId}`, enableTranslation);
    localStorage.setItem(`translation_language_${chatId}`, selectedLanguage);
    localStorage.setItem(`translate_new_only_${chatId}`, translateNewOnly);
    if (enableTranslation) {
      localStorage.setItem(`translation_enabled_timestamp_${chatId}`, new Date().toISOString());
    } else {
      localStorage.removeItem(`translation_enabled_timestamp_${chatId}`);
    }
    onSave(enableTranslation, selectedLanguage, translateNewOnly);
    onClose();
  };

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>Translation Settings</h2>
        <label>
          <input
            type="checkbox"
            checked={enableTranslation}
            onChange={(e) => setEnableTranslation(e.target.checked)}
          />
          Enable Translation
        </label>
        <label>
          <input
            type="checkbox"
            checked={translateNewOnly}
            onChange={(e) => setTranslateNewOnly(e.target.checked)}
          />
          Translate only new messages
        </label>
        <label>
          Language:
          <select
            value={selectedLanguage}
            onChange={(e) => setSelectedLanguage(e.target.value)}
          >
            {languages.map((lang) => (
              <option key={lang.code} value={lang.code}>
                {lang.flag} {lang.name}
              </option>
            ))}
          </select>
        </label>
        <div className="modal-buttons">
          <button onClick={onClose}>Cancel</button>
          <button onClick={handleSave}>Save</button>
        </div>
      </div>
    </div>
  );
};

export default TranslationSettingsModal;