import { useCallback } from 'react';
import { translateMessage, saveTranslation } from '../api/messages';
import { toast } from 'react-toastify';

const useGroupChatTranslationAndSpellCheck = ({
  chatId,
  messages,
  setMessages,
  isTranslationEnabled,
  setIsTranslationEnabled,
  translationLanguage,
  setTranslationLanguage,
  translateNewOnly,
  setTranslateNewOnly,
  translationQueue,
  setTranslationQueue,
  isTranslating,
  setIsTranslating,
  setIsSpellCheckEnabled,
}) => {
  // Проверка, является ли сообщение новым
  const isMessageNew = useCallback(
    (message) => {
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
    },
    [chatId, translateNewOnly]
  );

  // Перевод существующих сообщений
  const translateExistingMessages = useCallback(() => {
    if (!isTranslationEnabled) return;
    const queue = messages
      .filter(
        (msg) =>
          isMessageNew(msg) &&
          (!msg.translatedContent || msg.translationLanguage !== translationLanguage)
      )
      .map((msg) => ({ ...msg }));
    setTranslationQueue(queue);
  }, [isTranslationEnabled, messages, translationLanguage, isMessageNew, setTranslationQueue]);

  // Обработка следующего сообщения в очереди
  const translateNextMessage = useCallback(() => {
    if (translationQueue.length === 0 || isTranslating) return;
    setIsTranslating(true);
    const message = translationQueue[0];
    translateMessageFunc(message);
  }, [translationQueue, isTranslating, setIsTranslating]);

  // Перевод одного сообщения
  const translateMessageFunc = useCallback(
    async (message) => {
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
    },
    [chatId, translationLanguage, setMessages, setTranslationQueue, setIsTranslating]
  );

  // Очистка переводов
  const clearTranslations = useCallback(() => {
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
  }, [chatId, setMessages, setTranslationQueue]);

  // Обработка сохранения настроек перевода
  const handleTranslationSettingsSave = useCallback(
    (enabled, language, newOnly) => {
      setIsTranslationEnabled(enabled);
      setTranslationLanguage(language);
      setTranslateNewOnly(newOnly);
    },
    [setIsTranslationEnabled, setTranslationLanguage, setTranslateNewOnly]
  );

  // Переключение орфографии
  const handleSpellCheckToggle = useCallback(() => {
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
  }, [chatId, setIsSpellCheckEnabled]);

  // Проверка орфографии
  const spellCheckMessage = useCallback(async (content) => {
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
  }, []);

  return {
    isMessageNew,
    translateExistingMessages,
    translateNextMessage,
    translateMessageFunc,
    clearTranslations,
    handleTranslationSettingsSave,
    handleSpellCheckToggle,
    spellCheckMessage,
  };
};

export default useGroupChatTranslationAndSpellCheck;