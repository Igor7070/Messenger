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
      // Берём последние 20 сообщений как контекст (точно как в Android)
      const history = messages.slice(-20).map(msg => ({
        content: msg.content || '',
        sender: { id: msg.sender?.id, username: msg.sender?.username },
        timestamp: msg.timestamp,
        messageType: msg.messageType || 'text'
      }));

      const requestBody = {
        history,
        preference: preferenceChecked ? preferenceText.trim() : null
      };

      const response = await fetch('https://unlimitedpossibilities12.org/api/generate-message', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          // Если нужен токен — раскомментируй и подставь
          // 'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
        },
        body: JSON.stringify(requestBody),
      });

      const contentType = response.headers.get('content-type');

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Сервер вернул ошибку ${response.status}: ${errorText || 'нет деталей'}`);
      }

      let text = '';
      if (contentType && contentType.includes('application/json')) {
        const data = await response.json();
        text = data.message?.trim() || data.text?.trim() || '';
      } else {
        // Сервер вернул просто строку (как часто бывает в Android-ответах)
        text = await response.text();
        text = text.trim();
      }

      if (text) {
        setGeneratedText(text);
        toast.success('Сообщение сгенерировано');
      } else {
        toast.warn('Сервер вернул пустой ответ');
      }
    } catch (err) {
      console.error('Ошибка генерации:', err);
      toast.error(err.message || 'Не удалось сгенерировать сообщение');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleCopy = () => {
    navigator.clipboard.writeText(generatedText);
    toast.success('Скопировано');
  };

  const handleEdit = () => {
    if (generatedText.trim()) {
      onSend(generatedText.trim());   // вставляем в поле ввода чата
      onClose();                      // закрываем диалог
      toast.info('Сообщение вставлено в поле ввода — отредактируйте и отправьте');
    } else {
      toast.warn('Сообщение пустое');
    }
  };

  const handleSend = () => {
    if (generatedText.trim()) {
      onSend(generatedText.trim());   // вставляем в поле ввода
      // Вызываем sendMessage() из родительского компонента (PrivateChat)
      // Но поскольку onSend уже вызывает setContent + sendMessage — ничего больше не нужно
      onClose();                      // закрываем диалог
      toast.success('Сообщение отправлено');
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
        <Typography variant="h6">AI message generation</Typography>
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
          label="Add a wish to the message"
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
                Generating a message...
              </Typography>
            </Box>
          ) : generatedText ? (
            <Typography variant="body1" sx={{ whiteSpace: 'pre-wrap' }}>
              {generatedText}
            </Typography>
          ) : (
            <Typography variant="body2" color="text.secondary" align="center" sx={{ py: 4 }}>
              Click "Generate" to get a smart message
            </Typography>
          )}
        </Box>
      </DialogContent>

      <DialogActions sx={{ px: 3, pb: 3, pt: 1 }}>
        {generatedText && (
          <>
            <IconButton onClick={handleCopy} title="Copy">
              <CopyIcon />
            </IconButton>

            <Button
              onClick={handleEdit}
              startIcon={<EditIcon />}
              color="primary"
            >
              Edit
            </Button>

            <Button
              variant="contained"
              color="primary"
              startIcon={<SendIcon />}
              onClick={handleSend}
            >
              Send
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
            {isGenerating ? 'Generation...' : 'Generate'}
          </Button>
        )}

        <Button onClick={onClose} color="inherit">
          Close
        </Button>
      </DialogActions>
    </StyledDialog>
  );
};

export default AiMessageGeneratorDialog;