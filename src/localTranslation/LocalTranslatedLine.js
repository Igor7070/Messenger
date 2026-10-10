import React, { useEffect, useState } from 'react';
import {
  isEnabled, targetLanguage, translateToIfNeeded,
  detectLanguage, isPairLoaded, isPairCached,
} from './localTranslator';

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

    // Нужна модель только для пары «язык этого сообщения → мой», а не для всех
    // языков сразу: иначе одна не скачанная пара глушила перевод остальных
    const src = detectLanguage(text);
    if (!src || src === target) return undefined;

    (async () => {
      if (!isPairLoaded(src, target) && !(await isPairCached(src, target))) return;
      try {
        const r = await translateToIfNeeded(text, target);
        cache.set(key, r || '');
        if (alive && r) setLine(r);
      } catch {
        cache.set(key, '');
      }
    })();
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
