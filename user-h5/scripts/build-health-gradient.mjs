import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const appWxss = fs.readFileSync(path.join(root, 'app.wxss'), 'utf8');
const output = path.join(root, 'assets', 'images', '3x', 'health-gradient-bg.png');
const width = 6;
const height = 1024;

function readHex(token) {
  const match = appWxss.match(new RegExp(`${token}\\s*:\\s*#([0-9a-fA-F]{6})`));
  if (!match) throw new Error(`缺少颜色 token: ${token}`);
  return [0, 2, 4].map(offset => Number.parseInt(match[1].slice(offset, offset + 2), 16));
}

const stops = [
  { at: 0, color: readHex('--health-gradient-top') },
  { at: 0.3, color: readHex('--health-gradient-mid') },
  { at: 0.52, color: readHex('--page-bg') },
  { at: 1, color: readHex('--page-bg') }
];

function colorAt(ratio) {
  const value = Math.min(1, Math.max(0, ratio));
  let start = stops[0];
  let end = stops[stops.length - 1];
  for (let index = 0; index < stops.length - 1; index += 1) {
    if (value >= stops[index].at && value <= stops[index + 1].at) {
      start = stops[index];
      end = stops[index + 1];
      break;
    }
  }
  const span = end.at - start.at;
  const progress = span ? (value - start.at) / span : 0;
  return start.color.map((channel, index) => Math.round(channel + (end.color[index] - channel) * progress));
}

const crcTable = Array.from({ length: 256 }, (_, index) => {
  let value = index;
  for (let bit = 0; bit < 8; bit += 1) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  return value >>> 0;
});

function crc32(buffer) {
  let value = 0xffffffff;
  for (const byte of buffer) value = crcTable[(value ^ byte) & 0xff] ^ (value >>> 8);
  return (value ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const typeBuffer = Buffer.from(type, 'ascii');
  const result = Buffer.alloc(12 + data.length);
  result.writeUInt32BE(data.length, 0);
  typeBuffer.copy(result, 4);
  data.copy(result, 8);
  result.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])), 8 + data.length);
  return result;
}

function encodePng() {
  const scanlineLength = width * 3 + 1;
  const raw = Buffer.alloc(scanlineLength * height);
  for (let y = 0; y < height; y += 1) {
    const rowOffset = y * scanlineLength;
    raw[rowOffset] = 0;
    const color = colorAt(y / (height - 1));
    for (let x = 0; x < width; x += 1) {
      const pixelOffset = rowOffset + 1 + x * 3;
      raw[pixelOffset] = color[0];
      raw[pixelOffset + 1] = color[1];
      raw[pixelOffset + 2] = color[2];
    }
  }

  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 2;
  header[10] = 0;
  header[11] = 0;
  header[12] = 0;

  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  return Buffer.concat([
    signature,
    chunk('IHDR', header),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]);
}

fs.mkdirSync(path.dirname(output), { recursive: true });
const image = encodePng();
fs.writeFileSync(output, image);
if (image.length > 16 * 1024) throw new Error(`渐变背景图过大: ${image.length} bytes`);
console.log(`舌诊渐变背景生成完成: ${width}x${height}, ${image.length} bytes`);
