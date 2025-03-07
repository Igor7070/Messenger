import React from 'react';
import { HashRouter as Router, Route, Routes } from 'react-router-dom';
import Home from './Home'; // Компонент главной страницы
import Messenger from './Messenger'; // Компонент группового чата
import UserProfile from './UserProfile'; // Компонент профиля пользователя
import PrivateChat from './PrivateChat'; // Импорт нового компонента
import VoiceCall from './VoiceCall'; // Импорт компонента для видеозвонка
import PersonalChats from './PersonalChats';
import { NotificationProvider } from './NotificationContext'; // Импортируем контекст уведомлений
import { UserInteractionProvider } from './UserInteractionContext';
import { ToastContainer } from 'react-toastify'; // Импортируем ToastContainer
import '../styles/App.css';

const App = () => {
    return (
        <UserInteractionProvider>
            <NotificationProvider>
                <Router>
                    <div className="app">
                        <h1>Messenger UP12</h1>
                        <ToastContainer /> {/* Размещаем ToastContainer для уведомлений */}
                        <Routes>
                            <Route path="/" element={<Home />} /> {/* Главная страница */}
                            <Route path="/chat/:chatId" element={<Messenger />} /> {/* Групповой чат */}
                            <Route path="/user/:userId" element={<UserProfile />} /> {/* Профиль пользователя */}
                            <Route path="/private-chat/:chatId" element={<PrivateChat />} /> {/* Личный чат */}
                            <Route path="/voice-call/:roomId" element={<VoiceCall />} /> {/* Видеозвонок */}
                            <Route path="/user/:userId/personal-chats" element={<PersonalChats />} /> {/* Личные чаты */}
                        </Routes>
                    </div>
                </Router>
            </NotificationProvider>
        </UserInteractionProvider>
    );
};

export default App;