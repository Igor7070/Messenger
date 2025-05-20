import React, { useEffect } from 'react';
import { HashRouter as Router, Route, Routes, Link } from 'react-router-dom';
import Home from './Home';
import Messenger from './Messenger';
import UserProfile from './UserProfile';
import PrivateChat from './PrivateChat';
import VoiceCall from './VoiceCall';
import VideoCall from './VideoCall';
import PersonalChats from './PersonalChats';
import { NotificationProvider } from './NotificationContext';
import { UserInteractionProvider } from './UserInteractionContext';
import { UserStatusProvider, useUserStatusContext } from '../context/UserStatusContext';
import { CallWebSocketProvider } from '../context/CallWebSocketContext';
import { PrivateMessageNotificationProvider } from '../context/PrivateMessageNotificationContext';
import { ToastContainer } from 'react-toastify';
import '../styles/App.css';

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
                <a href="https://unlimitedpossibilities12.org" className="nav-link nav-link-main" rel="noopener noreferrer">
                    To Main
                </a>
                <h1 className="app-title">Messenger UP12</h1>
                <Link to="/" className="nav-link nav-link-home">
                    To Home
                </Link>
            </div>
            <ToastContainer />
            <Routes>
                <Route path="/" element={<Home />} />
                <Route path="/chat/:chatId" element={<Messenger />} />
                <Route path="/user/:userId" element={<UserProfile />} />
                <Route path="/private-chat/:chatId" element={<PrivateChat />} />
                <Route path="/voice-call/:roomId" element={<VoiceCall />} />
                <Route path="/video-call/:roomId" element={<VideoCall />} />
                <Route path="/user/:userId/personal-chats" element={<PersonalChats />} />
            </Routes>
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