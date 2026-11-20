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
  // У группового звонка номера комнаты в адресе нет: его заводит сам
  // звонящий, а приглашённым он приходит сигналом
  const key = roomId || (location.state && location.state.roomId) || type;
  const navigate = useNavigate();
  const { activeCall, openCall } = useCallWebSocket();

  useEffect(() => {
    if (activeCall && activeCall.key === key) return;
    // Без сведений о звонке заводить нечего: они передаются переходом и
    // перезагрузку не переживают. Прежде на этом месте открывалось окно
    // давно законченного разговора — и так при каждой перезагрузке, пока
    // адрес оставался прежним.
    if (!location.state) {
      navigate('/', { replace: true });
      return;
    }
    openCall({ type, key, roomId, ...location.state });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return null;
};

export default CallOpener;
