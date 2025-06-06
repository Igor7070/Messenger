import { useState, useEffect } from 'react';
import { translateMessage, saveTranslation } from '../api/messages';
import { toast } from 'react-toastify';

// Хук для управления переводом и орфографией (новый файл, весь код добавлен)
const useTranslationAndSpellCheck = ({
  chatId,
  messages,
  setMessages,
  isTranslationEnabled,
  setIsTranslationEnabled,
  translationLanguage,
  setTranslationLanguage,
  translateNewOnly,
  setTranslateNewOnly,
}) => {
  const [translationQueue, setTranslationQueue] = useState([]);
  const [isTranslating, setIsTranslating] = useState(false);
  const [isSpellCheckEnabled, setIsSpellCheckEnabled] = useState(
    localStorage.getItem(`spellcheck_enabled_${chatId}`) === 'true'
  );

  // Проверка, является ли сообщение новым (перенесено из Messenger без изменений)
  const isMessageNew = (message) => {
    if (!translateNewOnly) return true;
    const enabledTimestamp = localStorage.getItem(`translation_enabled_timestamp_${chatId}`);
    if (!enabledTimestamp) return false;
    try {
      const messageTime = new Date(message.timestamp).getTime();
      const enabledTime = new Date(enabledTimestamp).getTime();
      return messageTime > enabledTime;
    } catch (e) {
      console.error('Error parsing timestamps:', e);
      return false;
    }
  };

  // Перевод существующих сообщений (перенесено из Messenger без изменений)
  const translateExistingMessages = () => {
    if (!isTranslationEnabled) return;
    const queue = messages
      .filter(
        (msg) =>
          isMessageNew(msg) &&
          (!msg.translatedContent || msg.translationLanguage !== translationLanguage)
      )
      .map((msg) => ({ ...msg }));
    setTranslationQueue(queue);
  };

  // Обработка следующего сообщения в очереди (перенесено из Messenger без изменений)
  const translateNextMessage = () => {
    if (translationQueue.length === 0 || isTranslating) return;
    setIsTranslating(true);
    const message = translationQueue[0];
    translateMessageFunc(message);
  };

  // Перевод одного сообщения (перенесено из Messenger без изменений)
  const translateMessageFunc = async (message) => {
    try {
      const translatedContent = await translateMessage(message.content, translationLanguage);
      const updatedMessage = {
        ...message,
        translatedContent,
        translationLanguage,
        showOriginal: true,
      };

      // Сохранение перевода локально
      localStorage.setItem(
        `translation_${chatId}_${message.id}`,
        JSON.stringify({ translatedContent, translationLanguage })
      );

      // Сохранение перевода на сервере
      await saveTranslation(message.id, translatedContent, translationLanguage);

      setMessages((prevMessages) =>
        prevMessages.map((msg) => (msg.id === message.id ? updatedMessage : msg))
      );

      setTranslationQueue((prevQueue) => prevQueue.slice(1));
      setIsTranslating(false);
    } catch (error) {
      console.error('Translation error:', error);
      setTranslationQueue((prevQueue) => prevQueue.slice(1));
      setIsTranslating(false);
    }
  };

  // Очистка переводов (перенесено из Messenger без изменений)
  const clearTranslations = () => {
    setTranslationQueue([]);
    setMessages((prevMessages) => {
      const hasTranslations = prevMessages.some(
        (msg) => msg.translatedContent || msg.translationLanguage
      );
      if (!hasTranslations) return prevMessages;
      // Удаляем переводы из localStorage
      prevMessages.forEach((msg) => {
        localStorage.removeItem(`translation_${chatId}_${msg.id}`);
      });
      return prevMessages.map((msg) =>
        msg.translatedContent || msg.translationLanguage
          ? {
              ...msg,
              translatedContent: null,
              translationLanguage: null,
              showOriginal: true,
            }
          : msg
      );
    });
  };

  // Обработка сохранения настроек перевода (перенесено из Messenger без изменений)
  const handleTranslationSettingsSave = (enabled, language, newOnly) => {
    setIsTranslationEnabled(enabled);
    setTranslationLanguage(language);
    setTranslateNewOnly(newOnly);
  };

  // Переключение орфографии (перенесено из Messenger без изменений)
  const handleSpellCheckToggle = () => {
    setIsSpellCheckEnabled((prev) => {
      const newState = !prev;
      localStorage.setItem(`spellcheck_enabled_${chatId}`, newState);
      if (newState) {
        localStorage.setItem(`spellcheck_enabled_timestamp_${chatId}`, new Date().toISOString());
      } else {
        localStorage.removeItem(`spellcheck_enabled_timestamp_${chatId}`);
      }
      return newState;
    });
  };

  // Проверка орфографии (перенесено из Messenger без изменений)
  const spellCheckMessage = async (content) => {
    if (!content.trim()) return content; // Пропускаем пустые сообщения
    try {
      const response = await fetch('https://unlimitedpossibilities12.org/api/spellcheck', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: content }),
      });
      if (!response.ok) {
        throw new Error('Spell check failed');
      }
      const result = await response.text(); // Получаем текст ответа
      return result.replace(/^"|"$/g, ''); // Удаляем кавычки
    } catch (error) {
      console.error('Spell check error:', error);
      toast.error('Failed to spell check message. Sending original text.');
      return content; // Возвращаем исходный текст при ошибке
    }
  };

  // Эффекты для обработки переводов (перенесено из Messenger без изменений)
  useEffect(() => {
    if (isTranslationEnabled) {
      translateExistingMessages();
    } else {
      clearTranslations();
    }
  }, [isTranslationEnabled, translationLanguage, translateNewOnly]);

  useEffect(() => {
    translateNextMessage();
  }, [translationQueue, isTranslating]);

  return {
    isSpellCheckEnabled,
    handleSpellCheckToggle,
    spellCheckMessage,
    handleTranslationSettingsSave,
    isMessageNew,
    translateExistingMessages,
    clearTranslations,
    translationQueue,
    setTranslationQueue,
    isTranslating,
  };
};

export default useTranslationAndSpellCheck;