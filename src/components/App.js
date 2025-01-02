import React from 'react';
import { HashRouter as Router, Route, Routes } from 'react-router-dom';
import Home from './Home'; // Компонент главной страницы
import Messenger from './Messenger'; // Компонент группового чата
import '../styles/App.css';

const App = () => {
    return (
        <Router>
            <div className="app">
                <h1>Messenger UP12</h1>
                <Routes>
                    <Route path="/" element={<Home />} /> {/* Главная страница */}
                    <Route path="/chat/:chatId" element={<Messenger />} /> {/* Групповой чат */}
                </Routes>
            </div>
        </Router>
    );
};

export default App;
