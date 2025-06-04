import React, { useState, useEffect, useRef } from 'react';
import '../styles/TranslationSettingsModal.css';

const TranslationSettingsModal = ({ chatId, onSave, onClose }) => {
  const [enableTranslation, setEnableTranslation] = useState(false);
  const [translateNewOnly, setTranslateNewOnly] = useState(false);
  const [selectedLanguage, setSelectedLanguage] = useState('auto');
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const dropdownRef = useRef(null);

  const languages = [
    { name: 'Auto', code: 'auto', flag: 'AUTO' },
    { name: 'Afrikaans', code: 'af', flag: 'AF' },
    { name: 'Albanian', code: 'sq', flag: 'SQ' },
    { name: 'Amharic', code: 'am', flag: 'AM' },
    { name: 'Arabic', code: 'ar', flag: 'AR' },
    { name: 'Armenian', code: 'hy', flag: 'HY' },
    { name: 'Azerbaijani', code: 'az', flag: 'AZ' },
    { name: 'Basque', code: 'eu', flag: 'EU' },
    { name: 'Belarusian', code: 'be', flag: 'BE' },
    { name: 'Bengali', code: 'bn', flag: 'BN' },
    { name: 'Bosnian', code: 'bs', flag: 'BS' },
    { name: 'Bulgarian', code: 'bg', flag: 'BG' },
    { name: 'Catalan', code: 'ca', flag: 'CA' },
    { name: 'Cebuano', code: 'ceb', flag: 'CEB' },
    { name: 'Chichewa', code: 'ny', flag: 'NY' },
    { name: 'Chinese (Simplified)', code: 'zh', flag: 'ZH' },
    { name: 'Chinese (Traditional)', code: 'zh-TW', flag: 'ZH-TW' },
    { name: 'Corsican', code: 'co', flag: 'CO' },
    { name: 'Croatian', code: 'hr', flag: 'HR' },
    { name: 'Czech', code: 'cs', flag: 'CS' },
    { name: 'Danish', code: 'da', flag: 'DA' },
    { name: 'Dutch', code: 'nl', flag: 'NL' },
    { name: 'English', code: 'en', flag: 'EN' },
    { name: 'Esperanto', code: 'eo', flag: 'EO' },
    { name: 'Estonian', code: 'et', flag: 'ET' },
    { name: 'Filipino', code: 'tl', flag: 'TL' },
    { name: 'Finnish', code: 'fi', flag: 'FI' },
    { name: 'French', code: 'fr', flag: 'FR' },
    { name: 'Frisian', code: 'fy', flag: 'FY' },
    { name: 'Galician', code: 'gl', flag: 'GL' },
    { name: 'Georgian', code: 'ka', flag: 'KA' },
    { name: 'German', code: 'de', flag: 'DE' },
    { name: 'Greek', code: 'el', flag: 'EL' },
    { name: 'Gujarati', code: 'gu', flag: 'GU' },
    { name: 'Haitian Creole', code: 'ht', flag: 'HT' },
    { name: 'Hausa', code: 'ha', flag: 'HA' },
    { name: 'Hawaiian', code: 'haw', flag: 'HAW' },
    { name: 'Hebrew', code: 'he', flag: 'HE' },
    { name: 'Hindi', code: 'hi', flag: 'HI' },
    { name: 'Hmong', code: 'hmn', flag: 'HMN' },
    { name: 'Hungarian', code: 'hu', flag: 'HU' },
    { name: 'Icelandic', code: 'is', flag: 'IS' },
    { name: 'Igbo', code: 'ig', flag: 'IG' },
    { name: 'Indonesian', code: 'id', flag: 'ID' },
    { name: 'Irish', code: 'ga', flag: 'GA' },
    { name: 'Italian', code: 'it', flag: 'IT' },
    { name: 'Japanese', code: 'ja', flag: 'JA' },
    { name: 'Javanese', code: 'jv', flag: 'JV' },
    { name: 'Kannada', code: 'kn', flag: 'KN' },
    { name: 'Kazakh', code: 'kk', flag: 'KK' },
    { name: 'Khmer', code: 'km', flag: 'KM' },
    { name: 'Kinyarwanda', code: 'rw', flag: 'RW' },
    { name: 'Korean', code: 'ko', flag: 'KO' },
    { name: 'Kurdish', code: 'ku', flag: 'KU' },
    { name: 'Kyrgyz', code: 'ky', flag: 'KY' },
    { name: 'Lao', code: 'lo', flag: 'LO' },
    { name: 'Latin', code: 'la', flag: 'LA' },
    { name: 'Latvian', code: 'lv', flag: 'LV' },
    { name: 'Lithuanian', code: 'lt', flag: 'LT' },
    { name: 'Luxembourgish', code: 'lb', flag: 'LB' },
    { name: 'Macedonian', code: 'mk', flag: 'MK' },
    { name: 'Malagasy', code: 'mg', flag: 'MG' },
    { name: 'Malay', code: 'ms', flag: 'MS' },
    { name: 'Malayalam', code: 'ml', flag: 'ML' },
    { name: 'Maltese', code: 'mt', flag: 'MT' },
    { name: 'Maori', code: 'mi', flag: 'MI' },
    { name: 'Marathi', code: 'mr', flag: 'MR' },
    { name: 'Mongolian', code: 'mn', flag: 'MN' },
    { name: 'Myanmar (Burmese)', code: 'my', flag: 'MY' },
    { name: 'Nepali', code: 'ne', flag: 'NE' },
    { name: 'Norwegian', code: 'no', flag: 'NO' },
    { name: 'Odia', code: 'or', flag: 'OR' },
    { name: 'Pashto', code: 'ps', flag: 'PS' },
    { name: 'Persian', code: 'fa', flag: 'FA' },
    { name: 'Polish', code: 'pl', flag: 'PL' },
    { name: 'Portuguese', code: 'pt', flag: 'PT' },
    { name: 'Punjabi', code: 'pa', flag: 'PA' },
    { name: 'Romanian', code: 'ro', flag: 'RO' },
    { name: 'Russian', code: 'ru', flag: 'RU' },
    { name: 'Samoan', code: 'sm', flag: 'SM' },
    { name: 'Scots Gaelic', code: 'gd', flag: 'GD' },
    { name: 'Serbian', code: 'sr', flag: 'SR' },
    { name: 'Sesotho', code: 'st', flag: 'ST' },
    { name: 'Shona', code: 'sn', flag: 'SN' },
    { name: 'Sindhi', code: 'sd', flag: 'SD' },
    { name: 'Sinhala', code: 'si', flag: 'SI' },
    { name: 'Slovak', code: 'sk', flag: 'SK' },
    { name: 'Slovenian', code: 'sl', flag: 'SL' },
    { name: 'Somali', code: 'so', flag: 'SO' },
    { name: 'Spanish', code: 'es', flag: 'ES' },
    { name: 'Sundanese', code: 'su', flag: 'SU' },
    { name: 'Swahili', code: 'sw', flag: 'SW' },
    { name: 'Swedish', code: 'sv', flag: 'SV' },
    { name: 'Tajik', code: 'tg', flag: 'TG' },
    { name: 'Tamil', code: 'ta', flag: 'TA' },
    { name: 'Tatar', code: 'tt', flag: 'TT' },
    { name: 'Telugu', code: 'te', flag: 'TE' },
    { name: 'Thai', code: 'th', flag: 'TH' },
    { name: 'Turkish', code: 'tr', flag: 'TR' },
    { name: 'Turkmen', code: 'tk', flag: 'TK' },
    { name: 'Ukrainian', code: 'uk', flag: 'UK' },
    { name: 'Urdu', code: 'ur', flag: 'UR' },
    { name: 'Uyghur', code: 'ug', flag: 'UG' },
    { name: 'Uzbek', code: 'uz', flag: 'UZ' },
    { name: 'Vietnamese', code: 'vi', flag: 'VI' },
    { name: 'Welsh', code: 'cy', flag: 'CY' },
    { name: 'Xhosa', code: 'xh', flag: 'XH' },
    { name: 'Yiddish', code: 'yi', flag: 'YI' },
    { name: 'Yoruba', code: 'yo', flag: 'YO' },
    { name: 'Zulu', code: 'zu', flag: 'ZU' }
  ].sort((a, b) => (a.name === 'Auto' ? -1 : b.name === 'Auto' ? 1 : a.name.localeCompare(b.name)));

  useEffect(() => {
    const translationEnabled = localStorage.getItem(`translation_enabled_${chatId}`) === 'true';
    const translationLanguage = localStorage.getItem(`translation_language_${chatId}`) || 'auto';
    const translateNewOnly = localStorage.getItem(`translate_new_only_${chatId}`) === 'true';
    setEnableTranslation(translationEnabled);
    setSelectedLanguage(translationLanguage);
    setTranslateNewOnly(translateNewOnly);
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
    localStorage.setItem(`translate_new_only_${chatId}`, translateNewOnly);
    if (enableTranslation) {
      localStorage.setItem(`translation_enabled_timestamp_${chatId}`, new Date().toISOString());
    } else {
      localStorage.removeItem(`translation_enabled_timestamp_${chatId}`);
    }
    onSave(enableTranslation, selectedLanguage, translateNewOnly);
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
        <label>
          <input
            type="checkbox"
            checked={translateNewOnly}
            onChange={(e) => setTranslateNewOnly(e.target.checked)}
          />
          Translate only new messages
        </label>
        <label className="select-label">
          Language:
          <div className="custom-select" ref={dropdownRef} role="combobox" aria-expanded={isDropdownOpen}>
            <div
              className={`select-selected ${isDropdownOpen ? 'open' : ''}`}
              onClick={() => setIsDropdownOpen(!isDropdownOpen)}
            >
              <span className="select-flag">{currentLanguage.flag}</span>
              <span>{currentLanguage.name}</span>
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
                    <span>{lang.name}</span>
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