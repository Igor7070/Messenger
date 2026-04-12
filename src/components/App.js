import React, { useEffect } from 'react';
import { HashRouter as Router, Route, Routes, Link, useLocation } from 'react-router-dom';
import { ToastContainer } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import Home from './Home';
import Messenger from './Messenger';
import UserProfile from './UserProfile';
import PrivateChat from './PrivateChat';
import SecretPrivateChat from './SecretPrivateChat';
import VoiceCall from './VoiceCall';
import VoiceCallSecure from './VoiceCallSecure';
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
    }
  }, [initializeWebSocket, location.pathname]);

  return (
    <>
      {toastPortal && createPortal(
        <ToastContainer
          position="top-right"
          autoClose={5000}
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

      <div className="app">
        <div className="header-container">
          <a href="https://unlimitedpossibilities12.org" className="nav-link nav-link-main" rel="noopener noreferrer">
            To Main
          </a>

          <h1 className="app-title">Messenger UP12</h1>

          <div
            onClick={() => window.location.reload()}
            className="refresh-icon"
            title="Обновить"
          >
            ⟳
          </div>

          <Link to="/" className="nav-link nav-link-home">
            To Home
          </Link>
        </div>

        <ErrorBoundary>
          <PrivateMessageNotificationProvider>
            <Routes>
              <Route path="/" element={<Home />} />
              <Route path="/chat/:chatId" element={<Messenger />} />
              <Route path="/user/:userId" element={<UserProfile />} />
              <Route path="/private-chat/:chatId" element={<PrivateChat />} />
              <Route path="/secret-chat/:chatId" element={<SecretPrivateChat />} />
              <Route path="/voice-call/:roomId" element={<VoiceCall />} />
              <Route path="/secure-call/:roomId" element={<VoiceCallSecure />} />
              <Route path="/video-call/:roomId" element={<VideoCall />} />
              <Route path="/user/:userId/personal-chats" element={<PersonalChats />} />
              <Route path="/voice-call-translate/:roomId" element={<VoiceCallWithTranslate />} />
              <Route path="/calls" element={<Calls />} />
              <Route path="/group-call" element={<GroupCallActivity />} />
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
              <Router>
                <AppContent />
              </Router>
            </AssistantProvider>
          </CallWebSocketProvider>
        </UserStatusProvider>
      </NotificationProvider>
    </UserInteractionProvider>
  );
};

export default App;