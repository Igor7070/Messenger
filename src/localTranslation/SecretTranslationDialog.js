import React, { useEffect, useState } from 'react';
import {
  SUPPORTED, DIRECTION_SIZE_MB, isEnabled, targetLanguage, saveSettings,
  directionsForTarget, isDirectionLoaded, loadForTarget, isTargetCached, onProgress,
} from './localTranslator';

/**
 * Настройки перевода на устройстве для секретного чата (веб).
 * Один выбор — «Translate to»; исходный язык определяется автоматически.
 * Модели скачиваются по явному согласию, с прогрессом.
 */
const SecretTranslationDialog = ({ chatId, onClose, onSaved }) => {
  const [enabled, setEnabled] = useState(isEnabled(chatId));
  const [target, setTarget] = useState(targetLanguage(chatId) || 'en');
  const [status, setStatus] = useState('');
  const [ready, setReady] = useState(false);
  const [cached, setCached] = useState(false);
  const [loading, setLoading] = useState(false);
  const [progress, setProgress] = useState(null);

  const dirs = directionsForTarget(target);
  const missing = dirs.filter((d) => !isDirectionLoaded(d));

  useEffect(() => {
    let alive = true;
    setReady(missing.length === 0);
    isTargetCached(target).then((c) => { if (alive) setCached(c); });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target]);

  useEffect(() => onProgress((m) => {
    if (m.info?.status === 'progress' && typeof m.info.progress === 'number') {
      setProgress({ dir: `${m.src}>${m.tgt}`, file: m.info.file, pct: Math.round(m.info.progress) });
    }
  }), []);

  const download = async () => {
    setLoading(true);
    setStatus(cached ? 'Loading models from browser cache…' : 'Downloading language models…');
    try {
      await loadForTarget(target);
      setReady(true);
      setCached(true);
      setStatus('Language models are ready — translation works in this browser.');
    } catch (e) {
      setStatus(`Download failed: ${e.message}`);
    } finally {
      setLoading(false);
      setProgress(null);
    }
  };

  const save = () => {
    saveSettings(chatId, enabled, target);
    onSaved && onSaved(enabled, target);
    onClose && onClose();
  };

  const sizeMb = missing.length * DIRECTION_SIZE_MB;

  return (
    <div className="secret-translate-overlay" onClick={onClose}>
      <div className="secret-translate-dialog" onClick={(e) => e.stopPropagation()}>
        <h3>On-device translation</h3>
        <p className="secret-translate-note">
          Secret chat: translation runs in this browser. Messages are never sent to a translation server.
        </p>

        <label className="secret-translate-row">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          <span>Enable translation</span>
        </label>

        <label className="secret-translate-row secret-translate-col">
          <span className="secret-translate-label">Translate to</span>
          <select value={target} onChange={(e) => setTarget(e.target.value)} disabled={loading}>
            {SUPPORTED.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
          </select>
        </label>

        <div className="secret-translate-status">
          {ready ? (
            <>Language models are loaded ✓ — translation works offline.</>
          ) : cached ? (
            <>Models are in the browser cache. Click <b>Load</b> to activate (no download needed).</>
          ) : (
            <>Language models needed: {missing.map((d) => d.replace('>', '→')).join(', ')} (~{sizeMb} MB, downloaded once).
              Your messages are never sent anywhere for translation.</>
          )}
          {status && <div className="secret-translate-substatus">{status}</div>}
          {progress && (
            <div className="secret-translate-progress">
              <div className="secret-translate-progress-bar" style={{ width: `${progress.pct}%` }} />
              <span>{progress.dir} · {progress.pct}%</span>
            </div>
          )}
        </div>

        <div className="secret-translate-actions">
          {!ready && (
            <button className="secret-translate-btn" onClick={download} disabled={loading}>
              {loading ? 'Loading…' : cached ? 'Load models' : 'Download language models'}
            </button>
          )}
          <button className="secret-translate-btn secret-translate-btn-ghost" onClick={onClose}>Cancel</button>
          <button className="secret-translate-btn secret-translate-btn-primary" onClick={save} disabled={enabled && !ready}>
            Save
          </button>
        </div>
      </div>
    </div>
  );
};

export default SecretTranslationDialog;
