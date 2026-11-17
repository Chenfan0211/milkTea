export const MAX_BANNER_BYTES = 1024 * 1024;
const TARGET_UPLOAD_BYTES = 800 * 1024;
const MAX_SOURCE_BYTES = 5 * 1024 * 1024;
const QUALITIES = [0.9, 0.82, 0.74, 0.66, 0.58];

interface BannerImageOptions {
  maxWidth: number;
  maxHeight: number;
  minWidth?: number;
  minHeight?: number;
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

function encodeJpeg(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(blob => (blob ? resolve(blob) : reject(new Error('图片压缩失败'))), 'image/jpeg', quality);
  });
}

export async function compressBannerImage(file: File, options: BannerImageOptions): Promise<File> {
  const name = file.name.toLowerCase();
  if (!/\.(jpe?g|png)$/.test(name) || (file.type && !['image/jpeg', 'image/png'].includes(file.type))) {
    throw new Error('仅支持 JPG 或 PNG 图片');
  }
  if (file.size > MAX_SOURCE_BYTES) throw new Error('原图不能超过 5MB');

  const image = await loadImage(file);
  if (image.naturalWidth < (options.minWidth || 0) || image.naturalHeight < (options.minHeight || 0)) {
    throw new Error(`图片宽至少 ${options.minWidth}px、高至少 ${options.minHeight}px`);
  }

  const ratio = Math.min(1, options.maxWidth / image.naturalWidth, options.maxHeight / image.naturalHeight);
  let width = Math.max(1, Math.floor(image.naturalWidth * ratio));
  let height = Math.max(1, Math.floor(image.naturalHeight * ratio));
  const canvas = document.createElement('canvas');
  const context = canvas.getContext('2d');
  if (!context) throw new Error('浏览器不支持图片压缩');

  for (let scale = 0; scale < 6; scale += 1) {
    canvas.width = width;
    canvas.height = height;
    context.fillStyle = '#fff';
    context.fillRect(0, 0, width, height);
    context.drawImage(image, 0, 0, width, height);
    for (const quality of QUALITIES) {
      const blob = await encodeJpeg(canvas, quality);
      if (blob.size <= TARGET_UPLOAD_BYTES) {
        return new File([blob], file.name.replace(/\.[^.]+$/, '.jpg'), { type: 'image/jpeg' });
      }
    }
    width = Math.max(1, Math.floor(width * 0.8));
    height = Math.max(1, Math.floor(height * 0.8));
  }
  throw new Error('图片压缩后仍超过 1MB，请换一张图片');
}
