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

// Анимация появления снизу
const Transition = React.forwardRef(function Transition(props, ref) {
  return <Slide direction="up" ref={ref} {...props} />;
});

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
  onInsert,       // вставка в поле ввода (можно оставить, но мы его почти не используем)
  onSendDirect,   // мгновенная отправка в чат
  messages,
}) => {
  const [preferenceChecked, setPreferenceChecked] = useState(false);
  const [preferenceText, setPreferenceText] = useState('');
  const [generatedText, setGeneratedText] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);

  const [isEditing, setIsEditing] = useState(false);
  const [editedText, setEditedText] = useState('');

  const handleGenerate = async () => {
    if (isGenerating) return;
    setIsGenerating(true);
    setGeneratedText('');
    setIsEditing(false);

    try {
      const history = messages.slice(-20).map((msg) => ({
        content: msg.content || '',
        sender: { id: msg.sender?.id, username: msg.sender?.username },
        timestamp: msg.timestamp,
        messageType: msg.messageType || 'text',
      }));

      const requestBody = {
        history,
        preference: preferenceChecked ? preferenceText.trim() : null,
      };

      const response = await fetch('https://unlimitedpossibilities12.org/api/generate-message', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody),
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Ошибка сервера ${response.status}: ${errorText || 'нет деталей'}`);
      }

      let text = '';
      const contentType = response.headers.get('content-type');
      if (contentType?.includes('application/json')) {
        const data = await response.json();
        text = data.message?.trim() || data.text?.trim() || '';
      } else {
        text = (await response.text()).trim();
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
    toast.success('Скопировано в буфер обмена');
  };

  const handleStartEdit = () => {
    setEditedText(generatedText);
    setIsEditing(true);
  };

  const handleSendDirect = () => {
    if (!generatedText.trim()) {
      toast.warn('Сообщение пустое');
      return;
    }
    onSendDirect(generatedText.trim());
    onClose();
    toast.success('Сообщение отправлено в чат');
  };

  const handleSendEdited = () => {
    if (!editedText.trim()) {
      toast.warn('Сообщение пустое');
      return;
    }
    onSendDirect(editedText.trim());
    onClose();
    toast.success('Отредактированное сообщение отправлено');
  };

  const handleCancelEdit = () => {
    setIsEditing(false);
    setEditedText('');
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
              disabled={isGenerating || isEditing}
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
            disabled={isGenerating || isEditing}
            sx={{ mb: 3 }}
          />
        )}

        <Box
          sx={{
            minHeight: 140,
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
          ) : isEditing ? (
            <TextField
              fullWidth
              multiline
              minRows={4}
              maxRows={8}
              variant="outlined"
              value={editedText}
              onChange={(e) => setEditedText(e.target.value)}
              autoFocus
              placeholder="Редактируйте сообщение здесь..."
              sx={{ '& .MuiOutlinedInput-root': { fontSize: '1rem' } }}
            />
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

      <DialogActions sx={{ px: 3, pb: 3, pt: 1, gap: 1.5, flexWrap: 'wrap' }}>
        {generatedText && !isEditing && (
          <>
            <IconButton onClick={handleCopy} title="Скопировать" size="small">
              <CopyIcon fontSize="small" />
            </IconButton>

            <Button
              onClick={handleStartEdit}
              startIcon={<EditIcon />}
              color="primary"
              variant="outlined"
            >
              Редактировать
            </Button>

            <Button
              variant="contained"
              color="primary"
              startIcon={<SendIcon />}
              onClick={handleSendDirect}
            >
              Отправить
            </Button>
          </>
        )}

        {isEditing && (
          <>
            <Button onClick={handleCancelEdit} color="inherit">
              Отмена
            </Button>

            <Button
              variant="contained"
              color="primary"
              startIcon={<SendIcon />}
              onClick={handleSendEdited}
            >
              Отправить отредактированное
            </Button>
          </>
        )}

        {!generatedText && !isEditing && (
          <Button
            variant="contained"
            color="primary"
            onClick={handleGenerate}
            disabled={isGenerating}
            fullWidth
            sx={{ py: 1.5, borderRadius: 3 }}
          >
            {isGenerating ? 'Генерирую...' : 'Сгенерировать'}
          </Button>
        )}

        <Button onClick={onClose} color="inherit" sx={{ ml: 'auto' }}>
          Закрыть
        </Button>
      </DialogActions>
    </StyledDialog>
  );
};

export default AiMessageGeneratorDialog;