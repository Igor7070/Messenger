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
  transcribedContentMap, // ДОБАВЛЕНО
  setTranscribedContentMap, // ДОБАВЛЕНО
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

      const contentToTranslate = message.messageType === 'audio' && transcribedContentMap[message.id]?.content
        ? transcribedContentMap[message.id].content
        : message.content;

      if (!contentToTranslate || contentToTranslate === 'Message deleted') {
        console.warn('Translation not possible:', {
          isTranslationEnabled,
          content: contentToTranslate,
          msgId: message.id,
          isAudio: message.messageType === 'audio',
        });
        toast.error('No valid content to translate');
        return;
      }

      try {
        setIsTranslating(true);
        const translatedContent = await translateMessage(contentToTranslate, translationLanguage);
        const translationKey = `translation_${chatId}_${message.id}`;
        const transcriptionKey = `transcription_${chatId}_${message.id}`;
        const translationData = {
          translatedContent,
          translationLanguage,
        };

        if (message.messageType === 'audio' && transcribedContentMap[message.id]?.content) {
          setTranscribedContentMap((prev) => ({
            ...prev,
            [message.id]: {
              ...prev[message.id],
              translatedContent,
              translationLanguage,
            },
          }));
          const transcriptionData = {
            content: transcribedContentMap[message.id].content,
            translatedContent,
            translationLanguage,
          };
          localStorage.setItem(transcriptionKey, JSON.stringify(transcriptionData));
        } else {
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
        }

        await saveTranslation(message.id, translatedContent, translationLanguage);
        toast.success('Message translated successfully');
      } catch (error) {
        console.error('Translation error:', error);
        toast.error('Failed to translate message');
      } finally {
        setIsTranslating(false);
      }
    },
    [chatId, isTranslationEnabled, translationLanguage, setMessages, setIsTranslating, transcribedContentMap, setTranscribedContentMap]
  );

  // Перевод существующих сообщений
  const translateExistingMessages = useCallback(() => {
    if (!isTranslationEnabled || translationMode !== 'all') return;
    const queue = messages
      .filter((msg, index) => {
        const isAudio = msg.messageType === 'audio';
        const isDeleted = msg.content === 'Message deleted';
        const hasTranscription = isAudio && transcribedContentMap[msg.id]?.content;
        const isTranslated = (isAudio && transcribedContentMap[msg.id]?.translatedContent && transcribedContentMap[msg.id]?.translationLanguage === translationLanguage) ||
                            (!isAudio && msg.translatedContent && msg.translationLanguage === translationLanguage);
        const isValid = (!isAudio || hasTranscription) && !isDeleted && isMessageNew(msg) && !isTranslated;
        if (!isValid) {
          console.log(`Message at index ${index} skipped from translation queue:`, {
            id: msg.id,
            content: isAudio ? transcribedContentMap[msg.id]?.content : msg.content,
            reason: isAudio && !hasTranscription ? 'Audio without transcription' : isDeleted ? 'Deleted message' : isTranslated ? 'Already translated' : 'Not new',
            translatedContent: isAudio ? transcribedContentMap[msg.id]?.translatedContent : msg.translatedContent,
            translationLanguage: isAudio ? transcribedContentMap[msg.id]?.translationLanguage : msg.translationLanguage,
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
      console.log('Translation queue:', queue.map(m => ({ id: m.id, content: m.messageType === 'audio' ? transcribedContentMap[m.id]?.content : m.content })));
      setTranslationQueue(queue);
    } else {
      console.log('No messages to translate');
    }
  }, [isTranslationEnabled, translationMode, messages, translationLanguage, isMessageNew, setTranslationQueue, transcribedContentMap]);

  // Перевод одного сообщения
  const translateMessageFunc = useCallback(
    async (message) => {
      const contentToTranslate = message.messageType === 'audio' && transcribedContentMap[message.id]?.content
        ? transcribedContentMap[message.id].content
        : message.content;

      const currentMessage = messages.find((msg) => msg.id === message.id);
      if (
        currentMessage &&
        ((message.messageType === 'audio' && transcribedContentMap[message.id]?.translatedContent && transcribedContentMap[message.id]?.translationLanguage === translationLanguage) ||
        (message.messageType !== 'audio' && currentMessage.translatedContent && currentMessage.translationLanguage === translationLanguage)) &&
        contentToTranslate !== 'Message deleted'
      ) {
        console.log('Skipping translation, message already translated:', {
          id: currentMessage.id,
          content: contentToTranslate,
          translatedContent: message.messageType === 'audio' ? transcribedContentMap[message.id]?.translatedContent : currentMessage.translatedContent,
          translationLanguage: message.messageType === 'audio' ? transcribedContentMap[message.id]?.translationLanguage : currentMessage.translationLanguage,
        });
        setTranslationQueue((prevQueue) => prevQueue.slice(1));
        setIsTranslating(false);
        return;
      }

      try {
        console.log('Translating message:', { id: message.id, content: contentToTranslate });
        const translatedContent = await translateMessage(contentToTranslate, translationLanguage);
        const translationKey = `translation_${chatId}_${message.id}`;
        const transcriptionKey = `transcription_${chatId}_${message.id}`;
        const translationData = {
          translatedContent,
          translationLanguage,
        };

        if (message.messageType === 'audio' && transcribedContentMap[message.id]?.content) {
          setTranscribedContentMap((prev) => ({
            ...prev,
            [message.id]: {
              ...prev[message.id],
              translatedContent,
              translationLanguage,
            },
          }));
          const transcriptionData = {
            content: transcribedContentMap[message.id].content,
            translatedContent,
            translationLanguage,
          };
          localStorage.setItem(transcriptionKey, JSON.stringify(transcriptionData));
        } else {
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
        }

        await saveTranslation(message.id, translatedContent, translationLanguage);
        setTranslationQueue((prevQueue) => prevQueue.slice(1));
        setIsTranslating(false);
      } catch (error) {
        console.error('Translation error for message:', { id: message.id, content: contentToTranslate, error: error.message });
        setTranslationQueue((prevQueue) => prevQueue.slice(1));
        setIsTranslating(false);
      }
    },
    [chatId, translationLanguage, messages, setMessages, setTranslationQueue, setIsTranslating, transcribedContentMap, setTranscribedContentMap]
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
      if (!hasTranslations && Object.keys(transcribedContentMap).every((id) => !transcribedContentMap[id].translatedContent)) {
        return prevMessages;
      }
      // Удаляем переводы из localStorage
      prevMessages.forEach((msg) => {
        localStorage.removeItem(`translation_${chatId}_${msg.id}`);
        if (msg.messageType === 'audio' && transcribedContentMap[msg.id]?.content) {
          localStorage.setItem(
            `transcription_${chatId}_${msg.id}`,
            JSON.stringify({
              content: transcribedContentMap[msg.id].content,
              translatedContent: null,
              translationLanguage: null,
            })
          );
        }
      });
      setTranscribedContentMap((prev) => {
        const newMap = {};
        Object.keys(prev).forEach((id) => {
          newMap[id] = {
            content: prev[id].content,
            translatedContent: null,
            translationLanguage: null,
          };
        });
        return newMap;
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
  }, [chatId, setMessages, setTranslationQueue, transcribedContentMap, setTranscribedContentMap]);

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