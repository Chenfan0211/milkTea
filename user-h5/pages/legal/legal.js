const { withShare } = require('../../utils/share');
const { getLegalDoc } = require('../../data/legal');

// 支持的文档类型，用于标题回退
const FALLBACK_TITLES = {
  agreement: '用户协议',
  privacy: '隐私政策'
};

Page(
  withShare({
    data: {
      title: '协议',
      doc: null
    },
    onLoad(options) {
      const type = (options && options.type) || 'agreement';
      const doc = getLegalDoc(type);
      if (!doc) {
        // 未知类型不抛错，展示空状态，避免白屏
        this.setData({ title: FALLBACK_TITLES[type] || '协议', doc: null });
        return;
      }
      this.setData({ title: doc.title, doc });
    }
  })
);