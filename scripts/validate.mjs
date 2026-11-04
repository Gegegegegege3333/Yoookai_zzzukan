// 使い方: npm run validate（サンプルのうちは npm run validate -- --sample）
import { readFile } from 'node:fs/promises';
import { validate } from '../js/validate.js';

const sample = process.argv.includes('--sample');
const data = JSON.parse(await readFile(new URL('../data/yokai.json', import.meta.url), 'utf8'));
const { errors, warnings, count } = validate(data, { sample });

console.log(`妖怪 ${count} 体をチェックしました${sample ? '（サンプルモード）' : ''}`);
for (const w of warnings) console.log('  注意: ' + w);
for (const e of errors) console.log('  エラー: ' + e);
console.log(`エラー ${errors.length} 件 / 注意 ${warnings.length} 件`);
process.exit(errors.length ? 1 : 0);
