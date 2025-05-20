import React, { useEffect } from 'react';
import { HashRouter as Router, Route, Routes, Link } from 'react-router-dom';
import { ToastContainer } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css'; // Импорт стилей для react-toastify
import Home from './Home';
import Messenger from './Messenger';
import UserProfile from './UserProfile';
import PrivateChat from './PrivateChat';
import VoiceCall from './VoiceCall';
import VideoCall from './VideoCall';
import PersonalChats from './PersonalChats';
import { NotificationProvider } from '../context/NotificationContext';
import { UserInteractionProvider } from '../context/UserInteractionContext';
import { UserStatusProvider, useUserStatusContext } from '../context/UserStatusContext';
import { CallWebSocketProvider } from '../context/CallWebSocketContext';
import { PrivateMessageNotificationProvider } from '../context/PrivateMessageNotificationContext';
import '../styles/App.css';

// Компонент ErrorBoundary для обработки ошибок рендеринга
class ErrorBoundary extends React.Component {
  state = { hasError: false };

  static getDerivedStateFromError(error) {
    return { hasError: true };
  }

  componentDidCatch(error, errorInfo) {
    console.error('ErrorBoundary caught:', error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      return <div>Произошла ошибка. Пожалуйста, перезагрузите страницу.</div>;
    }
    return this.props.children;
  }
}

const AppContent = () => {
  const { initializeWebSocket } = useUserStatusContext();

  useEffect(() => {
    const userId = localStorage.getItem('userId');
    const username = localStorage.getItem('username');
    if (userId && username) {
      initializeWebSocket(Number(userId), username);
    }
  }, [initializeWebSocket]);

  return (
    <div className="app">
      <div className="header-container">
        <a
          href="https://unlimitedpossibilities12.org"
          className="nav-link nav-link-main"
          rel="noopener noreferrer"
        >
          To Main
        </a>
        <h1 className="app-title">Messenger UP12</h1>
        <Link to="/" className="nav-link nav-link-home">
          To Home
        </Link>
      </div>
      <ToastContainer
        position="top-right"
        autoClose={5000}
        hideProgressBar={false}
        newestOnTop={false}
        closeOnClick
        rtl={false}
        pauseOnFocusLoss
        draggable
        pauseOnHover
      />
      <ErrorBoundary>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/chat/:chatId" element={<Messenger />} />
          <Route path="/user/:userId" element={<UserProfile />} />
          <Route path="/private-chat/:chatId" element={<PrivateChat />} />
          <Route path="/voice-call/:roomId" element={<VoiceCall />} />
          <Route path="/video-call/:roomId" element={<VideoCall />} />
          <Route path="/user/:userId/personal-chats" element={<PersonalChats />} />
        </Routes>
      </ErrorBoundary>
    </div>
  );
};

const App = () => {
  return (
    <UserInteractionProvider>
      <NotificationProvider>
        <UserStatusProvider>
          <CallWebSocketProvider>
            <PrivateMessageNotificationProvider>
              <Router>
                <AppContent />
              </Router>
            </PrivateMessageNotificationProvider>
          </CallWebSocketProvider>
        </UserStatusProvider>
      </NotificationProvider>
    </UserInteractionProvider>
  );
};

export default App;