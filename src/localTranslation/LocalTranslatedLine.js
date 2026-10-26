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
  // Подпись «translated on device» раньше стояла у каждого сообщения: она
  // длиннее самого перевода, переносилась на вторую строку и растягивала
  // пузыри вразнобой. То, что перевод включён, и так видно по метке в шапке,
  // поэтому здесь — маленький значок с подсказкой при наведении.
  return (
    <div className="secret-local-translation" title="Translated on your device">
      <svg
        className="secret-local-translation-mark"
        width="12"
        height="12"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        aria-hidden="true"
      >
        <path d="M4 6h9" />
        <path d="M8.5 6v2c0 3-2 5.5-4.5 6.5" />
        <path d="M6 10.5c1 2.5 3 4 5.5 4.5" />
        <path d="M12.5 20l4-10 4 10" />
        <path d="M14 17h5" />
      </svg>
      <span>{line}</span>
    </div>
  );
};

export const clearLocalTranslationCache = () => cache.clear();
export default LocalTranslatedLine;
