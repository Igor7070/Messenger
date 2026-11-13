// Звуковая волна голосового сообщения: громкость записи по кусочкам.
//
// Считается один раз — запись скачивается, раскодируется и превращается в
// шесть десятков чисел от 0 до 100, — и дальше живёт в памяти вкладки и в
// localStorage. Иначе волна пересчитывалась бы при каждой перерисовке ленты.

const BARS = 64;
const KEY = (url) => `waveform_${url}`;

const memory = new Map();
const inFlight = new Map();

/** Готовая волна или null, если посчитать не вышло. */
export const getWaveform = async (url) => {
  if (!url) return null;
  if (memory.has(url)) return memory.get(url);

  try {
    const stored = localStorage.getItem(KEY(url));
    if (stored) {
      const levels = JSON.parse(stored);
      if (Array.isArray(levels) && levels.length) {
        memory.set(url, levels);
        return levels;
      }
    }
  } catch (error) {
    // Разбитая запись в хранилище — не повод падать, посчитаем заново
  }

  if (inFlight.has(url)) return inFlight.get(url);

  const task = decode(url)
    .then((levels) => {
      inFlight.delete(url);
      if (!levels) return null;
      memory.set(url, levels);
      try {
        localStorage.setItem(KEY(url), JSON.stringify(levels));
      } catch (error) {
        // Хранилище переполнено — волна останется только в памяти вкладки
      }
      return levels;
    })
    .catch(() => {
      inFlight.delete(url);
      return null;
    });

  inFlight.set(url, task);
  return task;
};

/** Забыть волну: запись пропала или заменилась. */
export const forgetWaveform = (url) => {
  memory.delete(url);
  try {
    localStorage.removeItem(KEY(url));
  } catch (error) {
    // не страшно
  }
};

async function decode(url) {
  const response = await fetch(url);
  if (!response.ok) return null;
  const bytes = await response.arrayBuffer();

  const Ctx = window.AudioContext || window.webkitAudioContext;
  if (!Ctx) return null;
  const context = new Ctx();
  try {
    const audio = await context.decodeAudioData(bytes);
    const samples = audio.getChannelData(0);
    const peaks = [];
    for (let i = 0; i < BARS; i++) {
      const from = Math.floor((i * samples.length) / BARS);
      const to = Math.max(from + 1, Math.floor(((i + 1) * samples.length) / BARS));
      let peak = 0;
      for (let j = from; j < to; j++) {
        const value = Math.abs(samples[j]);
        if (value > peak) peak = value;
      }
      peaks.push(peak);
    }
    return shrink(peaks);
  } finally {
    if (context.close) context.close();
  }
}

/** Приводит пики к шкале 0..100: тихая запись тоже должна быть волной. */
function shrink(peaks) {
  if (!peaks.length) return null;
  // Равняемся не на самый громкий всплеск, а на уровень погромче среднего:
  // один щелчок в микрофон иначе прижимал бы всю речь к низу
  const sorted = [...peaks].sort((a, b) => a - b);
  let loud = sorted[Math.floor(sorted.length * 0.85)] || 0;
  const loudest = sorted[sorted.length - 1] || 0;
  if (loud * 4 < loudest) loud = loudest / 4;
  if (!loud) return null;
  return peaks.map((peak) => {
    const share = Math.pow(Math.min(1, peak / loud), 0.8);
    return Math.max(6, Math.min(100, Math.round(share * 100)));
  });
}
