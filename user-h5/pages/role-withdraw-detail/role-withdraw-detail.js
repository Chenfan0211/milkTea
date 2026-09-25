const { withShare } = require('../../utils/share');
const { formatDateTime } = require('../../utils/date-format');
const { getCurrentBusinessRole, getWithdrawRecordDetail, syncWithdrawalsFromRemote } = require('../../utils/roles');

const RECORDS_URL = '/pages/role-withdraw-records/role-withdraw-records';
const ROLE_CENTER_URL = '/pages/role-center/role-center';

Page(
  withShare({
    data: {
      ready: false,
      title: '提现详情',
      record: null
    },
    onLoad(options) {
      this.recordId = (options && options.id) || '';
      // 先同步后端记录再取单条，避免直接进详情页时缓存为空
      syncWithdrawalsFromRemote().then(() => this.syncRecord());
    },
    syncRecord() {
      const role = getCurrentBusinessRole();
      if (!role) {
        this.setData({ ready: false });
        wx.showToast({ title: '请先开通并选择经营角色', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      const record = getWithdrawRecordDetail(role.id, this.recordId);
      if (!record) {
        this.setData({ ready: false });
        wx.showToast({ title: '提现记录不存在', icon: 'none' });
        return;
      }
      this.setData({ ready: true, record: Object.assign({}, record, { timeText: formatDateTime(record.time), timeline: (record.timeline || []).map(step => Object.assign({}, step, { timeText: step.time ? formatDateTime(step.time) : '' })) }) });
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