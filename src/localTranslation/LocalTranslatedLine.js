import React, { useEffect, useState } from 'react';
import { isEnabled, targetLanguage, translateToIfNeeded, directionsForTarget, isDirectionLoaded } from './localTranslator';

const cache = new Map(); // `${msgId}|${target}` -> translated | ''

/**
 * Строка перевода под сообщением секретного чата.
 * Правило: готовый перевод из сообщения, если он на нужном языке; иначе
 * переводим сами (если модели загружены); иначе ничего.
 */
const LocalTranslatedLine = ({ chatId, msgId, text, translated, lang, refreshKey }) => {
  const [line, setLine] = useState(null);

  useEffect(() => {
    let alive = true;
    setLine(null);
    if (!isEnabled(chatId)) return undefined;
    const target = targetLanguage(chatId);
    if (!target || !text) return undefined;

    if (translated && lang === target) { setLine(translated); return undefined; }

    const key = `${msgId}|${target}`;
    if (cache.has(key)) { const c = cache.get(key); if (c) setLine(c); return undefined; }

    if (!directionsForTarget(target).every(isDirectionLoaded)) return undefined;

    translateToIfNeeded(text, target)
      .then((r) => { cache.set(key, r || ''); if (alive && r) setLine(r); })
      .catch(() => { cache.set(key, ''); });
    return () => { alive = false; };
  }, [chatId, msgId, text, translated, lang, refreshKey]);

  if (!line) return null;
  return (
    <div className="secret-local-translation">
      {line} <span className="secret-local-translation-badge">· translated on device</span>
    </div>
  );
};

export const clearLocalTranslationCache = () => cache.clear();
export default LocalTranslatedLine;
