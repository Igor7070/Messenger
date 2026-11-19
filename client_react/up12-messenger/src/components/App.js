import React, { useEffect } from 'react';
import { Route, Routes, Link, Navigate, useLocation, useParams } from 'react-router-dom';
import SyncHashRouter from './SyncHashRouter';
import { ToastContainer } from 'react-toastify';
import { publishCallKey } from '../utils/callGuard';
import 'react-toastify/dist/ReactToastify.css';
import Home from './Home';
import ChatShell from './ChatShell';
import Auth from './Auth';
import InviteAccept from './InviteAccept';
import Messenger from './Messenger';
import UserProfile from './UserProfile';
import Settings from './Settings';
import PrivateChat from './PrivateChat';
import SecretPrivateChat from './SecretPrivateChat';
import VoiceCall from './VoiceCall';
import VideoCall from './VideoCall';
import VoiceCallWithTranslate from './VoiceCallWithTranslate';
import PersonalChats from './PersonalChats';
import Calls from './Calls';
import GroupCallActivity from './GroupCallActivity';
import { NotificationProvider } from '../context/NotificationContext';
import { UserInteractionProvider } from '../context/UserInteractionContext';
import { UserStatusProvider, useUserStatusContext } from '../context/UserStatusContext';
import { CallWebSocketProvider } from '../context/CallWebSocketContext';
import { PrivateMessageNotificationProvider } from '../context/PrivateMessageNotificationContext';
import { AssistantProvider } from '../context/AssistantContext';
import { useAssistant } from '../context/AssistantContext';
import AssistantChatWindow from './AssistantChatWindow';
import { createPortal } from 'react-dom';
import BUILD_STAMP from '../buildStamp';
import '../styles/tokens.css'; // палитра и общий вид всплывающих сообщений
import '../styles/App.css';

class ErrorBoundary extends React.Component {
  state = { hasError: false };

  static getDerivedStateFromError(error) {
    return { hasError: true };
  }

  componentDidCatch(error, errorInfo) {
    console.error('ErrorBoundary caught:', error, errorInfo);
    console.log('Error details:', error.message, error.stack);
  }

  render() {
    if (this.state.hasError) {
      return <div>Something went wrong. Please reload the page.</div>;
    }
    return this.props.children;
  }
}

/**
 * Страж: без аккаунта внутрь не пускаем.
 *
 * Сервер всё равно отвечает 401, и прежде человек видел наполовину живые
 * экраны с ошибкой вместо понятного «войдите». Куда он шёл, запоминаем и
 * возвращаем туда сразу после входа — это нужно и для ссылок-приглашений.
 */
const RequireAuth = ({ children }) => {
  const location = useLocation();
  let signedIn = false;
  try { signedIn = Boolean(localStorage.getItem('userId')); } catch { /* приватный режим */ }
  if (!signedIn) {
    return <Navigate to="/auth" replace state={{ from: location.pathname + location.search }} />;
  }
  return children;
};

/**
 * Переписка начинается с чистого листа при каждом переходе.
 *
 * Все три экрана чата живут на одном месте каркаса, поэтому React переиспользовал
 * один и тот же компонент и менял ему только номер чата. Пока новые сообщения
 * грузились, на экране оставались сообщения прежнего чата — со стороны это
 * выглядело так, будто нажатие в списке не сработало вовсе. Свой key на номер
 * чата заставляет React собрать экран заново: старое содержимое исчезает сразу.
 */
const KeyedChat = ({ element: Element }) => {
  const { chatId } = useParams();
  return <Element key={chatId} />;
};

const AppContent = () => {
  const { initializeWebSocket } = useUserStatusContext();
  const location = useLocation(); // Для получения текущего маршрута
  const { isOpen } = useAssistant();
  const toastPortal = typeof document !== 'undefined' ? document.body : null;

  useEffect(() => {
    console.log('Rendering AppContent, location:', location.pathname);
    const userId = localStorage.getItem('userId');
    const username = localStorage.getItem('username');
    if (userId && username) {
      initializeWebSocket(Number(userId), username);
      // Ключ этого устройства должен лежать на сервере до первого звонка,
      // иначе собеседник спросит список раньше, чем мы в нём появимся
      publishCallKey(userId);
    }
  }, [initializeWebSocket, location.pathname]);

  return (
    <>
      {toastPortal && createPortal(
        <ToastContainer
          position="top-right"
          theme="dark"
          autoClose={4000}
          hideProgressBar={false}
          newestOnTop={true}
          closeOnClick={false}
          rtl={false}
          pauseOnFocusLoss
          draggable
          pauseOnHover
          limit={3}
          enableMultiContainer={false}
          toastStyle={{ zIndex: 9999 }}
        />,
        toastPortal
      )}

      {/*
        Мессенджер занимает окно целиком — везде, кроме экрана входа.
        Прежде так выглядела только переписка, а люди, звонки и профиль
        открывались поверх фотообоев, с кнопками «To Main» и «To Home»:
        человек, перешедший туда из переписки, думал, что попал в другую
        программу.
      */}
      <div className={`app ${location.pathname === '/auth' ? '' : 'app-messenger'}`}>
        <div className="header-container">
          <a href="https://unlimitedpossibilities12.org" className="nav-link nav-link-main" rel="noopener noreferrer">
            To Main
          </a>

          <h1 className="app-title">Messenger UP12</h1>

          <div
            onClick={() => window.location.reload()}
            className="refresh-icon"
            title="Refresh"
          >
            ⟳
          </div>

          {/* На экране входа этой ссылки нет: главная всё равно вернёт сюда же */}
          {location.pathname !== '/auth' && (
            <Link to="/" className="nav-link nav-link-home">
              To Home
            </Link>
          )}
        </div>

        <ErrorBoundary>
          <PrivateMessageNotificationProvider>
            <Routes>
              {/* Экран входа и переход по ссылке-приглашению открыты всем:
                  приглашение само отправит ко входу и вернёт обратно */}
              <Route path="/auth" element={<Auth />} />
              <Route path="/invite/:token" element={<InviteAccept />} />

              {/* Каркас: список слева остаётся на месте, меняется переписка справа */}
              <Route element={<RequireAuth><ChatShell /></RequireAuth>}>
                <Route path="/" element={null} />
                <Route path="/chat/:chatId" element={<KeyedChat element={Messenger} />} />
                <Route path="/private-chat/:chatId" element={<KeyedChat element={PrivateChat} />} />
                <Route path="/secret-chat/:chatId" element={<KeyedChat element={SecretPrivateChat} />} />
                {/* Люди, звонки и профиль — тоже внутри каркаса: список
                    разговоров остаётся слева, меняется только правая часть */}
                <Route path="/contacts" element={<Home />} />
                <Route path="/calls" element={<Calls />} />
                <Route path="/user/:userId" element={<UserProfile />} />
                <Route path="/settings" element={<Settings />} />
              </Route>
              <Route path="/voice-call/:roomId" element={<RequireAuth><VoiceCall /></RequireAuth>} />
              <Route path="/video-call/:roomId" element={<RequireAuth><VideoCall /></RequireAuth>} />
              {/* Прежний отдельный список личных чатов больше не нужен:
                  все разговоры живут в колонке слева */}
              <Route path="/user/:userId/personal-chats" element={<Navigate to="/" replace />} />
              <Route path="/voice-call-translate/:roomId" element={<RequireAuth><VoiceCallWithTranslate /></RequireAuth>} />
              <Route path="/group-call" element={<RequireAuth><GroupCallActivity /></RequireAuth>} />
              {/* Неизвестный адрес — старая закладка, опечатка в ссылке —
                  прежде оставлял чёрный экран без единой подсказки.
                  Отправляем к списку разговоров. */}
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </PrivateMessageNotificationProvider>
        </ErrorBoundary>
      </div>

      {/* ЗДЕСЬ ДОБАВЛЯЕМ ГЛОБАЛЬНОЕ ОКНО АССИСТЕНТА */}
      {isOpen && <AssistantChatWindow />}

    </>
  );
};

const App = () => {
  return (
    <UserInteractionProvider>
      <NotificationProvider>
        <UserStatusProvider>
          <CallWebSocketProvider>
            <AssistantProvider>
              <SyncHashRouter>
                <AppContent />
              </SyncHashRouter>
            </AssistantProvider>
          </CallWebSocketProvider>
        </UserStatusProvider>
      </NotificationProvider>
    </UserInteractionProvider>
  );
};

export default App;