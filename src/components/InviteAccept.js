import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'react-toastify';
import { acceptInvite } from '../api/chatMembers';

/**
 * Переход по ссылке-приглашению.
 *
 * Ссылка может прийти человеку, который ещё не вошёл: тогда отправляем его на
 * начальный экран и возвращаем сюда после входа — иначе приглашение сгорало бы
 * впустую, а он не понял бы, почему.
 */
const InviteAccept = () => {
  const { token } = useParams();
  const navigate = useNavigate();
  const [status, setStatus] = useState('Joining the group…');
  const done = useRef(false); // StrictMode вызывает эффект дважды

  useEffect(() => {
    if (done.current) return;
    done.current = true;

    const userId = localStorage.getItem('userId');
    if (!userId) {
      try {
        sessionStorage.setItem('pendingInvite', token);
      } catch { /* приватный режим */ }
      setStatus('Please sign in first — the invite will be applied right after.');
      setTimeout(() => navigate('/'), 1500);
      return;
    }

    acceptInvite(token)
      .then((result) => {
        toast.success(`You joined "${result.name}"`);
        navigate(`/chat/${result.chatId}`);
      })
      .catch((error) => {
        console.error('Invite failed:', error);
        setStatus('This invite is no longer valid.');
        toast.error('This invite is no longer valid');
        setTimeout(() => navigate('/'), 2000);
      });
  }, [token, navigate]);

  return <div style={{ padding: 32, textAlign: 'center' }}>{status}</div>;
};

export default InviteAccept;
