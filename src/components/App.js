import React from 'react';
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
import { ToastContainer } from 'react-toastify';
import '../styles/App.css';

const App = () => {
    return (
        <UserInteractionProvider>
            <NotificationProvider>
                <Router>
                    <div className="app">
                        {/* Контейнер для заголовка и ссылок */}
                        <div className="header-container">
                            {/* Ссылка слева */}
                            <a href="https://unlimitedpossibilities12.org" className="nav-link nav-link-main" rel="noopener noreferrer">
                                To Main
                            </a>
                            {/* Заголовок */}
                            <h1 className="app-title">Messenger UP12</h1>
                            {/* Ссылка справа */}
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
                </Router>
            </NotificationProvider>
        </UserInteractionProvider>
    );
};

export default App;