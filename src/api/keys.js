// Ключи секретных чатов.
//
// Код переехал сюда из Home.js: вход и регистрация теперь живут на отдельном
// экране (components/Auth.js), а ключи нужны обоим. Имена записей в
// localStorage оставлены прежними, иначе уже присланные секретные сообщения
// перестали бы расшифровываться.

import { toast } from 'react-toastify';
import sodium from 'libsodium-wrappers';

const KEYSTORE_ALIAS = 'secret_chat_key';
const API = 'https://unlimitedpossibilities12.org/api';

const sendPublicKeyToServer = async (userId, publicKey) => {
  try {
    const response = await fetch(`${API}/public_keys`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId, publicKey }),
    });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`${response.status} — ${text}`);
    }
    console.log(`Public key uploaded for userId: ${userId}`);
  } catch (error) {
    console.error('Error uploading public key:', error);
    toast.error(`Failed to upload public key: ${error.message}`);
  }
};

/**
 * Пара ключей для пользователя: выпускаем один раз, дальше переиспользуем.
 *
 * Если пара уже есть, всё равно проверяем, знает ли сервер открытую часть, —
 * он мог её потерять, и тогда писать этому человеку было бы нечем.
 */
export const generateAndSaveKeys = async (userId) => {
  try {
    await sodium.ready;
    const alias = `${KEYSTORE_ALIAS}_${userId}`;
    const existingKey = localStorage.getItem(alias);

    if (!existingKey) {
      const { publicKey, privateKey } = sodium.crypto_box_keypair();
      localStorage.setItem(alias, sodium.to_base64(privateKey));
      await sendPublicKeyToServer(userId, sodium.to_base64(publicKey).trim());
      console.log(`Keys generated for userId: ${userId}`);
      return;
    }

    const response = await fetch(`${API}/public_keys/${userId}`);
    if (!response.ok) {
      const storedKeyPair = JSON.parse(localStorage.getItem(`keyPair_${userId}`) || 'null');
      if (storedKeyPair?.publicKey) await sendPublicKeyToServer(userId, storedKeyPair.publicKey);
    }
  } catch (error) {
    console.error('Error generating keys:', error);
    toast.error('Failed to initialize encryption keys');
  }
};
