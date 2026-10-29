/* eslint-disable no-restricted-globals */
// Web Worker локального перевода (Transformers.js + OPUS-MT).
//
// Работает целиком в браузере: модель скачивается один раз с нашего сайта
// (public/models/…), кэшируется браузером и дальше используется офлайн.
// Текст сообщений никуда не отправляется — это условие, при котором перевод
// в секретном чате не ломает сквозное шифрование.

import { pipeline, env } from '@huggingface/transformers';

// Модели берём только с нашего хостинга, не с Hugging Face
env.allowRemoteModels = false;
env.allowLocalModels = true;
env.localModelPath = self.__MODELS_BASE__ || '/Messenger/models/';
env.useBrowserCache = true;

// WASM-бэкенд ONNX Runtime — с CDN (единственная внешняя загрузка, и это код, не данные)
env.backends.onnx.wasm.wasmPaths =
  'https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.8.1/dist/';

const pipelines = new Map(); // "ru>en" -> pipeline

const modelId = (src, tgt) => `opus-mt-${src}-${tgt}`;

async function getPipeline(src, tgt, onProgress) {
  const key = `${src}>${tgt}`;
  if (pipelines.has(key)) return pipelines.get(key);
  const p = pipeline('translation', modelId(src, tgt), {
    dtype: 'q8',
    progress_callback: (info) => {
      if (onProgress && (info.status === 'progress' || info.status === 'done' || info.status === 'initiate')) {
        onProgress(info);
      }
    },
  });
  pipelines.set(key, p);
  try {
    return await p;
  } catch (e) {
    pipelines.delete(key);
    throw e;
  }
}

self.onmessage = async (e) => {
  const { id, type, src, tgt, text, base } = e.data || {};
  if (base) env.localModelPath = base;
  try {
    if (type === 'load') {
      await getPipeline(src, tgt, (info) => self.postMessage({ id, type: 'progress', src, tgt, info }));
      self.postMessage({ id, type: 'loaded', src, tgt });
    } else if (type === 'translate') {
      const p = await getPipeline(src, tgt);
      const out = await p(text, { max_new_tokens: 512 });
      const translated = Array.isArray(out) ? out[0]?.translation_text : out?.translation_text;
      self.postMessage({ id, type: 'result', translated: translated || '' });
    }
  } catch (err) {
    self.postMessage({ id, type: 'error', error: String(err && err.message ? err.message : err) });
  }
};
