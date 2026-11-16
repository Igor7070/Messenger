import * as sodium from 'libsodium-wrappers';

// Подпись звонка: подтверждаем, что по ту сторону именно собеседник.
//
// Звук и видео в звонке шифрует сам WebRTC, сервер их не слышит. Открытой
// оставалась установка связи: тот, кто держит сигнализацию, мог подменить
// ключи и слушать разговор. Поэтому каждая сторона подписывает своё описание
// соединения ключом Ed25519, а другая проверяет подпись.
//
// Раньше это жило в отдельном «защищённом звонке», который надо было выбрать
// вручную. Теперь это есть в каждом голосовом и видеозвонке — и в браузере, и
// на телефоне, причём формат подписи у них общий.
//
// Ключ собеседника берём только у сервера: ключ из самого сообщения проверять
// бессмысленно — кто подменил сообщение, тот приложит и свой ключ. Враньё
// самого сервера ловится отпечатком, который обе стороны сверяют вслух.

const API = 'https://unlimitedpossibilities12.org/api/sign_keys';

export const CallSecurity = {
  CHECKING: 'checking',
  VERIFIED: 'verified',
  UNVERIFIED: 'unverified',
  FAILED: 'failed',
};

export async function createCallGuard(currentUserId, partnerId) {
  await sodium.ready;

  const storageKey = `secure_sign_${currentUserId}`;
  let pair = null;
  try {
    pair = JSON.parse(localStorage.getItem(storageKey) || 'null');
    // Ключи прежнего «защищённого звонка» подходят: берём их, чтобы у тех,
    // кто им пользовался, отпечаток не сменился на пустом месте
    if (!pair) {
      const legacy = JSON.parse(localStorage.getItem(`signKeyPair_${currentUserId}`) || 'null');
      if (legacy && legacy.publicKey && legacy.privateKey) pair = legacy;
    }
  } catch {
    pair = null;
  }
  if (!pair || !pair.publicKey || !pair.privateKey) {
    const keys = sodium.crypto_sign_keypair();
    pair = {
      publicKey: sodium.to_base64(keys.publicKey),
      privateKey: sodium.to_base64(keys.privateKey),
    };
    try {
      localStorage.setItem(storageKey, JSON.stringify(pair));
    } catch {
      /* ключи не сохранились — звонок всё равно подпишем */
    }
  }

  // Свой открытый ключ кладём на сервер, чтобы собеседник мог нас проверить.
  // Ждём ответа: без этого собеседник сверяет подпись со старым ключом.
  try {
    const published = await fetch(API, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: String(currentUserId), publicKey: pair.publicKey }),
    });
    if (!published.ok) {
      console.warn('Открытый ключ не принят сервером:', published.status);
    }
  } catch (error) {
    console.warn('Открытый ключ не ушёл на сервер:', error);
  }

  let partnerPublicKey = null;
  try {
    const response = await fetch(`${API}/${partnerId}`);
    if (response.ok) {
      const data = await response.json();
      partnerPublicKey = data.publicKey || null;
    }
  } catch {
    /* ключа нет — разговор пойдёт как неподтверждённый */
  }

  let state = CallSecurity.CHECKING;

  const sign = (sdp) => {
    try {
      const signature = sodium.crypto_sign_detached(
        sodium.from_string(sdp),
        sodium.from_base64(pair.privateKey)
      );
      return sodium.to_base64(signature);
    } catch (error) {
      console.warn('Не удалось подписать описание соединения:', error);
      return null;
    }
  };

  const verify = (sdp, signature) => {
    if (!signature || !partnerPublicKey) {
      state = CallSecurity.UNVERIFIED;
      return state;
    }
    try {
      const valid = sodium.crypto_sign_verify_detached(
        sodium.from_base64(signature),
        sodium.from_string(sdp),
        sodium.from_base64(partnerPublicKey)
      );
      state = valid ? CallSecurity.VERIFIED : CallSecurity.FAILED;
    } catch (error) {
      console.warn('Подпись не разобрана:', error);
      state = CallSecurity.FAILED;
    }
    return state;
  };

  // Код, одинаковый у обеих сторон: порядок ключей не зависит от того, кто звонил
  const fingerprint = async () => {
    if (!partnerPublicKey) return null;
    try {
      const mine = sodium.from_base64(pair.publicKey);
      const theirs = sodium.from_base64(partnerPublicKey);
      let first = mine;
      let second = theirs;
      for (let i = 0; i < Math.min(mine.length, theirs.length); i += 1) {
        if (mine[i] !== theirs[i]) {
          if (mine[i] > theirs[i]) {
            first = theirs;
            second = mine;
          }
          break;
        }
      }
      const joined = new Uint8Array(first.length + second.length);
      joined.set(first, 0);
      joined.set(second, first.length);
      // SHA-256 берём у браузера: в сборке libsodium его может не быть
      const digest = await crypto.subtle.digest('SHA-256', joined);
      const hash = new Uint8Array(digest);
      let code = '';
      for (let i = 0; i < 12; i += 1) {
        if (i > 0 && i % 4 === 0) code += ' ';
        code += String(hash[i] % 10);
      }
      return code;
    } catch (error) {
      return null;
    }
  };

  return {
    sign,
    verify,
    fingerprint,
    state: () => state,
    hasPartnerKey: () => Boolean(partnerPublicKey),
  };
}
