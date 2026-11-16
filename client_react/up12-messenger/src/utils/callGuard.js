import * as sodium from 'libsodium-wrappers';

// Подпись звонка: подтверждаем, что по ту сторону именно собеседник.
//
// Звук и видео шифрует сам WebRTC, сервер их не слышит. Открытой оставалась
// установка связи: тот, кто передаёт служебные сообщения, мог подменить ключи
// и слушать разговор. Поэтому каждая сторона подписывает своё описание
// соединения ключом Ed25519, а другая проверяет подпись.
//
// Ключ заводится на каждое устройство отдельно — иначе телефон и браузер
// затирали бы ключи друг друга, и собеседник видел бы «ключ сменился» при
// каждом переходе с одного на другое.
//
// Ключ знакомого устройства запоминается: новое устройство — спокойная
// отметка, тот же ключ — подтверждено, другой ключ у знакомого устройства —
// предупреждение. Сервер раздаёт ключи, поэтому его враньё ловится не
// подписью, а кодом из обоих ключей: он одинаков у обеих сторон, и сверенный
// вслух код запоминается как «подтверждено вами».

const API = 'https://unlimitedpossibilities12.org/api/sign_keys';
const DEVICES_API = 'https://unlimitedpossibilities12.org/api/device_keys';
const TRUST_PREFIX = 'call_trust_';

export const CallSecurity = {
  CHECKING: 'checking',
  ENCRYPTED: 'encrypted',
  NEW_DEVICE: 'new-device',
  VERIFIED: 'verified',
  CONFIRMED: 'confirmed',
  CHANGED: 'changed',
  FAILED: 'failed',
};

// Буквы кода: тот же набор, что и в приложении на телефоне
const EMOJI = [
  '🐶', '🐱', '🦊', '🐻', '🐼', '🐨', '🦁', '🐷',
  '🐸', '🐵', '🦉', '🦆', '🐢', '🐳', '🐬', '🐝',
  '🦋', '🌻', '🌲', '🍏', '🍋', '🍓', '🍉', '🍇',
  '🥕', '🍄', '🍞', '🧀', '🍕', '🍔', '🍟', '🍩',
  '🍪', '☕', '🍵', '⚽', '🏀', '🎾', '🎸', '🎺',
  '🎻', '🥁', '🚗', '🚕', '🚲', '✈', '🚀', '⛵',
  '⌚', '💡', '🔑', '🔔', '🎁', '📷', '📚', '✏',
  '⭐', '🌈', '☀', '🌙', '❄', '🔥', '💧', '🍀',
];

const read = (key) => {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
};

const write = (key, value) => {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* приватное окно — обойдёмся без памяти */
  }
};

/** Постоянная метка устройства; живёт столько же, сколько данные сайта. */
const deviceIdFor = () => {
  let id = read('call_device_id');
  if (!id) {
    id = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now() + Math.random())).replace(/-/g, '');
    write('call_device_id', id);
  }
  return id;
};

export async function createCallGuard(currentUserId, partnerId) {
  await sodium.ready;

  const deviceId = deviceIdFor();
  const storageKey = `secure_sign_${currentUserId}`;
  let pair = null;
  try {
    pair = JSON.parse(read(storageKey) || 'null');
    // Ключи прежнего «защищённого звонка» подходят: берём их, чтобы у тех,
    // кто им пользовался, код не сменился на пустом месте
    if (!pair) {
      const legacy = JSON.parse(read(`signKeyPair_${currentUserId}`) || 'null');
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
    write(storageKey, JSON.stringify(pair));
  }

  // Свой ключ кладём на сервер: по нему собеседник нас проверит
  const publish = async (url, body) => {
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!response.ok) console.warn('Ключ не принят сервером:', url, response.status);
    } catch (error) {
      console.warn('Ключ не ушёл на сервер:', url, error);
    }
  };
  await publish(DEVICES_API, { userId: String(currentUserId), deviceId, publicKey: pair.publicKey });
  // Прежний общий слот — для ещё не обновлённых собеседников
  publish(API, { userId: String(currentUserId), publicKey: pair.publicKey });

  // Устройства собеседника
  const partnerDevices = new Map();
  try {
    const response = await fetch(`${DEVICES_API}/${partnerId}`);
    if (response.ok) {
      const devices = await response.json();
      devices.forEach((device) => partnerDevices.set(device.deviceId, device.publicKey));
    }
  } catch {
    /* список не пришёл — звонок пойдёт как неподтверждённый */
  }
  if (partnerDevices.size === 0) {
    try {
      const response = await fetch(`${API}/${partnerId}`);
      if (response.ok) {
        const data = await response.json();
        if (data.publicKey) partnerDevices.set('legacy', data.publicKey);
      }
    } catch {
      /* ключа нет вовсе */
    }
  }

  let state = CallSecurity.CHECKING;
  let partnerPublicKey = null;
  let partnerDeviceId = null;

  const trustKey = () => `${TRUST_PREFIX}key_${partnerId}_${partnerDeviceId}`;
  const confirmedKey = () => `${TRUST_PREFIX}confirmed_${partnerId}_${partnerDeviceId}`;

  const sign = (sdp) => {
    try {
      return sodium.to_base64(
        sodium.crypto_sign_detached(sodium.from_string(sdp), sodium.from_base64(pair.privateKey))
      );
    } catch (error) {
      console.warn('Не удалось подписать описание соединения:', error);
      return null;
    }
  };

  // Что мы знали об этом устройстве раньше: запомненный ключ и есть защита
  // от сервера — подменить ключ он может только один раз, и это будет видно
  const rememberAndJudge = (keyB64) => {
    const known = read(trustKey());
    if (!known) {
      write(trustKey(), keyB64);
      return CallSecurity.NEW_DEVICE;
    }
    if (known !== keyB64) return CallSecurity.CHANGED;
    return read(confirmedKey()) === 'true' ? CallSecurity.CONFIRMED : CallSecurity.VERIFIED;
  };

  const verify = (sdp, signature, senderDeviceId) => {
    if (!signature) {
      state = CallSecurity.ENCRYPTED;
      return state;
    }
    let device = senderDeviceId;
    let keyB64 = device ? partnerDevices.get(device) : null;
    if (!keyB64 && partnerDevices.size === 1) {
      // Старый клиент без метки устройства — у него ключ один
      [device] = [...partnerDevices.keys()];
      keyB64 = partnerDevices.get(device);
    }
    if (!keyB64) {
      state = CallSecurity.ENCRYPTED;
      return state;
    }
    try {
      partnerPublicKey = keyB64;
      partnerDeviceId = device;
      const valid = sodium.crypto_sign_verify_detached(
        sodium.from_base64(signature),
        sodium.from_string(sdp),
        sodium.from_base64(keyB64)
      );
      state = valid ? rememberAndJudge(keyB64) : CallSecurity.FAILED;
    } catch (error) {
      console.warn('Подпись не разобрана:', error);
      state = CallSecurity.FAILED;
    }
    return state;
  };

  const fingerprintHash = async () => {
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
      return new Uint8Array(await crypto.subtle.digest('SHA-256', joined));
    } catch (error) {
      return null;
    }
  };

  // Код разговора: шесть значков, одинаковых у обеих сторон
  const fingerprint = async () => {
    const hash = await fingerprintHash();
    if (!hash) return null;
    const parts = [];
    for (let i = 0; i < 6; i += 1) parts.push(EMOJI[hash[i] & 0x3f]);
    return parts.join(' ');
  };

  const fingerprintDigits = async () => {
    const hash = await fingerprintHash();
    if (!hash) return null;
    let code = '';
    for (let i = 0; i < 12; i += 1) {
      if (i > 0 && i % 4 === 0) code += ' ';
      code += String(hash[i] % 10);
    }
    return code;
  };

  /** Человек сверил код вслух: с этой минуты подмена будет видна. */
  const confirmByCode = () => {
    if (!partnerPublicKey) return;
    write(trustKey(), partnerPublicKey);
    write(confirmedKey(), 'true');
    state = CallSecurity.CONFIRMED;
  };

  /** Ключ сменился, но человек сверил код заново — принимаем новый. */
  const acceptChangedKey = () => {
    if (!partnerPublicKey) return;
    write(trustKey(), partnerPublicKey);
    write(confirmedKey(), 'false');
    state = CallSecurity.VERIFIED;
  };

  return {
    deviceId,
    sign,
    verify,
    fingerprint,
    fingerprintDigits,
    confirmByCode,
    acceptChangedKey,
    state: () => state,
  };
}
