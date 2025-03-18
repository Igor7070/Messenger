import React from 'react';
import { HashRouter as Router, Route, Routes, Link } from 'react-router-dom';
import Home from './Home';
import Messenger from './Messenger';
import UserProfile from './UserProfile';
import PrivateChat from './PrivateChat';
import VoiceCall from './VoiceCall';
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
                            <Link to="/" className="nav-link nav-link-home">
                                To Home
                            </Link>
                            {/* Заголовок */}
                            <h1 className="app-title">Messenger UP12</h1>
                            {/* Ссылка справа */}
                            <a href="https://unlimitedpossibilities12.org" className="nav-link nav-link-main" target="_blank" rel="noopener noreferrer">
                                To Main
                            </a>
                        </div>
                        <ToastContainer />
                        <Routes>
                            <Route path="/" element={<Home />} />
                            <Route path="/chat/:chatId" element={<Messenger />} />
                            <Route path="/user/:userId" element={<UserProfile />} />
                            <Route path="/private-chat/:chatId" element={<PrivateChat />} />
                            <Route path="/voice-call/:roomId" element={<VoiceCall />} />
                            <Route path="/user/:userId/personal-chats" element={<PersonalChats />} />
                        </Routes>
                    </div>
                </Router>
            </NotificationProvider>
        </UserInteractionProvider>
    );
};

export default App;