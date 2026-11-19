/**
 * Метка сборки.
 *
 * Строка «Build …» в меню списка разговоров нужна ровно для одного: понять,
 * свежую ли страницу показывает браузер или взял её из своего кэша. Дата в
 * ней стояла вписанной руками и месяцами не менялась — строка говорила
 * неправду и пользы не приносила.
 *
 * Теперь её проставляет сама сборка: npm запускает этот файл перед
 * `react-scripts build`, и в buildStamp.js ложится время этой самой сборки.
 */
const fs = require('fs');
const path = require('path');

const now = new Date();
const two = (value) => String(value).padStart(2, '0');
const stamp = `${now.getFullYear()}-${two(now.getMonth() + 1)}-${two(now.getDate())} `
  + `${two(now.getHours())}:${two(now.getMinutes())}`;

const file = path.join(__dirname, '..', 'src', 'buildStamp.js');
const text = `// Метка сборки: видна в меню списка и в журнале браузера. Нужна, чтобы
// отличить свежую страницу от той, что браузер взял из своего кэша.
//
// Пишется сама при сборке — scripts/stamp-build.js. Руками не правим.
const BUILD_STAMP = '${stamp}';

export default BUILD_STAMP;
`;

fs.writeFileSync(file, text, 'utf8');
console.log('Метка сборки:', stamp);
