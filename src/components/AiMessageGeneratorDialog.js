import React, { useState } from 'react';
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  Checkbox,
  FormControlLabel,
  TextField,
  CircularProgress,
  IconButton,
  Box,
  Typography,
  Slide,
} from '@mui/material';
import {
  ContentCopy as CopyIcon,
  Edit as EditIcon,
  Send as SendIcon,
  Close as CloseIcon,
} from '@mui/icons-material';
import { styled } from '@mui/material/styles';
import { toast } from 'react-toastify';

// Анимация появления снизу (как в мобильных мессенджерах)
const Transition = React.forwardRef(function Transition(props, ref) {
  return <Slide direction="up" ref={ref} {...props} />;
});

// Стилизованный диалог с современным видом
const StyledDialog = styled(Dialog)(({ theme }) => ({
  '& .MuiDialog-paper': {
    borderRadius: 28,
    boxShadow: '0 8px 32px rgba(0,0,0,0.3)',
    background: theme.palette.background.paper,
    overflow: 'hidden',
    maxWidth: '480px',
    width: '90%',
  },
}));

const AiMessageGeneratorDialog = ({
  open,
  onClose,
  onSend,
  messages, // передаём для контекста (последние 20 сообщений)
}) => {
  const [preferenceChecked, setPreferenceChecked] = useState(false);
  const [preferenceText, setPreferenceText] = useState('');
  const [generatedText, setGeneratedText] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);

  const handleGenerate = async () => {
    if (isGenerating) return;

    setIsGenerating(true);
    setGeneratedText('');

    try {
      // Берём последние 20 сообщений как контекст
      const history = messages.slice(-20).map(msg => ({
        content: msg.content,
        sender: { id: msg.sender.id, username: msg.sender.username },
        timestamp: msg.timestamp,
        messageType: msg.messageType || 'text',
      }));

      const requestBody = {
        history,
        preference: preferenceChecked ? preferenceText.trim() : null,
      };

      const response = await fetch('https://unlimitedpossibilities12.org/api/messages/generate', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          // Если нужен токен авторизации — добавь
          // 'Authorization': `Bearer ${localStorage.getItem('token')}`
        },
        body: JSON.stringify(requestBody),
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const data = await response.json();
      const text = data.message?.trim() || data.text?.trim() || '';

      if (text) {
        setGeneratedText(text);
        toast.success('Сообщение сгенерировано');
      } else {
        toast.warn('Не удалось сгенерировать текст');
      }
    } catch (err) {
      console.error('Ошибка генерации:', err);
      toast.error('Не удалось сгенерировать: ' + (err.message || 'попробуйте позже'));
    } finally {
      setIsGenerating(false);
    }
  };

  const handleCopy = () => {
    navigator.clipboard.writeText(generatedText);
    toast.success('Скопировано');
  };

  const handleEdit = () => {
    // Можно сделать редактирование внутри диалога, но проще сразу вставить в чат
    onSend(generatedText);
    onClose();
    toast.info('Сообщение вставлено в поле ввода');
  };

  const handleSend = () => {
    if (generatedText.trim()) {
      onSend(generatedText.trim());
      onClose();
    } else {
      toast.warn('Сообщение пустое');
    }
  };

  return (
    <StyledDialog
      open={open}
      onClose={onClose}
      TransitionComponent={Transition}
      maxWidth="sm"
      fullWidth
      aria-labelledby="ai-dialog-title"
    >
      <DialogTitle
        id="ai-dialog-title"
        sx={{
          pb: 1,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          background: 'linear-gradient(135deg, #6200ea 0%, #3700b3 100%)',
          color: 'white',
        }}
      >
        <Typography variant="h6">Генерация сообщения ИИ</Typography>
        <IconButton onClick={onClose} sx={{ color: 'white' }}>
          <CloseIcon />
        </IconButton>
      </DialogTitle>

      <DialogContent sx={{ pt: 3, pb: 2 }}>
        <FormControlLabel
          control={
            <Checkbox
              checked={preferenceChecked}
              onChange={(e) => setPreferenceChecked(e.target.checked)}
              color="primary"
              disabled={isGenerating}
            />
          }
          label="Добавить пожелание к сообщению"
          sx={{ mb: 2 }}
        />

        {preferenceChecked && (
          <TextField
            fullWidth
            variant="outlined"
            size="small"
            placeholder="Например: вежливее / в стиле пирата / коротко и по делу"
            value={preferenceText}
            onChange={(e) => setPreferenceText(e.target.value)}
            disabled={isGenerating}
            sx={{ mb: 3 }}
          />
        )}

        <Box
          sx={{
            minHeight: 120,
            p: 2,
            borderRadius: 2,
            backgroundColor: generatedText ? '#f5f5f5' : 'transparent',
            border: generatedText ? '1px solid #ddd' : '1px dashed #ccc',
            position: 'relative',
          }}
        >
          {isGenerating ? (
            <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 4 }}>
              <CircularProgress size={40} thickness={4} />
              <Typography variant="body2" sx={{ mt: 2, color: 'text.secondary' }}>
                Генерирую сообщение...
              </Typography>
            </Box>
          ) : generatedText ? (
            <Typography variant="body1" sx={{ whiteSpace: 'pre-wrap' }}>
              {generatedText}
            </Typography>
          ) : (
            <Typography variant="body2" color="text.secondary" align="center" sx={{ py: 4 }}>
              Нажмите «Сгенерировать», чтобы получить умное сообщение
            </Typography>
          )}
        </Box>
      </DialogContent>

      <DialogActions sx={{ px: 3, pb: 3, pt: 1 }}>
        {generatedText && (
          <>
            <IconButton onClick={handleCopy} title="Скопировать">
              <CopyIcon />
            </IconButton>

            <Button
              onClick={handleEdit}
              startIcon={<EditIcon />}
              color="primary"
            >
              Редактировать
            </Button>

            <Button
              variant="contained"
              color="primary"
              startIcon={<SendIcon />}
              onClick={handleSend}
            >
              Отправить
            </Button>
          </>
        )}

        {!generatedText && (
          <Button
            variant="contained"
            color="primary"
            onClick={handleGenerate}
            disabled={isGenerating}
            fullWidth
            sx={{ py: 1.5, borderRadius: 3 }}
          >
            {isGenerating ? 'Генерация...' : 'Сгенерировать'}
          </Button>
        )}

        <Button onClick={onClose} color="inherit">
          Закрыть
        </Button>
      </DialogActions>
    </StyledDialog>
  );
};

export default AiMessageGeneratorDialog;