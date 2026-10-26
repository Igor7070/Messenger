import React, { useCallback, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { loginUser } from '../api/messages';
import { setAuthToken } from '../authToken';
import { generateAndSaveKeys } from '../api/keys';
import { useUserStatusContext } from '../context/UserStatusContext';
import { useCallWebSocket } from '../context/CallWebSocketContext';
import { usePrivateMessageNotification } from '../context/PrivateMessageNotificationContext';
import '../styles/Auth.css';

/**
 * Вход и регистрация — отдельный экран.
 *
 * Раньше поля логина и кнопка создания пользователя висели в шапке списка
 * чатов и были видны даже тому, кто уже вошёл. Теперь как в любом мессенджере:
 * без аккаунта виден только этот экран, а вошедший его не видит вовсе —
 * сессия живёт 30 дней.
 *
 * Регистрация — вторая вкладка здесь же, а не окно поверх списка.
 */
const Auth = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { initializeWebSocket, sendOnlineStatus } = useUserStatusContext();
  const { setCurrentUserId: setCallUserId } = useCallWebSocket();
  const { setCurrentUserId: setNotificationUserId } = usePrivateMessageNotification();

  const [signUpMode, setSignUpMode] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [bio, setBio] = useState('');
  const [avatar, setAvatar] = useState(null);
  const [busy, setBusy] = useState(false);

  /** Куда человек шёл до того, как его остановил вход. */
  const destination = (location.state && location.state.from) || '/';

  const completeAuth = useCallback(async (user) => {
    const userId = Number(user.id);
    localStorage.setItem('userId', String(userId));
    localStorage.setItem('username', user.username);
    if (user.avatar) localStorage.setItem('userAvatar', user.avatar);
    else localStorage.removeItem('userAvatar');

    setCallUserId(userId);
    setNotificationUserId(userId);
    await generateAndSaveKeys(userId);
    initializeWebSocket(userId, user.username);
    sendOnlineStatus();
    navigate(destination, { replace: true });
  }, [destination, initializeWebSocket, navigate, sendOnlineStatus, setCallUserId, setNotificationUserId]);

  const signIn = async () => {
    if (!username.trim() || !password) {
      toast.error('Enter your username and password');
      return;
    }
    setBusy(true);
    try {
      const user = await loginUser({ username: username.trim(), password });
      await completeAuth(user);
      toast.success('Welcome back!');
    } catch (error) {
      toast.error(error.message || 'Wrong username or password');
    } finally {
      setBusy(false);
    }
  };

  /**
   * Регистрация отправляется формой, а не JSON: вместе с именем и паролем
   * может приехать фотография.
   */
  const signUp = async () => {
    if (!username.trim() || !password) {
      toast.error('Username and password are required');
      return;
    }
    setBusy(true);
    try {
      const form = new FormData();
      form.append('username', username.trim());
      form.append('password', password);
      form.append('bio', bio);
      if (avatar) form.append('avatar', avatar);

      const response = await fetch('https://unlimitedpossibilities12.org/api/users/create', {
        method: 'POST',
        body: form,
      });
      if (!response.ok) throw new Error(await response.text());

      const user = await response.json();
      if (user.token) setAuthToken(user.token);
      await completeAuth(user);
      toast.success('Your account is ready');
    } catch (error) {
      toast.error(error.message || 'Could not create the account');
    } finally {
      setBusy(false);
    }
  };

  const submit = (event) => {
    event.preventDefault();
    if (signUpMode) signUp(); else signIn();
  };

  return (
    <div className="auth-wrap">
      <form className="auth-card" onSubmit={submit}>
        <h2 className="auth-title">Messenger UP12</h2>
        <p className="auth-subtitle">
          {signUpMode ? 'Create an account to start chatting' : 'Sign in to continue'}
        </p>

        <div className="auth-tabs">
          <button
            type="button"
            className={`auth-tab ${signUpMode ? '' : 'auth-tab-active'}`}
            onClick={() => setSignUpMode(false)}
          >
            Sign in
          </button>
          <button
            type="button"
            className={`auth-tab ${signUpMode ? 'auth-tab-active' : ''}`}
            onClick={() => setSignUpMode(true)}
          >
            Sign up
          </button>
        </div>

        <input
          type="text"
          className="auth-input"
          placeholder="Username"
          autoComplete="username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />

        <div className="auth-password">
          <input
            type={showPassword ? 'text' : 'password'}
            className="auth-input"
            placeholder="Password"
            autoComplete={signUpMode ? 'new-password' : 'current-password'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <button
            type="button"
            className="auth-password-toggle"
            onClick={() => setShowPassword((shown) => !shown)}
            title={showPassword ? 'Hide password' : 'Show password'}
          >
            {showPassword ? '🙈' : '👁'}
          </button>
        </div>

        {signUpMode && (
          <>
            <textarea
              className="auth-input auth-textarea"
              placeholder="About me (optional)"
              rows={3}
              maxLength={244}
              value={bio}
              onChange={(e) => setBio(e.target.value)}
            />
            <label className="auth-file">
              <input type="file" accept="image/*" onChange={(e) => setAvatar(e.target.files[0])} />
              <span>{avatar ? avatar.name : 'Choose a photo (optional)'}</span>
            </label>
          </>
        )}

        <button type="submit" className="auth-submit" disabled={busy}>
          {busy ? 'Please wait…' : signUpMode ? 'Create account' : 'Sign in'}
        </button>

        <button type="button" className="auth-switch" onClick={() => setSignUpMode(!signUpMode)}>
          {signUpMode ? 'Already have an account? Sign in' : 'No account yet? Sign up'}
        </button>
      </form>
    </div>
  );
};

export default Auth;
