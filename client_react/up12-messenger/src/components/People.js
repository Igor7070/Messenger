import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { useUserStatusContext } from '../context/UserStatusContext';
import '../styles/People.css';

/**
 * Люди.
 *
 * Прежде на этом месте стояли сразу две вещи: список людей и рядом — панель
 * групп с полем «создать групповой чат». Создание групп давно переехало в
 * кнопку «New chat» над списком разговоров, а панель осталась и повторяла
 * его во второй раз.
 *
 * Теперь экран делает одно: показывает людей — так же, как вкладка Contacts
 * в нашем приложении на телефоне и окно «Контакты» в телеграме: поиск
 * сверху, список по алфавиту, у каждого снимок, имя и строка под ним —
 * описание, а если его нет, то «Online» или «Offline». Нажатие открывает
 * карточку человека, откуда можно написать или позвонить.
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

const People = () => {
  const navigate = useNavigate();
  const { usersStatus } = useUserStatusContext();
  const [people, setPeople] = useState([]);
  const [term, setTerm] = useState('');
  const [loading, setLoading] = useState(true);

  const myId = localStorage.getItem('userId');
  // Снимки, которых не оказалось на сервере: вместо пустого места рисуем
  // букву — так же, как у тех, кто снимок не ставил вовсе.
  const [brokenAvatars, setBrokenAvatars] = useState({});

  useEffect(() => {
    let alive = true;
    fetch(`${API}/api/users`)
      .then((response) => {
        if (!response.ok) throw new Error(String(response.status));
        return response.json();
      })
      .then((list) => {
        if (!alive) return;
        // Себя в списке нет: себе не пишут. На телефоне то же самое.
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

  const isOnline = (person) => Boolean(usersStatus?.[person.id]?.online ?? person.online);

  /** Список по алфавиту, разбитый на буквы: [{ letter, people: [...] }] */
  const sections = useMemo(() => {
    const needle = term.trim().toLowerCase();
    const shown = people
      .filter((person) => !needle || (person.username || '').toLowerCase().includes(needle))
      .sort((a, b) => (a.username || '').localeCompare(b.username || '', undefined, { sensitivity: 'base' }));

    const groups = [];
    shown.forEach((person) => {
      const letter = letterOf(person.username);
      const last = groups[groups.length - 1];
      if (last && last.letter === letter) last.people.push(person);
      else groups.push({ letter, people: [person] });
    });
    return groups;
  }, [people, term]);

  const total = sections.reduce((sum, group) => sum + group.people.length, 0);

  return (
    <div className="up-people">
      <h2 className="up-people-title">Contacts</h2>

      <input
        className="up-people-search"
        type="text"
        value={term}
        onChange={(event) => setTerm(event.target.value)}
        placeholder="Search users..."
        aria-label="Search users"
      />

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
                <li key={person.id}>
                  <button
                    type="button"
                    className="up-person"
                    onClick={() => navigate(`/user/${person.id}`)}
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
                          {letterOf(person.username)}
                        </span>
                      )}
                      {online && <span className="up-person-online" title="Online" />}
                    </span>
                    <span className="up-person-body">
                      <span className="up-person-name">{person.username}</span>
                      <span className="up-person-about">
                        {about || (online ? 'Online' : 'Offline')}
                      </span>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
};

export default People;
