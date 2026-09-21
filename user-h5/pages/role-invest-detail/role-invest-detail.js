const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole } = require('../../utils/roles');
const { getApplicationDetail } = require('../../utils/invest');

const ROLE_CENTER_URL = '/pages/role-center/role-center';
const INVESTOR_ID = 'INV-1001';

Page(
  withShare({
    data: {
      ready: false,
      title: '申请详情',
      record: null
    },
    onLoad(options) {
      const role = getCurrentBusinessRole();
      if (!role || role.id !== 'investor') {
        wx.showToast({ title: '仅投资人角色可查看申请详情', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.investorId = INVESTOR_ID;
      this.recordId = (options && options.id) || '';
      const record = getApplicationDetail(this.investorId, this.recordId);
      if (!record) {
        this.setData({ ready: false });
        wx.showToast({ title: '申请记录不存在', icon: 'none' });
        return;
      }
      this.setData({ ready: true, record });
    },
    copyOrderNo() {
      const orderNo = this.data.record && this.data.record.orderNo;
      if (!orderNo) return;
      wx.setClipboardData({
        data: orderNo,
        success: () => wx.showToast({ title: '单号已复制', icon: 'none' }),
        fail: () => wx.showToast({ title: '复制失败，请重试', icon: 'none' })
      });
    },
    leaveToRoleCenter() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.reLaunch({ url: ROLE_CENTER_URL });
    }
  })
);