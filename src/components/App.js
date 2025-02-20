import React from 'react';
import { HashRouter as Router, Route, Routes } from 'react-router-dom';
import Home from './Home'; // Компонент главной страницы
import Messenger from './Messenger'; // Компонент группового чата
import UserProfile from './UserProfile'; // Компонент профиля пользователя
import PrivateChat from './PrivateChat'; // Импорт нового компонента
import VoiceCall from './VoiceCall'; // Импорт компонента для видеозвонка
import { NotificationProvider } from './NotificationContext'; // Импортируем контекст уведомлений
import { ToastContainer } from 'react-toastify'; // Импортируем ToastContainer
import '../styles/App.css';

const App = () => {
    return (
        <UserInteractionProvider>
            <NotificationProvider> {/* Оборачиваем Router в NotificationProvider */}
                <Router>
                    <div className="app">
                        <h1>Messenger UP12</h1>
                        <ToastContainer /> {/* Размещаем ToastContainer для уведомлений */}
                        <Routes>
                            <Route path="/" element={<Home />} /> {/* Главная страница */}
                            <Route path="/chat/:chatId" element={<Messenger />} /> {/* Групповой чат */}
                            <Route path="/user/:userId" element={<UserProfile />} /> {/* Профиль пользователя */}
                            <Route path="/private-chat/:chatId" element={<PrivateChat />} /> {/* Новый маршрут для личного чата */}
                            <Route path="/voice-call/:roomId" element={<VoiceCall />} /> {/* Новый маршрут для видеозвонка */}
                        </Routes>
                    </div>
                </Router>
            </NotificationProvider>
        </UserInteractionProvider>
    );
};

export default App;