import React, { useCallback, useEffect, useRef, useState } from 'react';
import NewGroupDialog from './NewGroupDialog';
import JoinByLinkDialog from './JoinByLinkDialog';
import { useLocation, useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { loadConversations, previewForRow, ASSISTANT_ID } from '../api/conversations';
import { stompAuthHeaders } from '../authToken';
import { createChat } from '../api/messages';
import { previewInvite } from '../api/chatMembers';
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
    // Язык даты — язык самого окна: рядом стоят Today и Yesterday,
    // и «18 нояб.» в том же столбце выглядело случайной оговоркой.
    : date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
};

/**
 * Левая колонка: один список всех разговоров.
 *
 * Список живёт в каркасе и не перерисовывается при переходах между чатами —
 * раньше каждый чат был отдельной страницей, и список собирался заново.
 */
const ChatList = ({ onOpenPeople = () => {}, onOpenCalls = () => {}, groupWanted = 0 }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const { openAssistant } = useAssistant();

  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [groupDialogOpen, setGroupDialogOpen] = useState(false);
  const [joinDialogOpen, setJoinDialogOpen] = useState(false);
  // Картинка чата может не открыться: файлы на сервере пропадают при
  // переразвёртывании. Тогда показываем кружок с буквой, а не битый значок.
  const [brokenAvatars, setBrokenAvatars] = useState({});
  const menuRef = useRef(null);

  const currentUserId = localStorage.getItem('userId');
  const currentUsername = localStorage.getItem('username') || '';
  // Свой снимок для кружка меню. Прежде там стояла одна буква имени, хотя
  // у человека загружен аватар, — и в списке ниже его снимок был виден, а
  // в собственном кружке нет.
  const [myAvatar, setMyAvatar] = useState(localStorage.getItem('userAvatar') || '');

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

  // Новая группа — своим окном: имя, вид и участники разом. Прежде здесь
  // стояли два системных запроса браузера подряд, а людей в группу
  // пригласить при создании было нельзя вовсе.
  const newGroup = () => {
    setMenuOpen(false);
    setGroupDialogOpen(true);
  };

  // Из окна людей просят завести группу — открываем то же окно, что и
  // пункт меню. Считаем нажатия, иначе второй раз подряд он бы не открылся.
  useEffect(() => {
    if (groupWanted > 0) setGroupDialogOpen(true);
  }, [groupWanted]);

  const groupCreated = (chat, invited) => {
    setGroupDialogOpen(false);
    toast.success(invited > 0 ? `Group created, ${invited} invited` : 'Group created');
    refresh();
    if (chat && chat.id) navigate(`/chat/${chat.id}`);
  };


  /**
   * Выход из учётной записи. Раньше он жил только на странице контактов, в
   * выпадающем списке под аватаром, — из основной оболочки выйти было нечем.
   */
  const signOut = () => {
    setMenuOpen(false);
    if (!window.confirm('Sign out of this account?')) return;
    try {
      localStorage.removeItem('authToken');
      localStorage.removeItem('userId');
      localStorage.removeItem('username');
      localStorage.removeItem('userAvatar');
    } catch {
      /* приватный режим */
    }
    navigate('/auth', { replace: true });
    window.location.reload(); // сбрасываем сокеты и состояние контекстов
  };

  const joinByLink = () => {
    setMenuOpen(false);
    setJoinDialogOpen(true);
  };

  /**
   * Ссылку разобрали — дальше показываем ту же карточку группы, что видит
   * человек, перешедший по ссылке: снимок, название, число участников и
   * кнопку «вступить». Прежде вместо карточки спрашивал браузер своим окном.
   */
  const joinWithToken = async (token) => {
    setJoinDialogOpen(false);
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
      navigate(`/invite/${token}`);
    } catch (error) {
      toast.error('This invite is no longer valid');
    }
  };

  const visible = search
    ? items.filter((item) => item.name.toLowerCase().includes(search.toLowerCase()))
    : items;

  useEffect(() => {
    if (!currentUserId) return undefined;
    let alive = true;
    fetch(`https://unlimitedpossibilities12.org/api/users/${currentUserId}`, { credentials: 'include' })
      .then((response) => (response.ok ? response.json() : null))
      .then((user) => {
        if (!alive || !user) return;
        const picture = user.avatar || '';
        setMyAvatar(picture);
        try {
          if (picture) localStorage.setItem('userAvatar', picture);
          else localStorage.removeItem('userAvatar');
        } catch {
          /* приватный режим */
        }
      })
      .catch(() => {
        /* снимок не обязателен: остаётся буква */
      });
    return () => {
      alive = false;
    };
  }, [currentUserId]);

  const activeRoute = location.pathname;

  return (
    <aside className="up-list">
      {joinDialogOpen && (
        <JoinByLinkDialog
          onClose={() => setJoinDialogOpen(false)}
          onToken={joinWithToken}
        />
      )}
      {groupDialogOpen && (
        <NewGroupDialog
          currentUserId={currentUserId}
          onClose={() => setGroupDialogOpen(false)}
          onCreated={groupCreated}
        />
      )}
      <header className="up-list-header">
        {/*
          Кружок с аватаром — вход в меню: именно по нему жмут, когда ищут
          «всё про меня». Раньше он вёл прямо в профиль, а разделы (люди,
          звонки, новая группа) прятались под «тремя точками» справа, и их
          не находили. Теперь вход один.
        */}
        <div className="up-list-menu-wrap" ref={menuRef}>
          <button
            type="button"
            className="up-list-avatar"
            title="Menu"
            onClick={() => setMenuOpen((open) => !open)}
          >
            {myAvatar && !brokenAvatars.me ? (
              <img
                className="up-list-avatar-picture"
                src={`https://unlimitedpossibilities12.org${myAvatar}`}
                alt=""
                onError={() => setBrokenAvatars((broken) => ({ ...broken, me: true }))}
              />
            ) : (
              (currentUsername || '?').charAt(0).toUpperCase()
            )}
          </button>
          {menuOpen && (
            <div className="up-list-menu up-list-menu-left">
              <button
                type="button"
                onClick={() => {
                  setMenuOpen(false);
                  if (currentUserId) navigate(`/user/${currentUserId}`);
                }}
              >
                My profile
              </button>
              <button
                type="button"
                onClick={() => { setMenuOpen(false); onOpenPeople(); }}
              >
                People
              </button>
              <button
                type="button"
                onClick={() => { setMenuOpen(false); onOpenCalls(); }}
              >
                Calls
              </button>
              <button type="button" onClick={newGroup}>New group</button>
              <button
                type="button"
                onClick={() => { setMenuOpen(false); navigate('/settings'); }}
              >
                Settings
              </button>
              <button type="button" onClick={joinByLink}>Join by invite link</button>
              <button type="button" className="up-list-menu-danger" onClick={signOut}>
                Sign out
              </button>
              <p className="up-list-menu-build">Build {BUILD_STAMP}</p>
            </div>
          )}
        </div>
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
                {/* Замок перед именем — у секретного разговора. Прежде
                    секретность отмечала плашка у правого края, рядом со
                    временем, и два разговора с одним человеком различить
                    было нечем. */}
                {item.kind === 'secret' && (
                  <svg className="up-row-lock" width="13" height="13" viewBox="0 0 24 24" fill="none"
                       stroke="currentColor" strokeWidth="2" aria-hidden="true">
                    <rect x="5" y="11" width="14" height="10" rx="2" />
                    <path d="M8 11V7a4 4 0 0 1 8 0v4" />
                  </svg>
                )}
                <span className="up-row-name">{item.name}</span>
                {item.badge && <span className="up-row-badge">{item.badge}</span>}
                {/* Галочки у своего последнего сообщения: одна — дошло,
                    две — прочли. Раньше судьбу своего сообщения в списке
                    было не видно вовсе. */}
                {item.lastFromMe && item.lastDelivered && (
                  <span className={`up-row-ticks ${item.lastRead ? 'up-row-ticks-read' : ''}`}>
                    <svg width="16" height="11" viewBox="0 0 24 16" fill="none" stroke="currentColor"
                         strokeWidth="2.4" strokeLinecap="round" aria-hidden="true">
                      <path d="M1 8.5l4.5 4.5L14 3" />
                      {item.lastRead && <path d="M9 8.5l4.5 4.5L22 3" />}
                    </svg>
                  </span>
                )}
                <span className="up-row-time">{formatTime(item.timestamp)}</span>
              </span>
              <span className="up-row-line">
                {/* Крошечный снимок перед словом «Photo» — чтобы узнать
                    фотографию, не открывая разговор */}
                {item.lastPhotoUrl && !brokenAvatars[`photo-${item.id}`] && (
                  <img
                    className="up-row-thumb"
                    src={item.lastPhotoUrl}
                    alt=""
                    onError={() =>
                      setBrokenAvatars((broken) => ({ ...broken, [`photo-${item.id}`]: true }))
                    }
                  />
                )}
                <span className="up-row-preview">{item.preview}</span>
                {item.unreadCount > 0 && <span className="up-row-unread">{item.unreadCount}</span>}
              </span>
            </span>
          </button>
        ))}
      </div>

      <footer className="up-list-footer">
        <button type="button" className="up-list-new" onClick={onOpenPeople}>
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
