import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './components/App';
import reportWebVitals from './reportWebVitals';
import axios from 'axios';
import { installFetchAuth, applyAxiosAuth } from './authToken';
import { installRemoteLog } from './remoteLog';

// Токен доступа к API подставляется во все запросы к бэкенду
installFetchAuth();
applyAxiosAuth(axios);
// Ошибки и предупреждения консоли уходят в лог бэкенда (см. remoteLog.js)
installRemoteLog();

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

// If you want to start measuring performance in your app, pass a function
// to log results (for example: reportWebVitals(console.log))
// or send to an analytics endpoint. Learn more: https://bit.ly/CRA-vitals
reportWebVitals();
