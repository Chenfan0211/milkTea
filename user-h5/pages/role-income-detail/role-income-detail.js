const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole, getIncomeRecordDetail } = require('../../utils/roles');

const ROLE_CENTER_URL = '/pages/role-center/role-center';

Page(
  withShare({
    data: {
      ready: false,
      title: '收益详情',
      record: null
    },
    onLoad(options) {
      this.recordId = (options && options.id) || '';
      this.syncRecord();
    },
    syncRecord() {
      const role = getCurrentBusinessRole();
      if (!role) {
        this.setData({ ready: false });
        wx.showToast({ title: '请先开通并选择经营角色', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      const record = getIncomeRecordDetail(role.id, this.recordId);
      if (!record) {
        this.setData({ ready: false });
        wx.showToast({ title: '收益记录不存在', icon: 'none' });
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