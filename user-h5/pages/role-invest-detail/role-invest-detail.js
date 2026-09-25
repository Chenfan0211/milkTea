const { withShare } = require('../../utils/share');
const { formatDateTime } = require('../../utils/date-format');
const { getCurrentBusinessRole } = require('../../utils/roles');
const { getApplicationDetail, refreshInvestApplications } = require('../../utils/invest');

const ROLE_CENTER_URL = '/pages/role-center/role-center';

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
      this.recordId = (options && options.id) || '';
      // 先拉取申请镜像再取详情（getApplicationDetail 依赖镜像）
      refreshInvestApplications().then(() => {
        const record = getApplicationDetail(this.recordId);
        if (!record) {
          this.setData({ ready: false });
          wx.showToast({ title: '申请记录不存在', icon: 'none' });
          return;
        }
        this.setData({ ready: true, record: Object.assign({}, record, { timeText: formatDateTime(record.time), timeline: (record.timeline || []).map(step => Object.assign({}, step, { timeText: step.time ? formatDateTime(step.time) : '' })) }) });
      });
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