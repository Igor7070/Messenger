import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'react-toastify';
import { acceptInvite, previewInvite } from '../api/chatMembers';
import '../styles/InviteAccept.css';

/**
 * Переход по ссылке-приглашению.
 *
 * Сначала показываем карточку группы — название, картинку, число участников, —
 * и только по нажатию вступаем: раньше человек оказывался внутри сразу, не видя,
 * куда именно попал.
 *
 * Ссылка может прийти тому, кто ещё не вошёл: тогда отправляем его на начальный
 * экран и применяем приглашение после входа, иначе оно сгорало бы впустую.
 */
const InviteAccept = () => {
  const { token } = useParams();
  const navigate = useNavigate();
  const [card, setCard] = useState(null);
  const [status, setStatus] = useState('Loading…');
  const [joining, setJoining] = useState(false);
  const asked = useRef(false); // StrictMode вызывает эффект дважды

  useEffect(() => {
    if (asked.current) return;
    asked.current = true;

    if (!localStorage.getItem('userId')) {
      try {
        sessionStorage.setItem('pendingInvite', token);
      } catch { /* приватный режим */ }
      setStatus('Please sign in first — the invite will be applied right after.');
      setTimeout(() => navigate('/auth', { state: { from: `/invite/${token}` } }), 1200);
      return;
    }

    previewInvite(token)
      .then((result) => {
        if (!result.usable) {
          setStatus('This invite is no longer valid.');
          return;
        }
        if (result.alreadyMember) {
          toast.info('You are already in this group');
          navigate(`/chat/${result.chatId}`);
          return;
        }
        setCard(result);
      })
      .catch(() => setStatus('This invite is no longer valid.'));
  }, [token, navigate]);

  const join = useCallback(() => {
    setJoining(true);
    acceptInvite(token)
      .then((result) => {
        // Запас на случай, если сюда пришли после входа: иначе главный экран
        // применил бы то же приглашение второй раз
        try { sessionStorage.removeItem('pendingInvite'); } catch { /* приватный режим */ }
        toast.success(`You joined "${result.name}"`);
        navigate(`/chat/${result.chatId}`);
      })
      .catch(() => {
        setJoining(false);
        toast.error('Could not join the group');
      });
  }, [token, navigate]);

  if (!card) {
    return <div className="invite-card-wrap"><p className="invite-status">{status}</p></div>;
  }

  const avatarUrl = card.avatar ? `https://unlimitedpossibilities12.org${card.avatar}` : null;
  const letter = (card.name || '?').trim().charAt(0).toUpperCase();

  return (
    <div className="invite-card-wrap">
      <div className="invite-card">
        {avatarUrl
          ? <img src={avatarUrl} alt="" className="invite-avatar" />
          : <div className="invite-avatar invite-avatar-letter">{letter}</div>}
        <h2>{card.name}</h2>
        <p className="invite-meta">
          {card.isPublic ? 'Public group' : 'Private group'}
          {' · '}
          {card.members} {card.members === 1 ? 'member' : 'members'}
        </p>
        <div className="invite-actions">
          <button className="invite-join" onClick={join} disabled={joining}>
            {joining ? 'Joining…' : 'Join group'}
          </button>
          <button className="invite-cancel" onClick={() => navigate('/')}>Not now</button>
        </div>
      </div>
    </div>
  );
};

export default InviteAccept;
