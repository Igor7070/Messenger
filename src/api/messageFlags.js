// Статусы доставки и прочтения сообщения.
//
// Сервер отдаёт их полями `read_status` и `delivered_status` (так названы
// колонки в базе), а веб-клиент читал `read` и `delivered` — то есть всегда
// `undefined`. Из-за этого сообщения считались непрочитанными навсегда,
// счётчик в списке не гас, а отметка «прочитано» вообще не отправлялась:
// условие на неё проверяло `=== false`, чего у `undefined` не бывает.
//
// Android эти поля сопоставляет правильно, поэтому ошибка была только в вебе.
// Здесь принимаем оба написания: старое на случай, если где-то сообщение
// собрано на клиенте, и серверное.

export const isReadFlag = (message) =>
  Boolean(message && (message.read_status === true || message.read === true));

export const isDeliveredFlag = (message) =>
  Boolean(message && (message.delivered_status === true || message.delivered === true));
