import React from 'react';
import { BrowserRouter as Router, Route, Switch } from 'react-router-dom';
import Home from './Home'; // Компонент главной страницы
import Messenger from './Messenger'; // Компонент группового чата
import '../styles/App.css';

const App = () => {
    return (
        <Router>
            <div className="app">
                <h1>Messenger UP12</h1>
                <Switch>
                    <Route path="/" exact component={Home} /> {/* Главная страница */}
                    <Route path="/chat/:chatId" component={Messenger} /> {/* Групповой чат */}
                </Switch>
            </div>
        </Router>
    );
};

export default App;
