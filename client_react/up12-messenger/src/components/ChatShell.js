import React, { useCallback, useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import ChatList from './ChatList';
import People from './People';
import Calls from './Calls';
import UserProfile from './UserProfile';
import '../styles/tokens.css';
import '../styles/ChatShell.css';
import '../styles/ShellPages.css';

/**
 * Каркас мессенджера: слева список разговоров, справа открытая переписка.
 *
 * Раньше каждый чат был отдельной страницей: при переходе список собирался
 * заново и заново поднималось STOMP-соединение. Теперь меняется только правая
 * часть, а список остаётся на месте — как в веб-клиентах Telegram и WhatsApp.
 *
 * На узком экране колонки не помещаются рядом, поэтому видна одна: пока чат не
 * выбран — список, после выбора — переписка, «назад» возвращает к списку.
 */
const ChatShell = () => {
  const location = useLocation();
  const navigate = useNavigate();

  // Люди живут не разделом, а окошком поверх приложения — как «Контакты» в
  // телеграме: открыл, выбрал, кого написать, и вернулся к той же переписке.
  // Прежний адрес /contacts остался рабочим: по нему окно просто открывается.
  const [peopleOpen, setPeopleOpen] = useState(false);
  const [callsOpen, setCallsOpen] = useState(false);
  const peopleRoute = location.pathname === '/contacts';
  const callsRoute = location.pathname === '/calls';
  const profileRoute = /^\/user\/[^/]+$/.test(location.pathname);

  useEffect(() => {
    if (peopleRoute) setPeopleOpen(true);
    if (callsRoute) setCallsOpen(true);
  }, [peopleRoute, callsRoute]);

  const closePeople = useCallback(() => {
    setPeopleOpen(false);
    if (peopleRoute) navigate('/', { replace: true });
  }, [peopleRoute, navigate]);

  const closeCalls = useCallback(() => {
    setCallsOpen(false);
    if (callsRoute) navigate('/', { replace: true });
  }, [callsRoute, navigate]);
  // Правая часть занята, когда открыт разговор или раздел — люди,
  // звонки, профиль. На узком экране тогда виден только он, а список
  // уезжает за край, как и раньше при открытой переписке.
  const chatOpen = location.pathname !== '/' && !peopleRoute && !callsRoute && !profileRoute;

  // Страница целиком не прокручивается: прокрутка живёт внутри колонок.
  // Иначе у окна появляется вторая полоса справа и всё содержимое уезжает
  // вместе с шапкой из-за пары лишних пикселей высоты.
  useEffect(() => {
    document.body.classList.add('up-no-scroll');
    return () => document.body.classList.remove('up-no-scroll');
  }, []);

  return (
    <div className={`up-shell ${chatOpen ? 'up-shell-open' : ''}`}>
      <ChatList onOpenPeople={() => setPeopleOpen(true)} onOpenCalls={() => setCallsOpen(true)} />
      <main className="up-shell-content">
        {chatOpen ? (
          <Outlet />
        ) : (
          <div className="up-shell-placeholder">
            <p>Select a conversation to start</p>
          </div>
        )}
      </main>
      {peopleOpen && <People onClose={closePeople} />}
      {callsOpen && <Calls onClose={closeCalls} />}
      {profileRoute && <UserProfile onClose={() => navigate('/', { replace: true })} />}
    </div>
  );
};

export default ChatShell;
