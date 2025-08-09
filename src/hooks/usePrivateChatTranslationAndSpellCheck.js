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
  isTranscriptionEnabled, // Флаг для включения транскрипции
  setIsTranscriptionEnabled, // Функция для установки флага транскрипции
}) => {

  const [transcriptionQueue, setTranscriptionQueue] = useState([]);
  const [isTranscribing, setIsTranscribing] = useState(false);

  useEffect(() => {
    transcribeNextMessage();
  }, [transcriptionQueue, isTranscribing, transcribeNextMessage]);

  useEffect(() => {
    if (isTranscriptionEnabled && transcriptionMode === 'all') {
      transcribeExistingMessages();
    } else if (!isTranscriptionEnabled) {
      setTranscriptionQueue([]);
      setMessages((prevMessages) =>
        prevMessages.map((msg) =>
          msg.transcribedContent
            ? { ...msg, transcribedContent: null, showTranscription: false }
            : msg
        )
      );
    }
  }, [isTranscriptionEnabled, transcriptionMode, transcribeExistingMessages, setMessages, setTranscriptionQueue]);

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
      const contentToTranslate = message.messageType === 'audio' ? message.transcribedContent : message.content;
      if (!contentToTranslate || (message.messageType === 'audio' && !isTranscriptionEnabled)) {
        console.warn('Cannot translate: No valid content or transcription disabled', {
          msgId: message.id,
          content: message.content,
          transcribedContent: message.transcribedContent,
          isTranscriptionEnabled,
        });
        toast.error('Cannot translate: No valid content');
        return;
      }
      try {
        setIsTranslating(true);
        const translatedContent = await translateMessage(contentToTranslate, translationLanguage);
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
    [chatId, isTranslationEnabled, isTranscriptionEnabled, translationLanguage, setMessages, setIsTranslating]
  );

  // Перевод существующих сообщений
  const translateExistingMessages = useCallback(() => {
    if (!isTranslationEnabled || translationMode !== 'all') return;
    const queue = messages
      .filter((msg, index) => {
        const isAudio = msg.messageType === 'audio';
        const isDeleted = msg.content === 'Message deleted';
        const isTranslated = msg.translatedContent && msg.translationLanguage === translationLanguage;
        const isValid =
          (!isAudio && !isDeleted && isMessageNew(msg) && !isTranslated) ||
          (isAudio && isTranscriptionEnabled && msg.transcribedContent && !isTranslated);
        if (!isValid) {
          console.log(`Message at index ${index} skipped from translation queue:`, {
            id: msg.id,
            content: msg.content,
            transcribedContent: msg.transcribedContent,
            reason: isAudio
              ? !isTranscriptionEnabled
                ? 'Transcription disabled'
                : !msg.transcribedContent
                ? 'No transcription'
                : isTranslated
                ? 'Already translated'
                : 'Unknown'
              : isDeleted
              ? 'Deleted message'
              : isTranslated
              ? 'Already translated'
              : 'Not new',
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
      console.log('Translation queue:', queue.map(m => ({ id: m.id, content: m.content, transcribedContent: m.transcribedContent })));
      setTranslationQueue(queue);
    } else {
      console.log('No messages to translate');
    }
  }, [isTranslationEnabled, isTranscriptionEnabled, translationMode, messages, translationLanguage, isMessageNew, setTranslationQueue]);

  // Перевод одного сообщения
  const translateMessageFunc = useCallback(
    async (message) => {
      const currentMessage = messages.find((msg) => msg.id === message.id);
      const contentToTranslate = message.messageType === 'audio' ? message.transcribedContent : message.content;
      if (
        currentMessage &&
        currentMessage.translatedContent &&
        currentMessage.translationLanguage === translationLanguage &&
        currentMessage.content !== 'Message deleted' &&
        (currentMessage.messageType !== 'audio' || (currentMessage.messageType === 'audio' && isTranscriptionEnabled))
      ) {
        console.log('Skipping translation, message already translated:', {
          id: currentMessage.id,
          content: currentMessage.content,
          transcribedContent: currentMessage.transcribedContent,
          translatedContent: currentMessage.translatedContent,
          translationLanguage: currentMessage.translationLanguage,
        });
        setTranslationQueue((prevQueue) => prevQueue.slice(1));
        setIsTranslating(false);
        return;
      }
      if (!contentToTranslate || (message.messageType === 'audio' && !isTranscriptionEnabled)) {
        console.warn('Cannot translate: No valid content or transcription disabled', {
          id: message.id,
          content: message.content,
          transcribedContent: message.transcribedContent,
          isTranscriptionEnabled,
        });
        setTranslationQueue((prevQueue) => prevQueue.slice(1));
        setIsTranslating(false);
        return;
      }
      try {
        console.log('Translating message:', { id: message.id, content: contentToTranslate });
        const translatedContent = await translateMessage(contentToTranslate, translationLanguage);
        const updatedMessage = {
          ...message,
          translatedContent,
          translationLanguage,
          showOriginal: true,
          delivered: message.delivered ?? message.delivered_status,
          read: message.read ?? message.read_status,
        };
        console.log('Updated message after translation:', updatedMessage);
        localStorage.setItem(
          `translation_${chatId}_${message.id}`,
          JSON.stringify({ translatedContent, translationLanguage })
        );
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
        console.error('Translation error for message:', {
          id: message.id,
          content: contentToTranslate,
          error: error.message,
        });
        setTranslationQueue((prevQueue) => prevQueue.slice(1));
        setIsTranslating(false);
      }
    },
    [chatId, isTranscriptionEnabled, translationLanguage, messages, setMessages, setTranslationQueue, setIsTranslating]
  );

  // Обработка следующего сообщения в очереди
  const translateNextMessage = useCallback(() => {
    if (translationQueue.length === 0 || isTranslating) return;
    setIsTranslating(true);
    const message = translationQueue[0];
    if (
      isTranslationEnabled &&
      (translationMode === 'all' || translationMode === 'new') &&
      (message.messageType !== 'audio' || (message.messageType === 'audio' && isTranscriptionEnabled && message.transcribedContent))
    ) {
      translateMessageFunc(message);
    } else {
      console.log('Skipping translation for message:', {
        id: message.id,
        reason: !isTranslationEnabled
          ? 'Translation disabled'
          : message.messageType === 'audio' && !isTranscriptionEnabled
          ? 'Transcription disabled'
          : message.messageType === 'audio' && !message.transcribedContent
          ? 'No transcription'
          : 'Invalid translation mode',
      });
      setTranslationQueue((prevQueue) => prevQueue.slice(1));
      setIsTranslating(false);
    }
  }, [
    translationQueue,
    isTranslating,
    isTranslationEnabled,
    isTranscriptionEnabled,
    translationMode,
    translateMessageFunc,
    setTranslationQueue,
    setIsTranslating,
  ]);

  // Очистка переводов
  const clearTranslations = useCallback(() => {
    setTranslationQueue([]);
    setMessages((prevMessages) => {
      const hasTranslationsOrTranscriptions = prevMessages.some(
        (msg) => msg.translatedContent || msg.translationLanguage || msg.transcribedContent
      );
      if (!hasTranslationsOrTranscriptions) return prevMessages;
      prevMessages.forEach((msg) => {
        localStorage.removeItem(`translation_${chatId}_${msg.id}`);
        localStorage.removeItem(`transcription_${chatId}_${msg.id}`);
      });
      return prevMessages.map((msg) =>
        msg.translatedContent || msg.translationLanguage || msg.transcribedContent
          ? {
              ...msg,
              translatedContent: null,
              translationLanguage: null,
              transcribedContent: null,
              showTranscription: false,
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
    setIsTranscriptionEnabled(settings.isTranscriptionEnabled); // ДОБАВЛЕНО
    localStorage.setItem(`translation_enabled_${chatId}`, settings.isTranslationEnabled);
    localStorage.setItem(`translation_language_${chatId}`, settings.translationLanguage);
    localStorage.setItem(`translation_mode_${chatId}`, settings.translationMode);
    localStorage.setItem(`transcription_enabled_${chatId}`, settings.isTranscriptionEnabled); // ДОБАВЛЕНО
    if (settings.isTranslationEnabled) {
      localStorage.setItem(`translation_enabled_timestamp_${chatId}`, new Date().toISOString());
    } else {
      localStorage.removeItem(`translation_enabled_timestamp_${chatId}`);
    }
    if (settings.isTranscriptionEnabled) {
      localStorage.setItem(`transcription_enabled_timestamp_${chatId}`, new Date().toISOString()); // ДОБАВЛЕНО
    } else {
      localStorage.removeItem(`transcription_enabled_timestamp_${chatId}`); // ДОБАВЛЕНО
    }
    if (settings.isTranslationEnabled && settings.translationMode === 'all') {
      translateExistingMessages();
    } else if (!settings.isTranslationEnabled || !settings.isTranscriptionEnabled) {
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

  // ДОБАВЛЕНО: Функция для транскрипции аудиосообщений
  const transcribeAudioMessage = useCallback(
    async (message, retryCount = 0) => {
      if (!isTranscriptionEnabled || message.messageType !== 'audio' || !message.fileUrl) {
        console.warn('Cannot transcribe: Transcription disabled or invalid message', {
          msgId: message.id,
          isTranscriptionEnabled,
          messageType: message.messageType,
          fileUrl: message.fileUrl,
        });
        return;
      }
      try {
        const response = await fetch('https://unlimitedpossibilities12.org/api/transcription', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            audioUrl: message.fileUrl,
            language: 'auto',
          }),
        });
        if (!response.ok) {
          if (retryCount < 2) {
            console.log(`Retrying transcription for msg ${message.id}, attempt ${retryCount + 1}`);
            setTimeout(() => transcribeAudioMessage(message, retryCount + 1), 3000);
            return;
          }
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        const json = await response.json();
        if (json.transcription) {
          const transcriptionKey = `transcription_${chatId}_${message.id}`;
          localStorage.setItem(
            transcriptionKey,
            JSON.stringify({ transcribedContent: json.transcription })
          );
          setMessages((prevMessages) =>
            prevMessages.map((msg) =>
              msg.id === message.id
                ? {
                    ...msg,
                    transcribedContent: json.transcription,
                    showTranscription: true,
                    delivered: msg.delivered ?? msg.delivered_status,
                    read: msg.read ?? msg.read_status,
                  }
                : msg
            )
          );
          toast.success('Audio transcribed successfully');
          if (isTranslationEnabled && translationMode !== 'selective') {
            console.log('Initiating translation for transcribed message:', { id: message.id });
            const updatedMessage = { ...message, transcribedContent: json.transcription };
            translateSingleMessage(updatedMessage);
          }
        } else {
          console.error('No transcription data in response:', json);
          toast.error('Failed to transcribe audio');
        }
      } catch (error) {
        console.error('Transcription error:', error);
        if (retryCount < 2) {
          console.log(`Retrying transcription for msg ${message.id}, attempt ${retryCount + 1}`);
          setTimeout(() => transcribeAudioMessage(message, retryCount + 1), 3000);
          return;
        }
        toast.error('Failed to transcribe audio');
      }
    },
    [chatId, isTranscriptionEnabled, isTranslationEnabled, translationMode, setMessages, translateSingleMessage]
  );

  const transcribeNextMessage = useCallback(() => {
    if (transcriptionQueue.length === 0 || isTranscribing) return;
    setIsTranscribing(true);
    const message = transcriptionQueue[0];
    transcribeAudioMessage(message).then(() => {
      setTranscriptionQueue((prev) => prev.slice(1));
      setIsTranscribing(false);
      transcribeNextMessage();
    });
  }, [transcriptionQueue, isTranscribing, transcribeAudioMessage]);

  const transcribeExistingMessages = useCallback(() => {
    if (!isTranscriptionEnabled || transcriptionMode === 'selective') return;
    const queue = messages
      .filter((msg) => msg.messageType === 'audio' && !msg.transcribedContent && msg.fileUrl)
      .sort((a, b) => a.id - b.id)
      .filter((msg, index, self) => index === self.findIndex((m) => m.id === msg.id));
    if (queue.length > 0) {
      console.log('Transcription queue:', queue.map((m) => ({ id: m.id, fileUrl: m.fileUrl })));
      setTranscriptionQueue(queue);
    }
  }, [isTranscriptionEnabled, transcriptionMode, messages, setTranscriptionQueue]);

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
    transcribeAudioMessage, // Экспорт функции транскрипции
    transcribeExistingMessages,
    translationMode,
    setTranslationMode,
  };
};

export default usePrivateChatTranslationAndSpellCheck;