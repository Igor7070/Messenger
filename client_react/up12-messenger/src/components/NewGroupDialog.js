import React, { useEffect, useMemo, useState } from 'react';
import { createChat } from '../api/messages';
import { addMember } from '../api/chatMembers';
import '../styles/NewGroupDialog.css';
import { nameOf } from '../api/names';

const API = 'https://unlimitedpossibilities12.org/api';

/**
 * Окно «Новая группа».
 *
 * Прежде группу заводили двумя системными запросами подряд: «Group name»
 * в окошке браузера, затем «Сделать публичной? Отмена — частная». Участников
 * выбрать было нельзя вовсе — группа рождалась пустой, и людей звали потом
 * ссылкой. Теперь имя, вид и люди спрашиваются разом, как в приложении на
 * телефоне.
 */
const NewGroupDialog = ({ currentUserId, onClose, onCreated }) => {
  const [name, setName] = useState('');
  const [isPublic, setIsPublic] = useState(false);
  const [people, setPeople] = useState([]);
  const [chosen, setChosen] = useState([]);
  const [search, setSearch] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    fetch(`${API}/users`, { credentials: 'include' })
      .then((response) => (response.ok ? response.json() : []))
      .then((list) => {
        if (!alive) return;
        setPeople((list || []).filter((person) => Number(person.id) !== Number(currentUserId)));
      })
      .catch(() => setPeople([]));
    return () => {
      alive = false;
    };
  }, [currentUserId]);

  const visible = useMemo(() => {
    const term = search.trim().toLowerCase();
    if (!term) return people;
    return people.filter((person) => nameOf(person).toLowerCase().includes(term)
      || (person.username || '').toLowerCase().includes(term));
  }, [people, search]);

  const toggle = (id) =>
    setChosen((current) =>
      current.includes(id) ? current.filter((value) => value !== id) : [...current, id]
    );

  const create = async () => {
    if (!name.trim()) {
      setError('The group needs a name');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const chat = await createChat({ name: name.trim(), private: false, isPublic });
      // Выбранных добавляем сразу: неудача с одним не отменяет остальных.
      await Promise.allSettled(chosen.map((userId) => addMember(chat.id, userId)));
      onCreated(chat, chosen.length);
    } catch (problem) {
      setError(problem.message || 'Could not create the group');
      setBusy(false);
    }
  };

  return (
    <div className="up-modal-backdrop" onClick={onClose}>
      <div className="up-modal" onClick={(event) => event.stopPropagation()}>
        <h3 className="up-modal-title">New group</h3>

        <input
          className="up-modal-input"
          placeholder="Group name"
          value={name}
          autoFocus
          onChange={(event) => setName(event.target.value)}
        />

        <label className="up-modal-check">
          <input
            type="checkbox"
            checked={isPublic}
            onChange={(event) => setIsPublic(event.target.checked)}
          />
          <span>Public group — anyone can find and read it</span>
        </label>

        <div className="up-modal-section">
          Add members
          {chosen.length > 0 && <span className="up-modal-counter">{chosen.length}</span>}
        </div>

        <input
          className="up-modal-input"
          placeholder="Search people…"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />

        <div className="up-modal-people">
          {visible.map((person) => (
            <label key={person.id} className="up-modal-person">
              <input
                type="checkbox"
                checked={chosen.includes(person.id)}
                onChange={() => toggle(person.id)}
              />
              {person.avatar ? (
                <img
                  className="up-modal-avatar"
                  src={`https://unlimitedpossibilities12.org${person.avatar}`}
                  alt=""
                />
              ) : (
                <span className="up-modal-avatar up-modal-avatar-letter">
                  {(nameOf(person) || '?').charAt(0).toUpperCase()}
                </span>
              )}
              <span className="up-modal-person-name">{nameOf(person)}</span>
            </label>
          ))}
          {visible.length === 0 && <p className="up-modal-empty">Nobody found</p>}
        </div>

        {error && <p className="up-modal-error">{error}</p>}

        <div className="up-modal-actions">
          <button type="button" className="up-modal-ghost" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="up-modal-primary" onClick={create} disabled={busy}>
            {busy ? 'Creating…' : 'Create group'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default NewGroupDialog;
