/**
 * Системные уведомления браузера.
 *
 * Внутри страницы сообщения и звонки показываются всплывающими окошками, но
 * их не видно, когда пользователь работает в другом окне — а звонок живёт
 * полминуты. Поэтому при неактивной вкладке дублируем событие уведомлением
 * операционной системы; когда вкладка на виду, ничего не показываем, чтобы
 * одно и то же не появлялось дважды.
 */

const ICON = `${process.env.PUBLIC_URL || ''}/logo192.png`;

/** Вкладка не на переднем плане — пользователь событие не увидит. */
const pageHidden = () => document.visibilityState !== 'visible' || !document.hasFocus();

export const canNotify = () => typeof Notification !== 'undefined';

/**
 * Спросить разрешение. Браузеры требуют, чтобы это происходило в ответ на
 * действие пользователя, поэтому вызываем из клика по странице.
 */
export const requestPermission = async () => {
  if (!canNotify() || Notification.permission !== 'default') return Notification?.permission;
  try {
    return await Notification.requestPermission();
  } catch {
    return 'denied';
  }
};

/**
 * Показать уведомление, если вкладка не активна.
 * @param {string} title заголовок
 * @param {object} options body, tag, requireInteraction, onClick
 * @returns {Notification|null}
 */
export const notify = (title, { body, tag, requireInteraction = false, onClick } = {}) => {
  if (!canNotify() || Notification.permission !== 'granted') return null;
  if (!pageHidden()) return null; // вкладка на виду — хватит и toast
  try {
    const notification = new Notification(title, {
      body,
      icon: ICON,
      tag,                       // одинаковый tag заменяет предыдущее, а не копит
      requireInteraction,        // для звонка — висит, пока не ответят
      silent: false,
    });
    notification.onclick = () => {
      window.focus();
      notification.close();
      if (onClick) onClick();
    };
    return notification;
  } catch {
    return null;
  }
};

/** Закрыть уведомление о звонке, когда он завершился или отменён. */
export const closeNotification = (notification) => {
  try {
    if (notification) notification.close();
  } catch {
    /* окно уже закрыто */
  }
};
