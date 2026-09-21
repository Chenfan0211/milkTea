const qrcode = require('../../utils/qrcode-lib');

function buildMatrix(value) {
  const qr = qrcode(0, 'M');
  qr.addData(String(value || ''));
  qr.make();
  const count = qr.getModuleCount();
  return { count, isDark: (row, col) => qr.isDark(row, col) };
}

function getPixelRatio() {
  try {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    return info.pixelRatio || 1;
  } catch (error) {
    return 1;
  }
}

Component({
  properties: {
    value: { type: String, value: '' },
    size: { type: Number, value: 320 }
  },
  lifetimes: {
    attached() {
      this.draw();
    }
  },
  observers: {
    'value, size': function () {
      this.draw();
    }
  },
  methods: {
    draw() {
      const value = String(this.data.value || '');
      if (!value) return;

      this.createSelectorQuery()
        .select('#qrcode-canvas')
        .fields({ node: true, size: true })
        .exec(res => {
          if (!res || !res[0] || !res[0].node) return;
          const canvas = res[0].node;
          const width = res[0].width || 320;
          const height = res[0].height || 320;
          const dpr = getPixelRatio();
          canvas.width = Math.floor(width * dpr);
          canvas.height = Math.floor(height * dpr);
          const ctx = canvas.getContext('2d');
          ctx.scale(dpr, dpr);

          const matrix = buildMatrix(value);
          const count = matrix.count;
          const cell = Math.floor(width / (count + 8));
          const total = cell * count;
          const offsetX = Math.floor((width - total) / 2);
          const offsetY = Math.floor((height - total) / 2);

          ctx.fillStyle = '#FFFFFF';
          ctx.fillRect(0, 0, width, height);
          ctx.fillStyle = '#000000';
          for (let row = 0; row < count; row += 1) {
            for (let col = 0; col < count; col += 1) {
              if (matrix.isDark(row, col)) {
                ctx.fillRect(offsetX + col * cell, offsetY + row * cell, cell, cell);
              }
            }
          }
        });
    }
  }
});