import sodium from 'libsodium-wrappers';

/**
 * Отпечаток пары ключей секретного чата («safety number»).
 *
 * Считается одинаково на обоих устройствах: SHA-256 от двух публичных ключей
 * X25519, отсортированных по userId (чтобы порядок не зависел от того, кто
 * смотрит), в виде 12 групп по 4 hex-символа. Если отпечатки у собеседников
 * совпадают — ключи не подменены по дороге (в том числе на сервере).
 *
 * Тот же алгоритм реализован в Android-клиенте (SecretPrivateChatActivity).
 */
export const computeSafetyNumber = async (userIdA, publicKeyA, userIdB, publicKeyB) => {
  await sodium.ready;
  if (!publicKeyA || !publicKeyB) return null;
  const pairs = [[Number(userIdA), publicKeyA], [Number(userIdB), publicKeyB]]
    .sort((x, y) => x[0] - y[0]);
  const material = sodium.from_string(`${pairs[0][0]}:${pairs[0][1]}|${pairs[1][0]}:${pairs[1][1]}`);
  const digest = sodium.crypto_hash_sha256(material);
  const hex = sodium.to_hex(digest).toUpperCase(); // 64 символа
  return hex.slice(0, 48).match(/.{4}/g).join(' ');
};

/** Короткая версия для шапки: первые 3 группы. */
export const shortSafetyNumber = (full) => (full ? full.split(' ').slice(0, 3).join(' ') : '');
