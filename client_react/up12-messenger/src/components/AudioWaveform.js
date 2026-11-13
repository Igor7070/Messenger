import React, { useEffect, useState } from 'react';
import { getWaveform } from '../api/waveforms';

/**
 * Звуковая волна голосового сообщения.
 *
 * Вместо полоски с бегунком — столбики по громкости записи: паузы лежат
 * низко, речь поднимается, прослушанная часть подсвечена. Перемотка та же:
 * клик и протяжка отдаются наружу, как у прежней дорожки.
 */
const BARS = 44;

const AudioWaveform = ({ url, progress = 0, onClick, onMouseDown, style }) => {
  const [levels, setLevels] = useState(null);

  useEffect(() => {
    let alive = true;
    setLevels(null);
    getWaveform(url).then((data) => {
      if (alive && data) setLevels(data);
    });
    return () => {
      alive = false;
    };
  }, [url]);

  const bars = [];
  for (let i = 0; i < BARS; i++) {
    // Пока волна не посчитана — ровная полоска, а не пустое место
    let level = 22;
    if (levels && levels.length) {
      const from = Math.floor((i * levels.length) / BARS);
      const to = Math.max(from + 1, Math.floor(((i + 1) * levels.length) / BARS));
      let sum = 0;
      for (let j = from; j < to && j < levels.length; j++) sum += levels[j];
      level = Math.max(8, Math.round(sum / (to - from)));
    }
    // Столбик считается прослушанным по своей середине — так полоса растёт
    // ровно и не мигает на границе
    const played = (i + 0.5) / BARS <= progress;
    bars.push(
      <span
        key={i}
        className={`audio-wave-bar${played ? ' played' : ''}`}
        style={{ height: `${level}%` }}
      />
    );
  }

  return (
    <div className="audio-waveform" onClick={onClick} onMouseDown={onMouseDown} style={style}>
      {bars}
    </div>
  );
};

export default AudioWaveform;
