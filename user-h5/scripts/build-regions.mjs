import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const areaData = require('china-area-data');
const provinceMap = areaData['86'] || {};

const regions = Object.keys(provinceMap).map(provinceCode => ({
  code: provinceCode,
  name: provinceMap[provinceCode],
  children: Object.keys(areaData[provinceCode] || {}).map(cityCode => ({
    code: cityCode,
    name: areaData[provinceCode][cityCode],
    children: Object.keys(areaData[cityCode] || {}).map(districtCode => ({
      code: districtCode,
      name: areaData[cityCode][districtCode]
    }))
  }))
}));

const output = `// Generated from china-area-data@5.0.1 (MIT). Run npm run build:regions to update.\nmodule.exports = ${JSON.stringify(regions)}\n`;
const outputPath = path.join(root, 'data/regions.js');
fs.writeFileSync(outputPath, output, 'utf8');
console.log(`行政区数据生成完成: ${regions.length} 个省级地区 -> ${path.relative(root, outputPath)}`);
