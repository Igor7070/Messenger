import React, { useState } from 'react';
import { webSettings, setWebSetting } from '../api/webSettings';
import '../styles/Settings.css';

/**
 * Настройки веб-клиента.
 *
 * Прежде их не было вовсе: звук уведомления звенел всегда, а что показывать
 * в уведомлении, решал не хозяин. В приложении на телефоне такой экран уже
 * есть — здесь те же самые переключатели, чтобы оба клиента вели себя
 * одинаково.
 */
const Settings = () => {
  const [sound, setSound] = useState(webSettings.notificationSound());
  const [preview, setPreview] = useState(webSettings.notificationPreview());

  const rows = [
    {
      key: 'sound',
      title: 'Sound',
      hint: 'Play a sound when a message arrives',
      value: sound,
      change: (on) => {
        setSound(on);
        setWebSetting('notificationSound', on);
      },
    },
    {
      key: 'preview',
      title: 'Show message text',
      hint: 'In notifications. Secret chats never show their text',
      value: preview,
      change: (on) => {
        setPreview(on);
        setWebSetting('notificationPreview', on);
      },
    },
  ];

  return (
    <div className="up-settings">
      <h2>Settings</h2>
      <p className="up-settings-group">Notifications</p>

      {rows.map((row) => (
        <button
          type="button"
          key={row.key}
          className="up-settings-row"
          onClick={() => row.change(!row.value)}
        >
          <span className="up-settings-text">
            <span className="up-settings-title">{row.title}</span>
            <span className="up-settings-hint">{row.hint}</span>
          </span>
          <span className={`up-settings-switch ${row.value ? 'up-settings-switch-on' : ''}`}>
            <span className="up-settings-knob" />
          </span>
        </button>
      ))}

      <p className="up-settings-note">
        A browser shows notifications only while this page is open and only with its
        permission; the phone receives them even when the application is closed.
      </p>
    </div>
  );
};

export default Settings;
