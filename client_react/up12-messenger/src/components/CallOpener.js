import { useEffect } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import { useCallWebSocket } from '../context/CallWebSocketContext';

/**
 * Переход на звонок больше не уводит со страницы.
 *
 * По тем же самым ссылкам — /voice-call/<комната> — приложение теперь не
 * показывает отдельный экран, а заводит разговор в общем хранилище. Окно
 * звонка рисует каркас поверх переписки, и уйти в другой чат, не оборвав
 * разговор, можно как в телеграме. Все прежние переходы остались на месте:
 * из чата, из карточки человека, из списка звонков.
 */
const CallOpener = ({ type = 'voice' }) => {
  const { roomId } = useParams();
  const location = useLocation();
  const { activeCall, openCall } = useCallWebSocket();

  useEffect(() => {
    if (!roomId) return;
    if (activeCall && activeCall.roomId === roomId) return;
    openCall({ type, roomId, ...(location.state || {}) });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roomId]);

  return null;
};

export default CallOpener;
