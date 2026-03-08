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
  AutoAwesome as SparkleIcon,
} from '@mui/icons-material';
import { styled } from '@mui/material/styles';
import { toast } from 'react-toastify';

const Transition = React.forwardRef(function Transition(props, ref) {
  return <Slide direction="up" ref={ref} {...props} />;
});

const StyledDialog = styled(Dialog)(({ theme }) => ({
  '& .MuiDialog-paper': {
    borderRadius: 24,
    background: 'linear-gradient(135deg, #0f0c29 0%, #302b63 50%, #24243e 100%)',
    boxShadow: '0 20px 60px rgba(0,0,0,0.6), 0 0 40px rgba(98,0,234,0.3)',
    color: '#e0e0ff',
    overflow: 'hidden',
    maxWidth: '520px',
    width: '92%',
    border: '1px solid rgba(98,0,234,0.25)',
    backdropFilter: 'blur(12px)',
  },
}));

const TitleGradient = styled(Box)({
  background: 'linear-gradient(90deg, #a78bfa, #7c3aed, #d946ef)',
  WebkitBackgroundClip: 'text',
  WebkitTextFillColor: 'transparent',
  fontWeight: 700,
});

const AiMessageGeneratorDialog = ({
  open,
  onClose,
  onInsert,
  onSendDirect,
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

      if (!response.ok) throw new Error(`Server error ${response.status}`);

      let text = '';
      if (response.headers.get('content-type')?.includes('application/json')) {
        const data = await response.json();
        text = data.message?.trim() || data.text?.trim() || '';
      } else {
        text = (await response.text()).trim();
      }

      if (text) {
        setGeneratedText(text);
        toast.success('AI generated your reply ✨');
      } else {
        toast.warn('No reply generated');
      }
    } catch (err) {
      console.error(err);
      toast.error('Failed to generate message');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleCopy = () => {
    navigator.clipboard.writeText(generatedText);
    toast.success('Copied to clipboard');
  };

  const handleStartEdit = () => {
    setEditedText(generatedText);
    setIsEditing(true);
  };

  const handleSendDirect = () => {
    if (!generatedText.trim()) return toast.warn('Message is empty');
    onSendDirect(generatedText.trim());
    onClose();
  };

  const handleSendEdited = () => {
    if (!editedText.trim()) return toast.warn('Message is empty');
    onSendDirect(editedText.trim());
    onClose();
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
    >
      <DialogTitle
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          p: 3,
          background: 'linear-gradient(135deg, rgba(98,0,234,0.15), rgba(212,0,255,0.1))',
          borderBottom: '1px solid rgba(98,0,234,0.3)',
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <SparkleIcon sx={{ color: '#c084fc', fontSize: 28 }} />
          <TitleGradient>AI Message Composer</TitleGradient>
        </Box>
        <IconButton
          onClick={onClose}
          sx={{
            color: '#d1d5db',
            '&:hover': { bgcolor: 'rgba(98,0,234,0.2)', color: '#fff' },
          }}
        >
          <CloseIcon />
        </IconButton>
      </DialogTitle>

      <DialogContent sx={{ p: 3, pt: 4, pb: 2 }}>
        <FormControlLabel
          control={
            <Checkbox
              checked={preferenceChecked}
              onChange={(e) => setPreferenceChecked(e.target.checked)}
              sx={{
                color: '#a78bfa',
                '&.Mui-checked': { color: '#c084fc' },
              }}
              disabled={isGenerating || isEditing}
            />
          }
          label={
            <Typography sx={{ color: '#d1d5db', fontWeight: 500 }}>
              Customize style / tone
            </Typography>
          }
          sx={{ mb: 3 }}
        />

        {preferenceChecked && (
          <TextField
            fullWidth
            variant="outlined"
            size="medium"
            placeholder="e.g. more polite, pirate style, very short, professional, flirty..."
            value={preferenceText}
            onChange={(e) => setPreferenceText(e.target.value)}
            disabled={isGenerating || isEditing}
            sx={{
              mb: 4,
              '& .MuiOutlinedInput-root': {
                bgcolor: 'rgba(30,30,50,0.6)',
                color: '#e0e0ff',
                borderRadius: 12,
                '& fieldset': { borderColor: 'rgba(98,0,234,0.4)' },
                '&:hover fieldset': { borderColor: '#a78bfa' },
                '&.Mui-focused fieldset': { borderColor: '#c084fc' },
              },
              '& .MuiInputBase-input': { color: '#f0f0ff' },
              '& .MuiInputLabel-root': { color: '#a0a0c0' },
            }}
          />
        )}

        <Box
          sx={{
            minHeight: 160,
            p: 3,
            borderRadius: 16,
            background: 'rgba(20,20,40,0.7)',
            border: '1px solid rgba(98,0,234,0.3)',
            backdropFilter: 'blur(8px)',
            boxShadow: 'inset 0 2px 10px rgba(0,0,0,0.4)',
            position: 'relative',
            overflow: 'hidden',
          }}
        >
          {isGenerating ? (
            <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 6 }}>
              <CircularProgress
                size={48}
                thickness={4}
                sx={{ color: '#c084fc', mb: 2 }}
              />
              <Typography sx={{ color: '#a0a0c0', fontWeight: 500 }}>
                AI is thinking...
              </Typography>
            </Box>
          ) : isEditing ? (
            <TextField
              fullWidth
              multiline
              minRows={5}
              maxRows={12}
              variant="outlined"
              value={editedText}
              onChange={(e) => setEditedText(e.target.value)}
              autoFocus
              sx={{
                '& .MuiOutlinedInput-root': {
                  bgcolor: 'rgba(30,30,50,0.6)',
                  color: '#f0f0ff',
                  borderRadius: 12,
                  '& fieldset': { borderColor: 'rgba(98,0,234,0.4)' },
                  '&:hover fieldset': { borderColor: '#a78bfa' },
                  '&.Mui-focused fieldset': { borderColor: '#c084fc' },
                },
              }}
            />
          ) : generatedText ? (
            <Typography
              sx={{
                whiteSpace: 'pre-wrap',
                color: '#f0f0ff',
                lineHeight: 1.6,
                fontSize: '1.05rem',
              }}
            >
              {generatedText}
            </Typography>
          ) : (
            <Box sx={{ textAlign: 'center', py: 8, color: '#a0a0c0' }}>
              <SparkleIcon sx={{ fontSize: 48, color: '#c084fc', mb: 2, opacity: 0.7 }} />
              <Typography variant="body1" sx={{ fontWeight: 500 }}>
                Generate a smart, context-aware reply
              </Typography>
              <Typography variant="body2" sx={{ mt: 1, opacity: 0.7 }}>
                Click "Generate" to get started
              </Typography>
            </Box>
          )}
        </Box>
      </DialogContent>

      <DialogActions
        sx={{
          px: 3,
          pb: 3,
          pt: 1,
          gap: 2,
          background: 'rgba(15,12,41,0.6)',
          borderTop: '1px solid rgba(98,0,234,0.25)',
        }}
      >
        {generatedText && !isEditing && (
          <>
            <IconButton
              onClick={handleCopy}
              title="Copy to clipboard"
              sx={{
                color: '#c084fc',
                '&:hover': { bgcolor: 'rgba(98,0,234,0.15)' },
              }}
            >
              <CopyIcon />
            </IconButton>

            <Button
              onClick={handleStartEdit}
              startIcon={<EditIcon />}
              sx={{
                color: '#c084fc',
                borderColor: 'rgba(98,0,234,0.4)',
                '&:hover': { borderColor: '#c084fc', bgcolor: 'rgba(98,0,234,0.1)' },
              }}
              variant="outlined"
            >
              Edit
            </Button>

            <Button
              variant="contained"
              startIcon={<SendIcon />}
              onClick={handleSendDirect}
              sx={{
                background: 'linear-gradient(90deg, #7c3aed, #c084fc)',
                '&:hover': {
                  background: 'linear-gradient(90deg, #6d28d9, #a855f7)',
                  boxShadow: '0 0 20px rgba(192,132,252,0.4)',
                },
              }}
            >
              Send Now
            </Button>
          </>
        )}

        {isEditing && (
          <>
            <Button
              onClick={handleCancelEdit}
              sx={{ color: '#d1d5db' }}
            >
              Cancel
            </Button>

            <Button
              variant="contained"
              startIcon={<SendIcon />}
              onClick={handleSendEdited}
              sx={{
                background: 'linear-gradient(90deg, #7c3aed, #c084fc)',
                '&:hover': {
                  background: 'linear-gradient(90deg, #6d28d9, #a855f7)',
                },
              }}
            >
              Send Edited
            </Button>
          </>
        )}

        {!generatedText && !isEditing && (
          <Button
            variant="contained"
            fullWidth
            onClick={handleGenerate}
            disabled={isGenerating}
            sx={{
              py: 1.8,
              background: 'linear-gradient(90deg, #7c3aed, #c084fc)',
              borderRadius: 12,
              fontWeight: 600,
              '&:hover': {
                background: 'linear-gradient(90deg, #6d28d9, #a855f7)',
                boxShadow: '0 0 25px rgba(192,132,252,0.5)',
              },
              '&.Mui-disabled': {
                background: 'rgba(98,0,234,0.3)',
              },
            }}
          >
            {isGenerating ? (
              <>
                <CircularProgress size={20} sx={{ mr: 1.5, color: '#fff' }} />
                Generating...
              </>
            ) : (
              'Generate Smart Reply ✨'
            )}
          </Button>
        )}

        <Button
          onClick={onClose}
          sx={{
            color: '#d1d5db',
            '&:hover': { bgcolor: 'rgba(98,0,234,0.15)' },
          }}
        >
          Close
        </Button>
      </DialogActions>
    </StyledDialog>
  );
};

export default AiMessageGeneratorDialog;