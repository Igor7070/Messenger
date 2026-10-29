import React, { useLayoutEffect, useRef, useState } from 'react';
import { Router } from 'react-router-dom';
import { createHashHistory } from 'history';

/**
 * Маршрутизация по адресу вида #/chat/7, применяемая немедленно.
 *
 * Штатный HashRouter помечает смену адреса необязательным обновлением
 * (startTransition). React вправе отложить такое обновление, пока занят
 * срочными, и в тяжёлой переписке — лента с картинками и превью ссылок,
 * рядом отметки о прочтении — оно могло не примениться вовсе: в строке
 * браузера новый адрес, а на экране прежний чат. Человек видел, что нажатие
 * на другой чат или на свой профиль ничего не делает, и помогала только
 * перезагрузка страницы.
 *
 * Здесь то же самое, что делает библиотека, но состояние обновляется обычным
 * способом, поэтому переход виден сразу. Сам разбор адреса и маршруты
 * остаются за React Router: мы отдаём ему историю через его же публичный
 * низкоуровневый Router.
 */
const SyncHashRouter = ({ children }) => {
  const historyRef = useRef(null);
  if (historyRef.current === null) {
    historyRef.current = createHashHistory({ window });
  }
  const history = historyRef.current;

  const [state, setState] = useState({ action: history.action, location: history.location });

  useLayoutEffect(() => history.listen(setState), [history]);

  return (
    <Router location={state.location} navigationType={state.action} navigator={history}>
      {children}
    </Router>
  );
};

export default SyncHashRouter;
