import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { loadConversations, previewForRow, ASSISTANT_ID } from '../api/conversations';
import { stompAuthHeaders } from '../authToken';
import { createChat } from '../api/messages';
import { previewInvite, acceptInvite } from '../api/chatMembers';
import { useAssistant } from '../context/AssistantContext';
import BUILD_STAMP from '../buildStamp';
import '../styles/ChatList.css';

const AVATAR_COLORS = ['#6366f1', '#0d9488', '#8b5cf6', '#0ea5e9', '#db2777', '#475569'];

/** Цвет кружка подбирается по имени, чтобы у чата он не прыгал. */
const colorFor = (name) => {
  let hash = 0;
  for (let i = 0; i < String(name).length; i += 1) hash = hash * 31 + String(name).charCodeAt(i);
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
};

const formatTime = (value) => {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const today = new Date();
  const sameDay =
    date.getDate() === today.getDate() &&
    date.getMonth() === today.getMonth() &&
    date.getFullYear() === today.getFullYear();
  return sameDay
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString([], { day: 'numeric', month: 'short' });
};

/**
 * Левая колонка: один список всех разговоров.
 *
 * Список живёт в каркасе и не перерисовывается при переходах между чатами —
 * раньше каждый чат был отдельной страницей, и список собирался заново.
 */
const ChatList = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { openAssistant } = useAssistant();

  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  // Картинка чата может не открыться: файлы на сервере пропадают при
  // переразвёртывании. Тогда показываем кружок с буквой, а не битый значок.
  const [brokenAvatars, setBrokenAvatars] = useState({});
  const menuRef = useRef(null);

  const currentUserId = localStorage.getItem('userId');
  const currentUsername = localStorage.getItem('username') || '';

  // Текущий адрес держим в ссылке, а не в зависимостях: иначе при каждом
  // переходе между чатами пересоздавались и опрос списка, и постоянное
  // соединение — отсюда задержки и «непереключение» с первого нажатия
  const pathRef = useRef(location.pathname);
  pathRef.current = location.pathname;

  // Идущий опрос, чтобы отменить его, когда начинается новый или человек
  // уходит со страницы: брошенный запрос всё равно занимает соединение.
  const pollRef = useRef(null);

  const refresh = useCallback(async () => {
    if (pollRef.current) pollRef.current.abort();
    const controller = new AbortController();
    pollRef.current = controller;
    try {
      const loaded = await loadConversations(currentUserId, currentUsername, controller.signal);
      // У открытого чата счётчик гасим сразу: отметка «прочитано» уходит на
      // сервер из самой переписки через секунду после открытия, и список,
      // пересчитанный раньше, показывал бы прежнее число, пока чат открыт.
      setItems(
        loaded.map((item) =>
          item.route && item.route === pathRef.current ? { ...item, unreadCount: 0 } : item
        )
      );
      setLoading(false);
    } catch (error) {
      if (!error || error.name !== 'AbortError') {
        console.warn('Could not load the conversation list:', error);
        setLoading(false);
      }
    } finally {
      if (pollRef.current === controller) pollRef.current = null;
    }
  }, [currentUserId, currentUsername]);

  /*
    Полный опрос списка — это запрос истории каждого чата, а браузер держит к
    одному домену всего шесть соединений. Раньше опрос повторялся при каждом
    переходе между чатами, и запрос открываемой переписки вставал в хвост
    очереди: чат появлялся спустя секунды, а то и вовсе казалось, что нажатие
    не сработало. Поэтому собираем список при открытии страницы и потом, когда
    человек возвращается на вкладку, — между этим строки живут за счёт
    подписки на сообщения.
  */
  const lastRefreshRef = useRef(0);
  useEffect(() => {
    lastRefreshRef.current = Date.now();
    refresh();
    return () => {
      if (pollRef.current) pollRef.current.abort();
    };
  }, [refresh]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      if (Date.now() - lastRefreshRef.current < 30000) return;
      lastRefreshRef.current = Date.now();
      refresh();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [refresh]);

  /**
   * Живое обновление строк.
   *
   * Раньше список перечитывался только при открытии страницы и при переходе
   * между чатами: новое сообщение в группе появлялось лишь после перезагрузки.
   * Подписываемся на темы своих чатов одним соединением и правим ту строку,
   * которой сообщение касается.
   */
  const itemsRef = useRef(items);
  itemsRef.current = items;
  const chatIds = items
    .filter((item) => item.kind !== 'assistant')
    .map((item) => item.id)
    .join(',');

  useEffect(() => {
    if (!currentUserId || !chatIds) return undefined;

    const client = new Client({
      webSocketFactory: () => new SockJS('https://unlimitedpossibilities12.org/ws'),
      connectHeaders: stompAuthHeaders(),
      reconnectDelay: 5000,
      onConnect: () => {
        chatIds.split(',').forEach((id) => {
          client.subscribe(`/topic/chat/${id}/messages`, (frame) => {
            let message;
            try {
              message = JSON.parse(frame.body);
            } catch {
              return; // по этой теме приходят и служебные кадры
            }
            const chatId = Number(id);
            const row = itemsRef.current.find((item) => Number(item.id) === chatId);
            if (!row) return;

            // Сюда же прилетают отметки о прочтении — это не новое сообщение
            const messageId = Number(message.id) || 0;
            if (messageId && messageId <= row.lastMessageId) return;

            const mine =
              message.sender && Number(message.sender.id) === Number(currentUserId);
            const open = row.route === pathRef.current;

            setItems((current) =>
              current
                .map((item) =>
                  Number(item.id) === chatId
                    ? {
                        ...item,
                        preview: previewForRow(item, message, currentUserId),
                        timestamp: message.timestamp || item.timestamp,
                        lastMessageId: messageId || item.lastMessageId,
                        unreadCount: mine || open ? item.unreadCount : item.unreadCount + 1,
                      }
                    : item
                )
                .sort((a, b) => {
                  if (a.kind === 'assistant') return -1;
                  if (b.kind === 'assistant') return 1;
                  return String(b.timestamp || '').localeCompare(String(a.timestamp || ''));
                })
            );
          });
        });
      },
      onStompError: (frame) => console.warn('Chat list STOMP error:', frame),
    });

    client.activate();
    return () => client.deactivate();
  }, [chatIds, currentUserId]);

  useEffect(() => {
    const closeOnOutside = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', closeOnOutside);
    return () => document.removeEventListener('mousedown', closeOnOutside);
  }, []);

  // Ctrl/⌘+K — поиск, Esc — закрыть: привычки браузера, а не телефона
  useEffect(() => {
    const onKey = (event) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setSearchOpen(true);
      }
      if (event.key === 'Escape') {
        setMenuOpen(false);
        setSearchOpen(false);
        setSearch('');
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const open = (item) => {
    if (item.kind === 'assistant') {
      openAssistant();
      return;
    }
    // Гасим счётчик, не дожидаясь ответа сервера: человек уже открыл чат
    setItems((current) =>
      current.map((row) => (row.id === item.id ? { ...row, unreadCount: 0 } : row))
    );
    navigate(item.route);
  };

  const newGroup = async () => {
    setMenuOpen(false);
    const name = window.prompt('Group name');
    if (!name || !name.trim()) return;
    const isPublic = window.confirm('Make the group public? Cancel keeps it private.');
    try {
      await createChat({ name: name.trim(), private: false, isPublic });
      toast.success(isPublic ? 'Public group created' : 'Private group created');
      refresh();
    } catch (error) {
      toast.error(error.message || 'Could not create the group');
    }
  };

  const joinByLink = async () => {
    setMenuOpen(false);
    const link = window.prompt('Paste the invite link');
    if (!link) return;
    const token = link.trim().split('/').pop();
    try {
      const card = await previewInvite(token);
      if (!card.usable) {
        toast.error('This invite is no longer valid');
        return;
      }
      if (card.alreadyMember) {
        navigate(`/chat/${card.chatId}`);
        return;
      }
      if (!window.confirm(`Join "${card.name}"? ${card.members} members.`)) return;
      const joined = await acceptInvite(token);
      toast.success(`You joined "${joined.name}"`);
      refresh();
      navigate(`/chat/${joined.chatId}`);
    } catch (error) {
      toast.error('This invite is no longer valid');
    }
  };

  const visible = search
    ? items.filter((item) => item.name.toLowerCase().includes(search.toLowerCase()))
    : items;

  const activeRoute = location.pathname;

  return (
    <aside className="up-list">
      <header className="up-list-header">
        <button
          type="button"
          className="up-list-avatar"
          title="My profile"
          onClick={() => currentUserId && navigate(`/user/${currentUserId}`)}
        >
          {(currentUsername || '?').charAt(0).toUpperCase()}
        </button>
        <h2 className="up-list-title">Chats</h2>
        <button
          type="button"
          className="up-list-icon-button"
          title="Search (Ctrl+K)"
          onClick={() => setSearchOpen((open) => !open)}
        >
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-3.5-3.5" />
          </svg>
        </button>
        <div className="up-list-menu-wrap" ref={menuRef}>
          <button
            type="button"
            className="up-list-icon-button"
            title="More"
            onClick={() => setMenuOpen((open) => !open)}
          >
            <svg width="19" height="19" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <circle cx="12" cy="5" r="1.8" />
              <circle cx="12" cy="12" r="1.8" />
              <circle cx="12" cy="19" r="1.8" />
            </svg>
          </button>
          {menuOpen && (
            <div className="up-list-menu">
              <button type="button" onClick={newGroup}>New group</button>
              <button type="button" onClick={joinByLink}>Join by invite link</button>
              <button
                type="button"
                onClick={() => {
                  setMenuOpen(false);
                  navigate('/calls');
                }}
              >
                Calls
              </button>
              <p className="up-list-menu-build">Build {BUILD_STAMP}</p>
            </div>
          )}
        </div>
      </header>

      {searchOpen && (
        <div className="up-list-search">
          <input
            type="text"
            autoFocus
            value={search}
            placeholder="Search chats"
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>
      )}

      <div className="up-list-items">
        {loading && <p className="up-list-empty">Loading…</p>}
        {!loading && visible.length === 0 && <p className="up-list-empty">Nothing found</p>}

        {visible.map((item) => (
          <button
            type="button"
            key={item.id}
            className={`up-row ${item.route === activeRoute ? 'up-row-active' : ''}`}
            onClick={() => open(item)}
          >
            {item.kind === 'assistant' ? (
              <span className="up-row-avatar up-row-avatar-assistant">
                <svg width="23" height="23" viewBox="0 0 24 24" fill="#a5b4fc" aria-hidden="true">
                  <path d="M13.6 2.5L6 13.2h4.6l-1.2 8.3 7.6-10.7h-4.6z" />
                </svg>
              </span>
            ) : item.avatar && !brokenAvatars[item.id] ? (
              <img
                className="up-row-avatar"
                src={`https://unlimitedpossibilities12.org${item.avatar}`}
                alt=""
                onError={() => setBrokenAvatars((broken) => ({ ...broken, [item.id]: true }))}
              />
            ) : (
              <span className="up-row-avatar" style={{ background: colorFor(item.name) }}>
                {(item.name || '?').trim().charAt(0).toUpperCase()}
              </span>
            )}

            <span className="up-row-body">
              <span className="up-row-line">
                <span className="up-row-name">{item.name}</span>
                {item.badge && <span className="up-row-badge">{item.badge}</span>}
                <span className="up-row-time">{formatTime(item.timestamp)}</span>
              </span>
              <span className="up-row-line">
                <span className="up-row-preview">{item.preview}</span>
                {item.unreadCount > 0 && <span className="up-row-unread">{item.unreadCount}</span>}
              </span>
            </span>
          </button>
        ))}
      </div>

      <footer className="up-list-footer">
        <button type="button" className="up-list-new" onClick={() => navigate('/contacts')}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M16.5 3.8l3.7 3.7L8.6 19.2l-4.6.9.9-4.6z" />
          </svg>
          New chat
        </button>
      </footer>
    </aside>
  );
};

export default ChatList;
