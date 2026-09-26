const { withShare } = require('../../utils/share');
const {
  getCurrentBusinessRole,
  getVerifyData,
  syncVerifyFromRemote,
  verifyStoreOrderByCode
} = require('../../utils/roles');
const { verifyExchange } = require('../../utils/points');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';
const { formatDateTime } = require('../../utils/date-format');

Page(
  withShare({
    data: {
      ready: false,
      role: null,
      title: '核销订单',
      pool: [],
      records: [],
      keyword: '',
      mode: 'order', // order=点单核销, exchange=兑换核销
      exchangeKeyword: ''
    },
    onLoad() {
      this.syncRole();
    },
    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role) {
        this.setData({ ready: false, role: null, title: '核销订单' });
        wx.showToast({ title: '请先开通并选择经营角色', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      const data = getVerifyData(role.id);
      if (!data) {
        this.setData({ ready: false, role, title: '核销订单' });
        wx.showToast({ title: '当前角色不支持核销', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.setData({ ready: true, role, title: `${role.label}核销订单`, pool: data.pool, records: data.records });
      // 核销记录与待核销池均以后端为准；拉到后覆盖渲染（失败保留现状）
      syncVerifyFromRemote(role.id).then(() => {
        const next = getVerifyData(role.id);
        if (!next) return;
        this.setData({ pool: next.pool, records: next.records });
      });
    },
    leaveToRoleCenter() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.reLaunch({ url: ROLE_CENTER_URL });
    },
    switchMode(event) {
      this.setData({ mode: event.currentTarget.dataset.mode });
    },
    handleScan() {
      wx.scanCode({
        onlyFromCamera: false,
        success: res => {
          const code = res.result || '';
          if (this.data.mode === 'exchange') {
            this.setData({ exchangeKeyword: code });
            this.verifyExchangeByCode(code);
          } else {
            this.setData({ keyword: code });
            this.verifyOrderByCode(code);
          }
        },
        fail: () => {
          wx.showToast({ title: '扫码失败，可手动输入', icon: 'none' });
        }
      });
    },
    handleInput(event) {
      this.setData({ keyword: event.detail.value });
    },
    handleExchangeInput(event) {
      this.setData({ exchangeKeyword: event.detail.value });
    },
    handleQuery() {
      this.verifyOrderByCode(this.data.keyword);
    },
    handleExchangeQuery() {
      this.verifyExchangeByCode(this.data.exchangeKeyword);
    },
    verifyOrderByCode(keyword) {
      const value = String(keyword || '').trim();
      if (!value) {
        wx.showToast({ title: '请输入取餐号或订单号', icon: 'none' });
        return;
      }
      const record = this.data.pool.find(item => item.pickupCode === value || item.orderNo === value);
      if (!record) {
        wx.showToast({ title: '未找到匹配订单', icon: 'none' });
        return;
      }
      if (this.data.records.some(item => item.orderNo === record.orderNo)) {
        wx.showToast({ title: '该订单已核销', icon: 'none' });
        return;
      }
      // 核销为服务端写操作（订单置核销 + 触发五方分账）：
      // 门店归属由后端校验，重复核销 / 非已支付订单会被拒绝。
      if (this.verifying) return;
      this.verifying = true;
      wx.showLoading({ title: '核销中', mask: true });
      verifyStoreOrderByCode(value)
        .then(outcome => {
          wx.hideLoading();
          this.verifying = false;
          wx.showToast({
            title: outcome.message,
            icon: outcome.ok ? 'success' : 'none'
          });
          if (!outcome.ok) return;
          // 核销成功后刷新记录与待核销池
          const role = getCurrentBusinessRole();
          const next = role ? getVerifyData(role.id) : null;
          if (next) this.setData({ pool: next.pool, records: next.records, keyword: '' });
        })
        .catch(() => {
          wx.hideLoading();
          this.verifying = false;
          wx.showToast({ title: '核销失败，请稍后重试', icon: 'none' });
        });
    },
    verifyExchangeByCode(keyword) {
      const value = String(keyword || '').trim();
      if (!value) {
        wx.showToast({ title: '请输入兑换自提码', icon: 'none' });
        return;
      }
      // 兑换核销为服务端写操作：结果以后端为准，随后刷新核销记录
      verifyExchange(value).then(result => {
        if (!result || !result.ok) {
          const reason =
            result && result.reason === 'already_verified'
              ? '该自提码已核销'
              : result && result.reason === 'not_found'
                ? '未找到匹配的兑换自提码'
                : (result && result.message) || '核销失败，请稍后重试';
          wx.showToast({ title: reason, icon: 'none' });
          return;
        }
        const order = result.order || {};
        this.setData({
          records: this.data.records.concat({
            id: 'ex-' + Date.now(),
            title: order.orderNo || '兑换核销',
            meta: '自提码 ' + value,
            orderNo: order.orderNo || value,
            time: formatDateTime(new Date()),
            image: '',
            pickupCode: order.pickupCode || value,
            type: 'exchange'
          })
        });
        wx.showToast({ title: '兑换核销成功', icon: 'success' });
      });
    },
    showUnavailable() {
      wx.showToast({ title: '该功能待接入真实接口', icon: 'none' });
    }
  })
);
