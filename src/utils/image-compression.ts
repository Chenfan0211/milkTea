export const MAX_ADMIN_IMAGE_BYTES = 500 * 1024;
export const MAX_ADMIN_SOURCE_BYTES = 5 * 1024 * 1024;

const QUALITIES = [0.92, 0.84, 0.76, 0.68, 0.6, 0.52];
const DIMENSION_FACTORS = [1, 0.9, 0.8, 0.7];

export interface AdminImageCompressionOptions {
  maxWidth?: number;
  maxHeight?: number;
  minWidth?: number;
  minHeight?: number;
  targetBytes?: number;
}

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(file);
    const image = new Image();
    image.addEventListener(
      'load',
      () => {
        URL.revokeObjectURL(objectUrl);
        resolve(image);
      },
      { once: true }
    );
    image.addEventListener(
      'error',
      () => {
        URL.revokeObjectURL(objectUrl);
        reject(new Error('图片无法读取'));
      },
      { once: true }
    );
    image.src = objectUrl;
  });
}

function encodeCanvas(canvas: HTMLCanvasElement, mimeType: string, quality?: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(blob => (blob ? resolve(blob) : reject(new Error('图片压缩失败'))), mimeType, quality);
  });
}

function hasTransparentPixels(context: CanvasRenderingContext2D, width: number, height: number) {
  if (typeof context.getImageData !== 'function') return false;
  const pixels = context.getImageData(0, 0, width, height).data;
  for (let offset = 3; offset < pixels.length; offset += 4) {
    if (pixels[offset] < 255) return true;
  }
  return false;
}

export async function compressAdminImage(file: File, options: AdminImageCompressionOptions = {}): Promise<File> {
  const name = file.name.toLowerCase();
  const validType = ['image/jpeg', 'image/png'].includes(file.type);
  const validExtension = /\.(jpe?g|png)$/.test(name);
  if (!validType && !validExtension) throw new Error('仅支持 JPG 或 PNG 图片');
  if (file.size > MAX_ADMIN_SOURCE_BYTES) throw new Error('原图不能超过 5MB');

  const targetBytes = options.targetBytes ?? MAX_ADMIN_IMAGE_BYTES;
  if (file.size <= targetBytes && !options.maxWidth && !options.maxHeight) return file;

  const image = await loadImage(file);
  if (image.naturalWidth < (options.minWidth || 0) || image.naturalHeight < (options.minHeight || 0)) {
    throw new Error(`图片宽至少 ${options.minWidth}px、高至少 ${options.minHeight}px`);
  }

  const ratio = Math.min(
    1,
    options.maxWidth ? options.maxWidth / image.naturalWidth : 1,
    options.maxHeight ? options.maxHeight / image.naturalHeight : 1
  );
  const baseWidth = Math.max(1, Math.floor(image.naturalWidth * ratio));
  const baseHeight = Math.max(1, Math.floor(image.naturalHeight * ratio));
  if (file.size <= targetBytes && ratio === 1) return file;

  const canvas = document.createElement('canvas');
  const context = canvas.getContext('2d');
  if (!context) throw new Error('浏览器不支持图片压缩');

  canvas.width = baseWidth;
  canvas.height = baseHeight;
  context.drawImage(image, 0, 0, baseWidth, baseHeight);
  const preservePng = file.type === 'image/png' && hasTransparentPixels(context, baseWidth, baseHeight);
  const mimeType = preservePng ? 'image/png' : 'image/jpeg';
  const qualities = preservePng ? [undefined] : QUALITIES;

  for (const factor of DIMENSION_FACTORS) {
    const width = Math.max(1, Math.floor(baseWidth * factor));
    const height = Math.max(1, Math.floor(baseHeight * factor));
    canvas.width = width;
    canvas.height = height;
    if (!preservePng) {
      context.fillStyle = '#fff';
      context.fillRect(0, 0, width, height);
    }
    context.drawImage(image, 0, 0, width, height);

    for (const quality of qualities) {
      const blob = await encodeCanvas(canvas, mimeType, quality);
      if (blob.size <= targetBytes) {
        const extension = preservePng ? '.png' : '.jpg';
        return new File([blob], file.name.replace(/\.[^.]+$/, extension), { type: mimeType });
      }
    }
  }

  throw new Error('图片压缩后仍超过 500KB，请换一张图片');
}
