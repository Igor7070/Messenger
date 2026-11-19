import React, { useEffect, useState } from 'react';
import '../styles/JoinByLinkDialog.css';

/**
 * Окно «войти в группу по ссылке».
 *
 * Прежде здесь спрашивал сам браузер: белое окно поверх тёмного приложения,
 * с «Подтвердите действие на igor7070.github.io» в заголовке и чужими
 * кнопками. За ним шло второе такое же — «вступить?». Теперь окно своё, а
 * вместо второго вопроса открывается та же карточка группы, что видит
 * человек, перешедший по ссылке: снимок, название, число участников.
 */
const JoinByLinkDialog = ({ onClose, onToken }) => {
  const [link, setLink] = useState('');

  useEffect(() => {
    const onKey = (event) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const token = link.trim().replace(/[#/]+$/, '').split('/').pop();
  const ready = Boolean(token) && token.length >= 8;

  const submit = (event) => {
    event.preventDefault();
    if (ready) onToken(token);
  };

  return (
    <div className="up-join-backdrop" onClick={onClose} role="presentation">
      <form
        className="up-join"
        onClick={(event) => event.stopPropagation()}
        onSubmit={submit}
      >
        <h2 className="up-join-title">Join by invite link</h2>
        <input
          className="up-join-input"
          type="text"
          value={link}
          onChange={(event) => setLink(event.target.value)}
          placeholder="unlimitedpossibilities12.org/invite/…"
          aria-label="Invite link"
          autoFocus
        />
        <p className="up-join-hint">
          Paste the link someone sent you. The group's card opens next, and
          nothing is joined until you say so there.
        </p>
        <div className="up-join-buttons">
          <button type="button" className="up-join-cancel" onClick={onClose}>Cancel</button>
          <button type="submit" className="up-join-go" disabled={!ready}>Continue</button>
        </div>
      </form>
    </div>
  );
};

export default JoinByLinkDialog;
