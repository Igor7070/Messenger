import { useCallback } from 'react';
import { translateMessage, saveTranslation } from '../api/messages';
import { toast } from 'react-toastify';

const usePrivateChatTranslationAndSpellCheck = ({
  chatId,
  chatIdParam,
  messages,
  setMessages,
  isTranslationEnabled,
  setIsTranslationEnabled,
  translationLanguage,
  setTranslationLanguage,
  translationMode,
  setTranslationMode,
  translationQueue,
  setTranslationQueue,
  isTranslating,
  setIsTranslating,
  setIsSpellCheckEnabled,
}) => {

  // Проверка, является ли сообщение новым
  const isMessageNew = useCallback(
    (message) => {
      if (translationMode !== 'new') return true;
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
    [chatId, translationMode]
  );

  // Перевод существующего сообщения (для ручного перевода)
  const translateSingleMessage = useCallback(
    async (message) => {
      if (!isTranslationEnabled) {
        toast.error('Translation is not enabled');
        return;
      }
      try {
        setIsTranslating(true);
        const translatedContent = await translateMessage(message.content, translationLanguage);
        const translationKey = `translation_${chatId}_${message.id}`;
        const translationData = {
          translatedContent,
          translationLanguage,
        };
        localStorage.setItem(translationKey, JSON.stringify(translationData));

        setMessages((prevMessages) =>
          prevMessages.map((msg) =>
            msg.id === message.id
              ? {
                  ...msg,
                  translatedContent,
                  translationLanguage,
                  showOriginal: true,
                  delivered: msg.delivered ?? msg.delivered_status,
                  read: msg.read ?? msg.read_status,
                }
              : msg
          )
        );

        await saveTranslation(message.id, translatedContent, translationLanguage);
        toast.success('Message translated successfully');
      } catch (error) {
        console.error('Translation error:', error);
        toast.error('Failed to translate message');
      } finally {
        setIsTranslating(false);
      }
    },
    [chatId, isTranslationEnabled, translationLanguage, setMessages, setIsTranslating]
  );

  // Перевод существующих сообщений
  const translateExistingMessages = useCallback(() => {
    if (!isTranslationEnabled || translationMode !== 'all') return;
    const queue = messages
      .filter((msg, index) => {
        const isAudio = msg.messageType === 'audio';
        const isDeleted = msg.content === 'Message deleted';
        const isTranslated = msg.translatedContent && msg.translationLanguage === translationLanguage;
        const isValid = !isAudio && !isDeleted && isMessageNew(msg) && !isTranslated;
        if (!isValid) {
          console.log(`Message at index ${index} skipped from translation queue:`, {
            id: msg.id,
            content: msg.content,
            reason: isAudio ? 'Audio message' : isDeleted ? 'Deleted message' : isTranslated ? 'Already translated' : 'Not new',
            translatedContent: msg.translatedContent,
            translationLanguage: msg.translationLanguage,
          });
        }
        return isValid;
      })
      .sort((a, b) => a.id - b.id)
      .filter((msg, index, self) => 
        index === self.findIndex((m) => m.id === msg.id)
      )
      .map((msg) => ({
        ...msg,
        delivered: msg.delivered ?? msg.delivered_status,
        read: msg.read ?? msg.read_status,
      }));
    if (queue.length > 0) {
      console.log('Translation queue:', queue.map(m => ({ id: m.id, content: m.content })));
      setTranslationQueue(queue);
    } else {
      console.log('No messages to translate');
    }
  }, [isTranslationEnabled, translationMode, messages, translationLanguage, isMessageNew, setTranslationQueue]);

  // Перевод одного сообщения
  const translateMessageFunc = useCallback(
    async (message) => {
      // Проверяем актуальное состояние сообщения из messages
      const currentMessage = messages.find((msg) => msg.id === message.id);
      if (
        currentMessage &&
        currentMessage.translatedContent &&
        currentMessage.translationLanguage === translationLanguage &&
        currentMessage.content !== 'Message deleted' &&
        currentMessage.messageType !== 'audio'
      ) {
        console.log('Skipping translation, message already translated:', {
          id: currentMessage.id,
          content: currentMessage.content,
          translatedContent: currentMessage.translatedContent,
          translationLanguage: currentMessage.translationLanguage,
        });
        setTranslationQueue((prevQueue) => prevQueue.slice(1));
        setIsTranslating(false);
        return;
      }

      try {
        console.log('Translating message:', message); // Отладка
        const translatedContent = await translateMessage(message.content, translationLanguage);
        const updatedMessage = {
          ...message,
          translatedContent,
          translationLanguage,
          showOriginal: true,
          delivered: message.delivered ?? message.delivered_status,
          read: message.read ?? message.read_status,
        };

        console.log('Updated message after translation:', updatedMessage); // Отладка

        // Сохранение перевода локально
        localStorage.setItem(
          `translation_${chatId}_${message.id}`,
          JSON.stringify({ translatedContent, translationLanguage })
        );

        // Сохранение перевода на сервере
        await saveTranslation(message.id, translatedContent, translationLanguage);

        setMessages((prevMessages) =>
          prevMessages.map((msg) =>
            msg.id === message.id
              ? {
                  ...msg,
                  translatedContent,
                  translationLanguage,
                  showOriginal: true,
                  delivered: msg.delivered ?? msg.delivered_status,
                  read: msg.read ?? msg.read_status,
                }
              : msg
          )
        );

        setTranslationQueue((prevQueue) => prevQueue.slice(1));
        setIsTranslating(false);
      } catch (error) {
        console.error('Translation error for message:', { id: message.id, content: message.content, error: error.message });
        setTranslationQueue((prevQueue) => prevQueue.slice(1));
        setIsTranslating(false);
      }
    },
    [chatId, translationLanguage, messages, setMessages, setTranslationQueue, setIsTranslating]
  );

  // Обработка следующего сообщения в очереди
  const translateNextMessage = useCallback(() => {
    if (translationQueue.length === 0 || isTranslating) return;
    setIsTranslating(true);
    const message = translationQueue[0];
    if (isTranslationEnabled && (translationMode === 'all' || translationMode === 'new')) {
      translateMessageFunc(message);
    } else {
      setTranslationQueue((prevQueue) => prevQueue.slice(1));
      setIsTranslating(false);
    }
  }, [translationQueue, isTranslating, isTranslationEnabled, translationMode, translateMessageFunc, setTranslationQueue, setIsTranslating]);

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
  const handleTranslationSettingsSave = (settings) => {
    setIsTranslationEnabled(settings.isTranslationEnabled);
    setTranslationLanguage(settings.translationLanguage);
    setTranslationMode(settings.translationMode);
    localStorage.setItem(`translation_enabled_${chatId}`, settings.isTranslationEnabled);
    localStorage.setItem(`translation_language_${chatId}`, settings.translationLanguage);
    localStorage.setItem(`translation_mode_${chatId}`, settings.translationMode);
    if (settings.isTranslationEnabled) {
      localStorage.setItem(`translation_enabled_timestamp_${chatId}`, new Date().toISOString());
    } else {
      localStorage.removeItem(`translation_enabled_timestamp_${chatId}`);
    }
    if (settings.isTranslationEnabled && settings.translationMode === 'all') {
      translateExistingMessages();
    } else if (!settings.isTranslationEnabled) {
      clearTranslations();
    }
  };

  // Переключение орфографии
  const handleSpellCheckToggle = useCallback(() => {
    setIsSpellCheckEnabled((prev) => {
      const newState = !prev;
      localStorage.setItem(`spellcheck_enabled_${chatIdParam}`, newState); // Используем chatIdParam
      if (newState) {
        localStorage.setItem(`spellcheck_enabled_timestamp_${chatIdParam}`, new Date().toISOString());
      } else {
        localStorage.removeItem(`spellcheck_enabled_timestamp_${chatIdParam}`);
      }
      return newState;
    });
  }, [chatIdParam, setIsSpellCheckEnabled]);

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
    translateSingleMessage,
    clearTranslations,
    handleTranslationSettingsSave,
    handleSpellCheckToggle,
    spellCheckMessage,
    translationMode,
    setTranslationMode,
  };
};

export default usePrivateChatTranslationAndSpellCheck;