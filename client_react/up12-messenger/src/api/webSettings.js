/**
 * Настройки веб-клиента.
 *
 * Хранятся у этого браузера: это его дело, звенеть ли ему и показывать ли
 * текст сообщения в уведомлении. На телефоне такой же выбор живёт в
 * настройках приложения и уезжает на сервер вместе с push-токеном — там
 * уведомление приходит и при закрытом приложении, здесь же окно должно быть
 * открыто, и решать всё можно на месте.
 */
const KEY = 'up_web_settings';

const read = () => {
  try {
    return JSON.parse(localStorage.getItem(KEY) || '{}');
  } catch {
    return {}; // приватный режим или испорченная запись
  }
};

const write = (values) => {
  try {
    localStorage.setItem(KEY, JSON.stringify(values));
  } catch {
    /* ничего не поделать — настройка проживёт до перезагрузки */
  }
};

export const webSettings = {
  notificationSound: () => read().notificationSound !== false,
  notificationPreview: () => read().notificationPreview !== false,
};

export const setWebSetting = (name, value) => {
  const values = read();
  values[name] = value;
  write(values);
};
