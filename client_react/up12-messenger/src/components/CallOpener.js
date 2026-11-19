import { useEffect } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
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
  const navigate = useNavigate();
  const { activeCall, openCall } = useCallWebSocket();

  useEffect(() => {
    if (!roomId) return;
    if (activeCall && activeCall.roomId === roomId) return;
    // Без сведений о звонке заводить нечего: они передаются переходом и
    // перезагрузку не переживают. Прежде на этом месте открывалось окно
    // давно законченного разговора — и так при каждой перезагрузке, пока
    // адрес оставался прежним.
    if (!location.state) {
      navigate('/', { replace: true });
      return;
    }
    openCall({ type, roomId, ...location.state });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roomId]);

  return null;
};

export default CallOpener;
