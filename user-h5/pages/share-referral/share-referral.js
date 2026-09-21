const { withShare } = require('../../utils/share');

const INVITE_CODE = 'WLG2026';

Page(
  withShare({
    data: {
      inviteCode: INVITE_CODE,
      invitedCount: 2,
      rewards: [
        {
          id: 'first-order',
          title: '双方首单奖励',
          desc: '好友通过链接注册并完成首单，双方各得 3 时光币 + 1 张 3 元无门槛券'
        },
        { id: 'social-star', title: '社交达人', desc: '累计邀请满 5 人，额外解锁「社交达人」徽章 + 指定产品' },
        {
          id: 'recommender',
          title: '时光推荐官',
          desc: '累计邀请满 10 人，升级为「时光推荐官」，享受被邀请人后续消费 5% 返利（示例，以后台配置为准）'
        }
      ],
      earningNote: '邀请好友注册 +3币/人，好友完成首单后到账。'
    },
    copyCode() {
      wx.setClipboardData({ data: this.data.inviteCode });
    },
    invite() {
      try {
        wx.showShareMenu({ menus: ['shareAppMessage', 'shareTimeline'] });
      } catch (error) {
        // 分享能力在不同基础库存在差异，忽略失败。
      }
      wx.showToast({ title: '邀请链接已准备，可点击右上角分享', icon: 'none' });
    }
  })
);
