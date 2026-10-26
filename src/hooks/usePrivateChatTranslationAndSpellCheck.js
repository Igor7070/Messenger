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
  transcribedContentMap, 
  setTranscribedContentMap, 
  isTranscriptionEnabled,
  setIsTranscriptionEnabled, // ДОБАВЛЕНО 
  transcriptionMode, 
  setTranscriptionMode, // ДОБАВЛЕНО
  transcriptionQueue, // ДОБАВЛЕНО
  setTranscriptionQueue, // ДОБАВЛЕНО
  isTranscribing, // ДОБАВЛЕНО
  setIsTranscribing, // ДОБАВЛЕНО
}) => {

  // Проверка, является ли сообщение новым
  const isMessageNew = useCallback(
    (message, context = 'translation') => {
      const modeKey = context === 'transcription' ? `transcription_mode_${chatId}` : `translation_mode_${chatId}`;
      const timestampKey = context === 'transcription' ? `transcription_enabled_timestamp_${chatId}` : `translation_enabled_timestamp_${chatId}`;
      const currentMode = localStorage.getItem(modeKey) || (context === 'transcription' ? transcriptionMode : translationMode);
      if (currentMode !== 'new') return true;
      const enabledTimestamp = localStorage.getItem(timestampKey);
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
    [chatId, translationMode, transcriptionMode]
  );

  // Перевод существующего сообщения (для ручного перевода)
  const translateSingleMessage = useCallback(
    async (message) => {
      if (!isTranslationEnabled) {
        toast.error('Translation is not enabled');
        return;
      }

      if (message.messageType === 'audio' && !transcribedContentMap[message.id]?.content) {
        toast.error('Transcription is not available for this audio message');
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
        const isValid = (!isAudio || hasTranscription) && !isDeleted && !isTranslated;
        if (!isValid) {
          console.log(`Message at index ${index} skipped from translation queue:`, {
            id: msg.id,
            content: isAudio ? transcribedContentMap[msg.id]?.content : msg.content,
            reason: isAudio && !hasTranscription ? 'Audio without transcription' : isDeleted ? 'Deleted message' : isTranslated ? 'Already translated' : 'Unknown',
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
      console.log('Translation queue:', queue.map(m => ({
        id: m.id,
        content: m.messageType === 'audio' ? transcribedContentMap[m.id]?.content : m.content
      })));
      setTranslationQueue((prevQueue) => {
        const existingIds = new Set(prevQueue.map((m) => m.id));
        return [...prevQueue, ...queue.filter((m) => !existingIds.has(m.id))];
      });
    } else {
      console.log('No messages to translate');
    }
  }, [isTranslationEnabled, translationMode, messages, translationLanguage, transcribedContentMap, setTranslationQueue]);

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

  // ДОБАВЛЕНО: Обработка сохранения настроек транскрипции
  const handleTranscriptionSettingsSave = ({ isTranscriptionEnabled, transcriptionMode }) => {
      setIsTranscriptionEnabled(isTranscriptionEnabled);
      setTranscriptionMode(transcriptionMode);
      localStorage.setItem(`transcription_enabled_${chatId}`, isTranscriptionEnabled);
      localStorage.setItem(`transcription_mode_${chatId}`, transcriptionMode);
      if (isTranscriptionEnabled) {
          localStorage.setItem(`transcription_enabled_timestamp_${chatId}`, new Date().toISOString());
      } else {
          localStorage.removeItem(`transcription_enabled_timestamp_${chatId}`);
          setTranscribedContentMap({});
          Object.keys(localStorage)
              .filter((key) => key.startsWith(`transcription_${chatId}_`))
              .forEach((key) => localStorage.removeItem(key));
      }
  };

  // ДОБАВЛЕНО: Транскрипция одного сообщения
  const transcribeSingleMessage = async (message) => {
      if (!isTranscriptionEnabled) {
          toast.error('Transcription is not enabled');
          return;
      }
      try {
          setIsTranscribing(true);
          const response = await fetch(`https://unlimitedpossibilities12.org/api/transcribe/${message.id}`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ fileUrl: message.fileUrl }),
          });
          if (!response.ok) {
              throw new Error(`HTTP error! status: ${response.status}`);
          }
          const content = await response.text();
          if (!content) {
              throw new Error('Empty transcription received');
          }
          setTranscribedContentMap((prev) => ({
              ...prev,
              [message.id]: {
                  content,
                  translatedContent: prev[message.id]?.translatedContent || null,
                  translationLanguage: prev[message.id]?.translationLanguage || null,
              },
          }));
          localStorage.setItem(
              `transcription_${chatId}_${message.id}`,
              JSON.stringify({
                  content,
                  translatedContent: null,
                  translationLanguage: null,
              })
          );
          if (isTranslationEnabled && (translationMode === 'all' || translationMode === 'new')) {
              setTranslationQueue((prevQueue) => [...prevQueue, message]);
          }
          if (isTranslationEnabled && (translationMode === 'all' || translationMode === 'new')) {
              setTranslationQueue((prevQueue) => [...prevQueue, message]);
              setMessages((prevMessages) =>
                  prevMessages.map((msg) =>
                      msg.id === message.id ? { ...msg, showOriginal: false } : msg
                  )
              );
          }
          toast.success('Voice message transcribed');
      } catch (error) {
          console.error('Transcription error:', error);
          toast.error('Could not transcribe this voice message');
      } finally {
          setIsTranscribing(false);
      }
  };

  // ДОБАВЛЕНО: Транскрипция существующих сообщений
  const transcribeExistingMessages = (messagesToTranscribe) => {
      if (!isTranscriptionEnabled || transcriptionMode !== 'all') return;
      const queue = messagesToTranscribe
          .filter((msg) => {
              const isAudio = msg.messageType === 'audio';
              const isTranscribed = transcribedContentMap[msg.id]?.content;
              const isNew = isMessageNew(msg, 'transcription');
              const isValid = isAudio && !isTranscribed && (transcriptionMode === 'all' || (transcriptionMode === 'new' && isNew));
              if (!isValid) {
                  console.log(`Message ${msg.id} skipped from transcription queue:`, {
                      reason: !isAudio ? 'Not audio' : isTranscribed ? 'Already transcribed' : !isNew ? 'Not new' : 'Unknown',
                  });
              }
              return isValid;
          })
          .sort((a, b) => a.id - b.id);
      if (queue.length > 0) {
          console.log('Transcription queue:', queue.map((m) => ({ id: m.id, fileUrl: m.fileUrl })));
          setTranscriptionQueue(queue);
      } else {
          console.log('No messages to transcribe');
      }
  };

  // ДОБАВЛЕНО: Обработка следующего сообщения в очереди транскрипции
  const transcribeNextMessage = async () => {
      if (transcriptionQueue.length === 0 || isTranscribing) return;
      setIsTranscribing(true);
      const message = transcriptionQueue[0];
      if (isTranscriptionEnabled && (transcriptionMode === 'all' || transcriptionMode === 'new')) {
          try {
              const response = await fetch(`https://unlimitedpossibilities12.org/api/transcribe/${message.id}`, {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ fileUrl: message.fileUrl }),
              });
              if (!response.ok) {
                  throw new Error(`HTTP error! status: ${response.status}`);
              }
              const content = await response.text();
              if (!content) {
                  throw new Error('Empty transcription received');
              }
              setTranscribedContentMap((prev) => ({
                  ...prev,
                  [message.id]: {
                      content,
                      translatedContent: prev[message.id]?.translatedContent || null,
                      translationLanguage: prev[message.id]?.translationLanguage || null,
                  },
              }));
              localStorage.setItem(
                  `transcription_${chatId}_${message.id}`,
                  JSON.stringify({
                      content,
                      translatedContent: null,
                      translationLanguage: null,
                  })
              );
              if (isTranslationEnabled && (translationMode === 'all' || translationMode === 'new')) {
                  setTranslationQueue((prevQueue) => [...prevQueue, message]);
              }
              if (isTranslationEnabled && (translationMode === 'all' || translationMode === 'new')) {
                  setTranslationQueue((prevQueue) => [...prevQueue, message]);
                  setMessages((prevMessages) =>
                      prevMessages.map((msg) =>
                          msg.id === message.id ? { ...msg, showOriginal: false } : msg
                      )
                  );
              }
          } catch (error) {
              // Очередь идёт сама: о каждой неудаче человеку сообщать незачем,
              // иначе при недоступных файлах экран заваливает одинаковыми окнами
              console.warn('Transcription skipped:', { id: message.id, fileUrl: message.fileUrl, error: error.message });
          } finally {
              setTranscriptionQueue((prevQueue) => prevQueue.slice(1));
              setIsTranscribing(false);
          }
      } else {
          setTranscriptionQueue((prevQueue) => prevQueue.slice(1));
          setIsTranscribing(false);
      }
  };

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
    handleTranscriptionSettingsSave,
    transcribeSingleMessage,
    transcribeExistingMessages,
    transcribeNextMessage,
  };
};

export default usePrivateChatTranslationAndSpellCheck;