import assert from 'node:assert/strict';
import test from 'node:test';
import { compressAdminImage, MAX_ADMIN_IMAGE_BYTES } from '../../utils/image-compression';
import { compressBannerImage } from './banner-image';

function installImageCanvasMock(
  outputSize: (width: number, height: number, quality: number) => number,
  sourceWidth = 3000,
  sourceHeight = 4160
) {
  const previousImage = globalThis.Image;
  const previousDocument = globalThis.document;
  const previousCreate = URL.createObjectURL;
  const previousRevoke = URL.revokeObjectURL;
  let canvasWidth = 0;
  let canvasHeight = 0;
  let revoked = false;

  class MockImage {
    naturalWidth = sourceWidth;
    naturalHeight = sourceHeight;
    private listeners: Record<string, () => void> = {};
    addEventListener(type: string, listener: () => void) {
      this.listeners[type] = listener;
    }
    private dispatch(type: string) {
      this.listeners[type]?.();
    }
    set src(_value: string) {
      queueMicrotask(() => this.dispatch('load'));
    }
  }
  const canvas = {
    width: 0,
    height: 0,
    getContext: () => ({ fillStyle: '', fillRect: () => {}, drawImage: () => {} }),
    toBlob(callback: (blob: Blob | null) => void, _mime: string, quality: number) {
      canvasWidth = this.width;
      canvasHeight = this.height;
      callback(new Blob([new Uint8Array(outputSize(this.width, this.height, quality))], { type: 'image/jpeg' }));
    }
  };
  globalThis.Image = MockImage as unknown as typeof Image;
  globalThis.document = { createElement: () => canvas } as unknown as Document;
  URL.createObjectURL = () => 'blob:test';
  URL.revokeObjectURL = () => {
    revoked = true;
  };
  return {
    getSize: () => ({ width: canvasWidth, height: canvasHeight }),
    wasRevoked: () => revoked,
    restore() {
      globalThis.Image = previousImage;
      globalThis.document = previousDocument;
      URL.createObjectURL = previousCreate;
      URL.revokeObjectURL = previousRevoke;
    }
  };
}

test('首页大 PNG 缩到尺寸范围内并作为不超过 500KB 的 JPG 上传', async () => {
  const mock = installImageCanvasMock((width, height, quality) => Math.round(width * height * quality * 0.4));
  try {
    const source = new File([new Uint8Array(2 * 1024 * 1024)], 'banner.png', { type: 'image/png' });
    const result = await compressBannerImage(source, {
      maxWidth: 1500,
      maxHeight: 2080,
      minWidth: 750,
      minHeight: 1040
    });
    assert.equal(result.type, 'image/jpeg');
    assert.match(result.name, /\.jpg$/);
    assert.ok(result.size <= MAX_ADMIN_IMAGE_BYTES);
    assert.ok(mock.getSize().width <= 1500);
    assert.ok(mock.getSize().height <= 2080);
    assert.ok(mock.getSize().width >= 1050);
    assert.ok(mock.getSize().height >= 1456);
    assert.equal(mock.wasRevoked(), true);
  } finally {
    mock.restore();
  }
});

test('我的页图片若质量调整仍超限则继续缩小尺寸', async () => {
  const mock = installImageCanvasMock(
    (width, height, quality) => Math.round(width * height * 2.2 * quality),
    3000,
    675
  );
  try {
    const source = new File([new Uint8Array(2 * 1024 * 1024)], 'profile.jpg', { type: 'image/jpeg' });
    const result = await compressBannerImage(source, { maxWidth: 1420, maxHeight: 320 });
    assert.ok(result.size <= MAX_ADMIN_IMAGE_BYTES);
    assert.ok(mock.getSize().width < 1420);
  } finally {
    mock.restore();
  }
});

test('小于 500KB 的图片保持原文件不上传压缩副本', async () => {
  const source = new File([new Uint8Array(300 * 1024)], 'small.jpg', { type: 'image/jpeg' });
  const result = await compressAdminImage(source);
  assert.equal(result, source);
});

test('超过 500KB 的长图先降质量并保持原始像素尺寸', async () => {
  const mock = installImageCanvasMock((_width, _height, quality) => Math.round(711_412 * quality), 906, 11_679);
  try {
    const source = new File([new Uint8Array(711_412)], 'detail.jpg', { type: 'image/jpeg' });
    const result = await compressAdminImage(source);
    assert.ok(result.size <= MAX_ADMIN_IMAGE_BYTES);
    assert.deepEqual(mock.getSize(), { width: 906, height: 11_679 });
  } finally {
    mock.restore();
  }
});

test('质量下限仍超限时只小比例缩小，并保留至少 70% 长边', async () => {
  const mock = installImageCanvasMock(
    (width, height, quality) => Math.round(width * height * 0.1 * quality),
    3000,
    4000
  );
  try {
    const source = new File([new Uint8Array(2 * 1024 * 1024)], 'complex.jpg', { type: 'image/jpeg' });
    const result = await compressAdminImage(source);
    assert.ok(result.size <= MAX_ADMIN_IMAGE_BYTES);
    assert.ok(mock.getSize().width >= 2100);
    assert.ok(mock.getSize().height >= 2800);
  } finally {
    mock.restore();
  }
});

test('原文件超出服务端 5MB 限额或首页尺寸不足时拒绝上传', async () => {
  const tooLarge = new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'banner.png', { type: 'image/png' });
  await assert.rejects(() => compressBannerImage(tooLarge, { maxWidth: 1500, maxHeight: 2080 }), /5MB/);
  const mock = installImageCanvasMock(() => 0, 300, 400);
  try {
    const tooSmall = new File([new Uint8Array(10)], 'small.png', { type: 'image/png' });
    await assert.rejects(
      () => compressBannerImage(tooSmall, { maxWidth: 1500, maxHeight: 2080, minWidth: 750, minHeight: 1040 }),
      /750.*1040/
    );
  } finally {
    mock.restore();
  }
});
