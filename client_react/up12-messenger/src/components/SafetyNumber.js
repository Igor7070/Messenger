/**
 * Отпечаток пары ключей секретного чата («safety number»).
 *
 * Считается одинаково на обоих устройствах: SHA-256 от двух публичных ключей
 * X25519, отсортированных по userId (чтобы порядок не зависел от того, кто
 * смотрит), в виде 12 групп по 4 hex-символа. Если отпечатки у собеседников
 * совпадают — ключи не подменены по дороге (в том числе на сервере).
 *
 * Тот же алгоритм реализован в Android-клиенте (SecretPrivateChatActivity).
 *
 * Хеш берём у браузера (Web Crypto), а не у libsodium: в обычной сборке
 * libsodium-wrappers функции crypto_hash_sha256 нет (она только в «sumo»),
 * поэтому расчёт падал с ошибкой, её молча перехватывали — и панель всегда
 * показывала «ключи ещё не загружены», хотя оба ключа были на месте.
 */

/** Padding «=» в ключах на сервере не хранится; Android его тоже срезает. */
const withoutPadding = (key) => String(key).replace(/=/g, '');

export const computeSafetyNumber = async (userIdA, publicKeyA, userIdB, publicKeyB) => {
  if (!publicKeyA || !publicKeyB) return null;
  const pairs = [
    [Number(userIdA), withoutPadding(publicKeyA)],
    [Number(userIdB), withoutPadding(publicKeyB)],
  ].sort((x, y) => x[0] - y[0]);

  const material = `${pairs[0][0]}:${pairs[0][1]}|${pairs[1][0]}:${pairs[1][1]}`;
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(material));
  const hex = Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
    .toUpperCase(); // 64 символа
  return hex.slice(0, 48).match(/.{4}/g).join(' ');
};

/** Короткая версия для шапки: первые 3 группы. */
export const shortSafetyNumber = (full) => (full ? full.split(' ').slice(0, 3).join(' ') : '');
