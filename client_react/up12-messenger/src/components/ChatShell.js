import React, { useEffect } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import ChatList from './ChatList';
import '../styles/tokens.css';
import '../styles/ChatShell.css';

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
  const chatOpen = location.pathname !== '/';

  // Страница целиком не прокручивается: прокрутка живёт внутри колонок.
  // Иначе у окна появляется вторая полоса справа и всё содержимое уезжает
  // вместе с шапкой из-за пары лишних пикселей высоты.
  useEffect(() => {
    document.body.classList.add('up-no-scroll');
    return () => document.body.classList.remove('up-no-scroll');
  }, []);

  return (
    <div className={`up-shell ${chatOpen ? 'up-shell-open' : ''}`}>
      <ChatList />
      <main className="up-shell-content">
        {chatOpen ? (
          <Outlet />
        ) : (
          <div className="up-shell-placeholder">
            <p>Select a conversation to start</p>
          </div>
        )}
      </main>
    </div>
  );
};

export default ChatShell;
