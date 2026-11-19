/**
 * Как показывать человека.
 *
 * Имя входа (то, что стоит под «@») уникально и неизменно: по нему находят,
 * им входят, из него же сложены названия личных переписок и имена файлов с
 * аватарами. Рядом с ним есть имя, которое человек выбирает себе сам, —
 * повторяться ему не запрещено, и меняется оно когда угодно. Везде, где
 * человека видно, показываем именно его, а нет своего имени — показываем имя
 * входа.
 */
export const nameOf = (person) => {
  if (!person) return '';
  const chosen = typeof person.displayName === 'string' ? person.displayName.trim() : '';
  return chosen || person.username || '';
};

/** То же, но для случаев, когда на руках только имя входа. */
export const nameOrLogin = (displayName, username) => {
  const chosen = typeof displayName === 'string' ? displayName.trim() : '';
  return chosen || username || '';
};
