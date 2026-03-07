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
        toast.success('Message generated');
      } else {
        toast.warn('Empty response from server');
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
    toast.success('Copied');
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
      <DialogTitle sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: 'linear-gradient(135deg, #6200ea 0%, #3700b3 100%)', color: 'white' }}>
        AI Message Generator
        <IconButton onClick={onClose} sx={{ color: 'white' }}>
          <CloseIcon />
        </IconButton>
      </DialogTitle>

      <DialogContent sx={{ pt: 3, pb: 2 }}>
        <FormControlLabel
          control={<Checkbox checked={preferenceChecked} onChange={(e) => setPreferenceChecked(e.target.checked)} color="primary" disabled={isGenerating || isEditing} />}
          label="Add custom preference"
          sx={{ mb: 2 }}
        />

        {preferenceChecked && (
          <TextField
            fullWidth
            variant="outlined"
            size="small"
            placeholder="e.g. more polite / pirate style / short and direct"
            value={preferenceText}
            onChange={(e) => setPreferenceText(e.target.value)}
            disabled={isGenerating || isEditing}
            sx={{ mb: 3 }}
          />
        )}

        <Box sx={{ minHeight: 140, p: 2, borderRadius: 2, backgroundColor: generatedText ? '#f5f5f5' : 'transparent', border: generatedText ? '1px solid #ddd' : '1px dashed #ccc' }}>
          {isGenerating ? (
            <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 4 }}>
              <CircularProgress size={40} />
              <Typography sx={{ mt: 2, color: 'text.secondary' }}>Generating...</Typography>
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
            />
          ) : generatedText ? (
            <Typography sx={{ whiteSpace: 'pre-wrap' }}>{generatedText}</Typography>
          ) : (
            <Typography color="text.secondary" align="center" sx={{ py: 4 }}>
              Click "Generate" to create a smart reply
            </Typography>
          )}
        </Box>
      </DialogContent>

      <DialogActions sx={{ px: 3, pb: 3, gap: 1.5 }}>
        {generatedText && !isEditing && (
          <>
            <IconButton onClick={handleCopy} title="Copy"><CopyIcon /></IconButton>
            <Button onClick={handleStartEdit} startIcon={<EditIcon />} color="primary">Edit</Button>
            <Button variant="contained" startIcon={<SendIcon />} onClick={handleSendDirect}>Send</Button>
          </>
        )}

        {isEditing && (
          <>
            <Button onClick={handleCancelEdit} color="inherit">Cancel</Button>
            <Button variant="contained" startIcon={<SendIcon />} onClick={handleSendEdited}>Send Edited</Button>
          </>
        )}

        {!generatedText && !isEditing && (
          <Button variant="contained" color="primary" onClick={handleGenerate} disabled={isGenerating} fullWidth sx={{ py: 1.5 }}>
            {isGenerating ? 'Generating...' : 'Generate'}
          </Button>
        )}

        <Button onClick={onClose} color="inherit" sx={{ ml: 'auto' }}>Close</Button>
      </DialogActions>
    </StyledDialog>
  );
};

export default AiMessageGeneratorDialog;