import React from 'react';
import { useNavigate } from 'react-router-dom';
import '../styles/SharedView.css';

/**
 * Что накоплено в переписке — списком внутри той же карточки.
 *
 * В телеграме счёт под именем открывает отдельный вид с заголовком и
 * стрелкой назад: снимки сеткой, ссылки и файлы строками. Здесь так же —
 * карточка не закрывается, человек возвращается стрелкой к ней же.
 */

const dayLabel = (timestamp) => {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '';
  const today = new Date();
  const sameYear = date.getFullYear() === today.getFullYear();
  return date.toLocaleDateString('en-GB', sameYear
    ? { day: 'numeric', month: 'long' }
    : { day: 'numeric', month: 'long', year: 'numeric' });
};

/** Адрес без «http://» и длинного хвоста — ссылка в строку не влезает */
const shortLink = (href) => {
  try {
    const parsed = new URL(href);
    const tail = parsed.pathname.length > 1 || parsed.search || parsed.hash;
    return parsed.host.replace(/^www\./, '') + (tail ? '/…' : '');
  } catch {
    return href;
  }
};

const TITLES = {
  photos: 'Photos',
  videos: 'Videos',
  files: 'Files',
  links: 'Links',
  voices: 'Voice messages',
  groups: 'Groups in common',
};

const SharedView = ({ kind, items, onBack, onClose }) => {
  const navigate = useNavigate();

  return (
    <>
      <header className="up-shared-head">
        <button type="button" className="up-profile-icon" title="Back" onClick={onBack}>
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 5l-7 7 7 7" />
          </svg>
        </button>
        <h3 className="up-shared-title">{TITLES[kind]}</h3>
        <button type="button" className="up-profile-icon" title="Close" onClick={onClose}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </header>

      <div className="up-shared-body">
        {items.length === 0 && <p className="up-shared-empty">Nothing here</p>}

        {/* Снимки и видео — сеткой, как в телеграме */}
        {(kind === 'photos' || kind === 'videos') && (
          <div className="up-shared-grid">
            {items.map((item) => (
              <a
                key={item.id}
                className="up-shared-tile"
                href={item.url}
                target="_blank"
                rel="noreferrer"
                title={item.caption || dayLabel(item.timestamp)}
              >
                {kind === 'photos'
                  ? <img src={item.url} alt="" loading="lazy" />
                  : <video src={item.url} preload="metadata" muted />}
              </a>
            ))}
          </div>
        )}

        {/* Файлы и голосовые — строками */}
        {(kind === 'files' || kind === 'voices') && (
          <ul className="up-shared-list">
            {items.map((item) => (
              <li key={item.id}>
                {kind === 'voices' ? (
                  <div className="up-shared-row">
                    <audio src={item.url} controls preload="none" />
                    <span className="up-shared-when">{dayLabel(item.timestamp)}</span>
                  </div>
                ) : (
                  <a className="up-shared-row" href={item.url} target="_blank" rel="noreferrer" download>
                    <span className="up-shared-name">{item.name}</span>
                    <span className="up-shared-when">{dayLabel(item.timestamp)}</span>
                  </a>
                )}
              </li>
            ))}
          </ul>
        )}

        {kind === 'links' && (
          <ul className="up-shared-list">
            {items.map((item) => (
              <li key={item.id}>
                <a className="up-shared-row" href={item.href} target="_blank" rel="noreferrer">
                  <span className="up-shared-name up-shared-link">{shortLink(item.href)}</span>
                  <span className="up-shared-when">{dayLabel(item.timestamp)}</span>
                </a>
              </li>
            ))}
          </ul>
        )}

        {kind === 'groups' && (
          <ul className="up-shared-list">
            {items.map((chat) => (
              <li key={chat.id}>
                <button
                  type="button"
                  className="up-shared-row"
                  onClick={() => { onClose(); navigate(`/chat/${chat.id}`); }}
                >
                  <span className="up-shared-name">{chat.name}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
};

export default SharedView;
