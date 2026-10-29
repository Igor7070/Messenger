import React, { useState, useEffect, useRef } from 'react';
import '../styles/TranslationSettingsModal.css';

const TranslationSettingsModal = ({ chatId, onSave, onClose }) => {
  const [enableTranslation, setEnableTranslation] = useState(false);
  const [translationMode, setTranslationMode] = useState('selective');
  const [selectedLanguage, setSelectedLanguage] = useState('auto');
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const dropdownRef = useRef(null);

  const languages = [
    { name: 'Auto', code: 'auto', flag: 'AUTO', emojiFlag: '🌐' },
    { name: 'Afrikaans', code: 'af', flag: 'AF', emojiFlag: '🇿🇦' },
    { name: 'Albanian', code: 'sq', flag: 'SQ', emojiFlag: '🇦🇱' },
    { name: 'Amharic', code: 'am', flag: 'AM', emojiFlag: '🇪🇹' },
    { name: 'Arabic', code: 'ar', flag: 'AR', emojiFlag: '🇸🇦' },
    { name: 'Armenian', code: 'hy', flag: 'HY', emojiFlag: '🇦🇲' },
    { name: 'Azerbaijani', code: 'az', flag: 'AZ', emojiFlag: '🇦🇿' },
    { name: 'Basque', code: 'eu', flag: 'EU', emojiFlag: '🇪🇸' },
    { name: 'Belarusian', code: 'be', flag: 'BE', emojiFlag: '🇧🇾' },
    { name: 'Bengali', code: 'bn', flag: 'BN', emojiFlag: '🇧🇩' },
    { name: 'Bosnian', code: 'bs', flag: 'BS', emojiFlag: '🇧🇦' },
    { name: 'Bulgarian', code: 'bg', flag: 'BG', emojiFlag: '🇧🇬' },
    { name: 'Catalan', code: 'ca', flag: 'CA', emojiFlag: '🇪🇸' },
    { name: 'Cebuano', code: 'ceb', flag: 'CEB', emojiFlag: '🇵🇭' },
    { name: 'Chichewa', code: 'ny', flag: 'NY', emojiFlag: '🇲🇼' },
    { name: 'Chinese (Simplified)', code: 'zh', flag: 'ZH', emojiFlag: '🇨🇳' },
    { name: 'Chinese (Traditional)', code: 'zh-TW', flag: 'ZH-TW', emojiFlag: '🇹🇼' },
    { name: 'Corsican', code: 'co', flag: 'CO', emojiFlag: '🇫🇷' },
    { name: 'Croatian', code: 'hr', flag: 'HR', emojiFlag: '🇭🇷' },
    { name: 'Czech', code: 'cs', flag: 'CS', emojiFlag: '🇨🇿' },
    { name: 'Danish', code: 'da', flag: 'DA', emojiFlag: '🇩🇰' },
    { name: 'Dutch', code: 'nl', flag: 'NL', emojiFlag: '🇳🇱' },
    { name: 'English', code: 'en', flag: 'EN', emojiFlag: '🇬🇧' },
    { name: 'Esperanto', code: 'eo', flag: 'EO', emojiFlag: '🏳️' },
    { name: 'Estonian', code: 'et', flag: 'ET', emojiFlag: '🇪🇪' },
    { name: 'Filipino', code: 'tl', flag: 'TL', emojiFlag: '🇵🇭' },
    { name: 'Finnish', code: 'fi', flag: 'FI', emojiFlag: '🇫🇮' },
    { name: 'French', code: 'fr', flag: 'FR', emojiFlag: '🇫🇷' },
    { name: 'Frisian', code: 'fy', flag: 'FY', emojiFlag: '🇳🇱' },
    { name: 'Galician', code: 'gl', flag: 'GL', emojiFlag: '🇪🇸' },
    { name: 'Georgian', code: 'ka', flag: 'KA', emojiFlag: '🇬🇪' },
    { name: 'German', code: 'de', flag: 'DE', emojiFlag: '🇩🇪' },
    { name: 'Greek', code: 'el', flag: 'EL', emojiFlag: '🇬🇷' },
    { name: 'Gujarati', code: 'gu', flag: 'GU', emojiFlag: '🇮🇳' },
    { name: 'Haitian Creole', code: 'ht', flag: 'HT', emojiFlag: '🇭🇹' },
    { name: 'Hausa', code: 'ha', flag: 'HA', emojiFlag: '🇳🇬' },
    { name: 'Hawaiian', code: 'haw', flag: 'HAW', emojiFlag: '🇺🇸' },
    { name: 'Hebrew', code: 'he', flag: 'HE', emojiFlag: '🇮🇱' },
    { name: 'Hindi', code: 'hi', flag: 'HI', emojiFlag: '🇮🇳' },
    { name: 'Hmong', code: 'hmn', flag: 'HMN', emojiFlag: '🇨🇳' },
    { name: 'Hungarian', code: 'hu', flag: 'HU', emojiFlag: '🇭🇺' },
    { name: 'Icelandic', code: 'is', flag: 'IS', emojiFlag: '🇮🇸' },
    { name: 'Igbo', code: 'ig', flag: 'IG', emojiFlag: '🇳🇬' },
    { name: 'Indonesian', code: 'id', flag: 'ID', emojiFlag: '🇮🇩' },
    { name: 'Irish', code: 'ga', flag: 'GA', emojiFlag: '🇮🇪' },
    { name: 'Italian', code: 'it', flag: 'IT', emojiFlag: '🇮🇹' },
    { name: 'Japanese', code: 'ja', flag: 'JA', emojiFlag: '🇯🇵' },
    { name: 'Javanese', code: 'jv', flag: 'JV', emojiFlag: '🇮🇩' },
    { name: 'Kannada', code: 'kn', flag: 'KN', emojiFlag: '🇮🇳' },
    { name: 'Kazakh', code: 'kk', flag: 'KK', emojiFlag: '🇰🇿' },
    { name: 'Khmer', code: 'km', flag: 'KM', emojiFlag: '🇰🇭' },
    { name: 'Kinyarwanda', code: 'rw', flag: 'RW', emojiFlag: '🇷🇼' },
    { name: 'Korean', code: 'ko', flag: 'KO', emojiFlag: '🇰🇷' },
    { name: 'Kurdish', code: 'ku', flag: 'KU', emojiFlag: '🇮🇶' },
    { name: 'Kyrgyz', code: 'ky', flag: 'KY', emojiFlag: '🇰🇬' },
    { name: 'Lao', code: 'lo', flag: 'LO', emojiFlag: '🇱🇦' },
    { name: 'Latin', code: 'la', flag: 'LA', emojiFlag: '🏳️' },
    { name: 'Latvian', code: 'lv', flag: 'LV', emojiFlag: '🇱🇻' },
    { name: 'Lithuanian', code: 'lt', flag: 'LT', emojiFlag: '🇱🇹' },
    { name: 'Luxembourgish', code: 'lb', flag: 'LB', emojiFlag: '🇱🇺' },
    { name: 'Macedonian', code: 'mk', flag: 'MK', emojiFlag: '🇲🇰' },
    { name: 'Malagasy', code: 'mg', flag: 'MG', emojiFlag: '🇲🇬' },
    { name: 'Malay', code: 'ms', flag: 'MS', emojiFlag: '🇲🇾' },
    { name: 'Malayalam', code: 'ml', flag: 'ML', emojiFlag: '🇮🇳' },
    { name: 'Maltese', code: 'mt', flag: 'MT', emojiFlag: '🇲🇹' },
    { name: 'Maori', code: 'mi', flag: 'MI', emojiFlag: '🇳🇿' },
    { name: 'Marathi', code: 'mr', flag: 'MR', emojiFlag: '🇮🇳' },
    { name: 'Mongolian', code: 'mn', flag: 'MN', emojiFlag: '🇲🇳' },
    { name: 'Myanmar (Burmese)', code: 'my', flag: 'MY', emojiFlag: '🇲🇲' },
    { name: 'Nepali', code: 'ne', flag: 'NE', emojiFlag: '🇳🇵' },
    { name: 'Norwegian', code: 'no', flag: 'NO', emojiFlag: '🇳🇴' },
    { name: 'Odia', code: 'or', flag: 'OR', emojiFlag: '🇮🇳' },
    { name: 'Pashto', code: 'ps', flag: 'PS', emojiFlag: '🇦🇫' },
    { name: 'Persian', code: 'fa', flag: 'FA', emojiFlag: '🇮🇷' },
    { name: 'Polish', code: 'pl', flag: 'PL', emojiFlag: '🇵🇱' },
    { name: 'Portuguese', code: 'pt', flag: 'PT', emojiFlag: '🇵🇹' },
    { name: 'Punjabi', code: 'pa', flag: 'PA', emojiFlag: '🇮🇳' },
    { name: 'Romanian', code: 'ro', flag: 'RO', emojiFlag: '🇷🇴' },
    { name: 'Russian', code: 'ru', flag: 'RU', emojiFlag: '🇷🇺' },
    { name: 'Samoan', code: 'sm', flag: 'SM', emojiFlag: '🇼🇸' },
    { name: 'Scots Gaelic', code: 'gd', flag: 'GD', emojiFlag: '🏴󠁧󠁢󠁳󠁣󠁴󠁿' },
    { name: 'Serbian', code: 'sr', flag: 'SR', emojiFlag: '🇷🇸' },
    { name: 'Sesotho', code: 'st', flag: 'ST', emojiFlag: '🇱🇸' },
    { name: 'Shona', code: 'sn', flag: 'SN', emojiFlag: '🇿🇼' },
    { name: 'Sindhi', code: 'sd', flag: 'SD', emojiFlag: '🇵🇰' },
    { name: 'Sinhala', code: 'si', flag: 'SI', emojiFlag: '🇱🇰' },
    { name: 'Slovak', code: 'sk', flag: 'SK', emojiFlag: '🇸🇰' },
    { name: 'Slovenian', code: 'sl', flag: 'SL', emojiFlag: '🇸🇮' },
    { name: 'Somali', code: 'so', flag: 'SO', emojiFlag: '🇸🇴' },
    { name: 'Spanish', code: 'es', flag: 'ES', emojiFlag: '🇪🇸' },
    { name: 'Sundanese', code: 'su', flag: 'SU', emojiFlag: '🇮🇩' },
    { name: 'Swahili', code: 'sw', flag: 'SW', emojiFlag: '🇹🇿' },
    { name: 'Swedish', code: 'sv', flag: 'SV', emojiFlag: '🇸🇪' },
    { name: 'Tajik', code: 'tg', flag: 'TG', emojiFlag: '🇹🇯' },
    { name: 'Tamil', code: 'ta', flag: 'TA', emojiFlag: '🇮🇳' },
    { name: 'Tatar', code: 'tt', flag: 'TT', emojiFlag: '🇷🇺' },
    { name: 'Telugu', code: 'te', flag: 'TE', emojiFlag: '🇮🇳' },
    { name: 'Thai', code: 'th', flag: 'TH', emojiFlag: '🇹🇭' },
    { name: 'Turkish', code: 'tr', flag: 'TR', emojiFlag: '🇹🇷' },
    { name: 'Turkmen', code: 'tk', flag: 'TK', emojiFlag: '🇹🇲' },
    { name: 'Ukrainian', code: 'uk', flag: 'UK', emojiFlag: '🇺🇦' },
    { name: 'Urdu', code: 'ur', flag: 'UR', emojiFlag: '🇵🇰' },
    { name: 'Uyghur', code: 'ug', flag: 'UG', emojiFlag: '🇨🇳' },
    { name: 'Uzbek', code: 'uz', flag: 'UZ', emojiFlag: '🇺🇿' },
    { name: 'Vietnamese', code: 'vi', flag: 'VI', emojiFlag: '🇻🇳' },
    { name: 'Welsh', code: 'cy', flag: 'CY', emojiFlag: '🏴󠁧󠁢󠁷󠁬󠁳󠁿' },
    { name: 'Xhosa', code: 'xh', flag: 'XH', emojiFlag: '🇿🇦' },
    { name: 'Yiddish', code: 'yi', flag: 'YI', emojiFlag: '🇮🇱' },
    { name: 'Yoruba', code: 'yo', flag: 'YO', emojiFlag: '🇳🇬' },
    { name: 'Zulu', code: 'zu', flag: 'ZU', emojiFlag: '🇿🇦' }
  ].sort((a, b) => (a.name === 'Auto' ? -1 : b.name === 'Auto' ? 1 : a.name.localeCompare(b.name)));

  useEffect(() => {
    const translationEnabled = localStorage.getItem(`translation_enabled_${chatId}`) === 'true';
    const translationLanguage = localStorage.getItem(`translation_language_${chatId}`) || 'auto';
    const translationMode = localStorage.getItem(`translation_mode_${chatId}`) || 'selective';
    setEnableTranslation(translationEnabled);
    setSelectedLanguage(translationLanguage);
    setTranslationMode(translationMode);
  }, [chatId]);

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
        setIsDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleSave = () => {
    localStorage.setItem(`translation_enabled_${chatId}`, enableTranslation);
    localStorage.setItem(`translation_language_${chatId}`, selectedLanguage);
    localStorage.setItem(`translation_mode_${chatId}`, translationMode);
    if (enableTranslation) {
      localStorage.setItem(`translation_enabled_timestamp_${chatId}`, new Date().toISOString());
    } else {
      localStorage.removeItem(`translation_enabled_timestamp_${chatId}`);
    }
    onSave({
      isTranslationEnabled: enableTranslation,
      translationLanguage: selectedLanguage,
      translationMode,
    });
    onClose();
  };

  const currentLanguage = languages.find(lang => lang.code === selectedLanguage) || languages[0];

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
        <div className="translation-mode-selection">
          <label>
            <input
              type="radio"
              name="translationMode"
              value="all"
              checked={translationMode === 'all'}
              onChange={() => setTranslationMode('all')}
              disabled={!enableTranslation}
            />
            Translate all messages in chat
          </label>
          <label>
            <input
              type="radio"
              name="translationMode"
              value="new"
              checked={translationMode === 'new'}
              onChange={() => setTranslationMode('new')}
              disabled={!enableTranslation}
            />
            Translate only new messages
          </label>
          <label>
            <input
              type="radio"
              name="translationMode"
              value="selective"
              checked={translationMode === 'selective'}
              onChange={() => setTranslationMode('selective')}
              disabled={!enableTranslation}
            />
            Translate selectively
          </label>
        </div>
        <label className="select-label">
          Language:
          <div className="custom-select" ref={dropdownRef} role="combobox" aria-expanded={isDropdownOpen}>
            <div
              className={`select-selected ${isDropdownOpen ? 'open' : ''}`}
              onClick={() => setIsDropdownOpen(!isDropdownOpen)}
            >
              <span className="select-flag">{currentLanguage.flag}</span>
              <span className="select-name">{currentLanguage.name}</span>
              <span className="select-emoji-flag">{currentLanguage.emojiFlag}</span>
            </div>
            {isDropdownOpen && (
              <div className="select-items">
                {languages.map((lang) => (
                  <div
                    key={lang.code}
                    className={`select-option ${lang.code === selectedLanguage ? 'selected' : ''}`}
                    onClick={() => {
                      setSelectedLanguage(lang.code);
                      setIsDropdownOpen(false);
                    }}
                  >
                    <span className="select-flag">{lang.flag}</span>
                    <span className="select-name">{lang.name}</span>
                    <span className="select-emoji-flag">{lang.emojiFlag}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
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