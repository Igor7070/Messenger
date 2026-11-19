import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { useUserStatusContext } from '../context/UserStatusContext';
import { findOrCreatePrivateChat } from '../api/privateChats';
import { nameOf } from '../api/names';
import '../styles/People.css';

/**
 * Люди — окошком поверх приложения, как «Контакты» в телеграме.
 *
 * Прежде это был раздел во всю правую половину окна. Половина окна для списка
 * имён велика: строки растягивались на метр, а переписка позади закрывалась
 * совсем. Теперь список висит окошком у края колонки разговоров: разговор
 * позади видно, окно закрывается крестиком, кнопкой внизу, щелчком мимо или
 * клавишей Esc.
 *
 * Нажатие на человека открывает переписку с ним — так же, как в телеграме.
 * Кнопка у правого края строки ведёт в его карточку: снимок, описание,
 * звонки — как по нажатию на человека в приложении на телефоне.
 */

const API = 'https://unlimitedpossibilities12.org';

// Те же цвета, что у букв на телефоне, и тот же счёт по имени — чтобы один
// человек выглядел одинаково на обоих устройствах.
const AVATAR_COLORS = ['#6366F1', '#0D9488', '#8B5CF6', '#0EA5E9', '#DB2777', '#475569'];

const colorFor = (name) => {
  if (!name) return AVATAR_COLORS[0];
  let hash = 0;
  for (let i = 0; i < name.length; i += 1) {
    hash = (hash * 31 + name.charCodeAt(i)) | 0;
  }
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
};

const letterOf = (name) => {
  const clean = (name || '').trim();
  return clean ? clean.charAt(0).toUpperCase() : '#';
};

const People = ({ onClose, onNewGroup }) => {
  const navigate = useNavigate();
  const { usersStatus } = useUserStatusContext();
  const [people, setPeople] = useState([]);
  const [term, setTerm] = useState('');
  const [loading, setLoading] = useState(true);
  // Снимки, которых не оказалось на сервере: вместо пустого места рисуем
  // букву — так же, как у тех, кто снимок не ставил вовсе.
  const [brokenAvatars, setBrokenAvatars] = useState({});
  const [opening, setOpening] = useState(null);

  const myId = localStorage.getItem('userId');

  useEffect(() => {
    let alive = true;
    fetch(`${API}/api/users`)
      .then((response) => {
        if (!response.ok) throw new Error(String(response.status));
        return response.json();
      })
      .then((list) => {
        if (!alive) return;
        // Себя в списке нет: себе не пишут.
        setPeople((Array.isArray(list) ? list : []).filter(
          (person) => String(person.id) !== String(myId)
        ));
      })
      .catch(() => {
        if (alive) toast.error('Could not load the list of people');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => { alive = false; };
  }, [myId]);

  // Esc закрывает окно — как любое окно поверх приложения
  useEffect(() => {
    const onKey = (event) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const isOnline = (person) => Boolean(usersStatus?.[person.id]?.online ?? person.online);

  const writeTo = (person) => {
    setOpening(person.id);
    findOrCreatePrivateChat(person, myId)
      .then((route) => { onClose(); navigate(route); })
      .catch(() => toast.error(`Could not open the chat with ${nameOf(person)}`))
      .finally(() => setOpening(null));
  };

  /** Список по алфавиту, разбитый на буквы: [{ letter, people: [...] }] */
  const sections = useMemo(() => {
    const needle = term.trim().toLowerCase();
    const shown = people
      .filter((person) => !needle
        || nameOf(person).toLowerCase().includes(needle)
        || (person.username || '').toLowerCase().includes(needle))
      .sort((a, b) => nameOf(a).localeCompare(nameOf(b), undefined, { sensitivity: 'base' }));

    const groups = [];
    shown.forEach((person) => {
      const letter = letterOf(nameOf(person));
      const last = groups[groups.length - 1];
      if (last && last.letter === letter) last.people.push(person);
      else groups.push({ letter, people: [person] });
    });
    return groups;
  }, [people, term]);

  const total = sections.reduce((sum, group) => sum + group.people.length, 0);

  return (
    <div
      className="up-people-backdrop"
      onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}
      role="presentation"
    >
      <div
        className="up-people-panel"
        onClick={(event) => event.stopPropagation()}
        role="dialog"
        aria-label="Contacts"
      >
        <header className="up-people-head">
          <h2 className="up-people-title">Contacts</h2>
          <button type="button" className="up-people-close" title="Close" onClick={onClose}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </header>

        <div className="up-people-search-row">
          <svg className="up-people-search-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-3.5-3.5" strokeLinecap="round" />
          </svg>
          <input
            className="up-people-search"
            type="text"
            value={term}
            onChange={(event) => setTerm(event.target.value)}
            placeholder="Search"
            aria-label="Search users"
            autoFocus
          />
        </div>

        <div className="up-people-scroll">
          {/* Группа заводится отсюда же: кнопка «New chat» ведёт в это окно, и
              если человеку нужна не переписка с кем-то одним, а группа, он не
              должен искать её в меню. Строка стоит первой, как в привычных
              мессенджерах. */}
          {onNewGroup && !term.trim() && (
            <button type="button" className="up-people-new-group" onClick={onNewGroup}>
              <span className="up-people-new-group-icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <circle cx="9" cy="8" r="3.4" />
                  <path d="M2.6 19c0-3.1 3-4.9 6.4-4.9s6.4 1.8 6.4 4.9z" />
                  <path d="M18 7.6v6M15 10.6h6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" fill="none" />
                </svg>
              </span>
              New group
            </button>
          )}

          {loading && <p className="up-people-empty">Loading…</p>}

          {!loading && total === 0 && (
            <p className="up-people-empty">
              {term.trim() ? 'Nobody with such a name' : 'Nobody here yet'}
            </p>
          )}

          {sections.map((group) => (
            <section key={group.letter} className="up-people-section">
              <h3 className="up-people-letter">{group.letter}</h3>
              <ul className="up-people-list">
                {group.people.map((person) => {
                  const online = isOnline(person);
                  const about = (person.info || '').trim();
                  return (
                    <li key={person.id} className="up-person-row">
                      <button
                        type="button"
                        className="up-person"
                        onClick={() => writeTo(person)}
                        disabled={opening === person.id}
                      >
                        <span className="up-person-avatar-wrap">
                          {person.avatar && !brokenAvatars[person.id] ? (
                            <img
                              className="up-person-avatar"
                              src={`${API}${person.avatar}`}
                              alt=""
                              onError={() => setBrokenAvatars((prev) => ({ ...prev, [person.id]: true }))}
                            />
                          ) : (
                            <span
                              className="up-person-avatar up-person-avatar-letter"
                              style={{ background: colorFor(person.username) }}
                            >
                              {letterOf(nameOf(person))}
                            </span>
                          )}
                          {online && <span className="up-person-online" title="Online" />}
                        </span>
                        <span className="up-person-body">
                          <span className="up-person-name">{nameOf(person)}</span>
                          <span className="up-person-about">
                            {about || (online ? 'Online' : 'Offline')}
                          </span>
                        </span>
                      </button>
                      <button
                        type="button"
                        className="up-person-card"
                        title={`${nameOf(person)}'s profile`}
                        onClick={() => { onClose(); navigate(`/user/${person.id}`); }}
                      >
                        <svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                          <circle cx="12" cy="8" r="3.6" />
                          <path d="M4.8 19.2c0-3.3 3.2-5.2 7.2-5.2s7.2 1.9 7.2 5.2z" />
                        </svg>
                      </button>
                    </li>
                  );
                })}
              </ul>
            </section>
          ))}
        </div>

        <footer className="up-people-foot">
          <button type="button" className="up-people-done" onClick={onClose}>Close</button>
        </footer>
      </div>
    </div>
  );
};

export default People;
