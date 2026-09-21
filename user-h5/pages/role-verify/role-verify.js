const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole, getVerifyData } = require('../../utils/roles');
const { verifyExchange } = require('../../utils/points');

const ROLE_CENTER_URL = '/pages/role-center/role-center';
const VERIFY_RECORDS_KEY = 'milkTea:verify:records';
const ORDER_PRODUCT_IMAGE = '/assets/images/3x/menu-product.jpg';

function formatDateTime(now) {
  const pad = value => String(value).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
}

function normalizeVerifyRecord(record, pool) {
  if (!record || record.type !== 'order') return record;
  const poolRecord = pool.find(item => item.pickupCode === record.pickupCode) || {};
  return Object.assign({}, record, {
    title: record.title || poolRecord.product,
    meta: record.meta || '取餐号 ' + poolRecord.pickupCode,
    orderNo: record.orderNo || poolRecord.orderNo || '',
    time: record.time === '刚刚' ? formatDateTime(new Date()) : record.time,
    image: record.image || poolRecord.image || ORDER_PRODUCT_IMAGE
  });
}

function readVerifyRecords() {
  try {
    const cached = typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(VERIFY_RECORDS_KEY) : null;
    return Array.isArray(cached) ? cached : [];
  } catch (error) {
    return [];
  }
}

function writeVerifyRecords(records) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(VERIFY_RECORDS_KEY, records);
  } catch (error) {
    // ignore storage errors
  }
}

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
      const localRecords = readVerifyRecords()
        .filter(r => r.type === 'order')
        .map(record => normalizeVerifyRecord(record, data.pool));
      const merged = data.records.concat(localRecords);
      this.setData({ ready: true, role, title: `${role.label}核销订单`, pool: data.pool, records: merged });
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
      const verifiedCodes = readVerifyRecords().map(item => item.pickupCode);
      if (
        this.data.records.some(item => item.pickupCode === record.pickupCode) ||
        verifiedCodes.indexOf(record.pickupCode) >= 0
      ) {
        wx.showToast({ title: '该订单已核销', icon: 'none' });
        return;
      }
      const entry = {
        id: 'vr-' + Date.now(),
        title: record.product,
        meta: '取餐号 ' + record.pickupCode,
        orderNo: record.orderNo || '',
        time: formatDateTime(new Date()),
        image: record.image || ORDER_PRODUCT_IMAGE,
        pickupCode: record.pickupCode,
        type: 'order'
      };
      const nextRecords = this.data.records.concat(entry);
      this.setData({ records: nextRecords });
      writeVerifyRecords(readVerifyRecords().concat(entry));
      wx.showToast({ title: '核销成功', icon: 'success' });
    },
    verifyExchangeByCode(keyword) {
      const value = String(keyword || '').trim();
      if (!value) {
        wx.showToast({ title: '请输入兑换自提码', icon: 'none' });
        return;
      }
      const result = verifyExchange(value);
      if (!result.ok) {
        if (result.reason === 'already_verified') {
          wx.showToast({ title: '该自提码已核销', icon: 'none' });
        } else {
          wx.showToast({ title: '未找到匹配的兑换自提码', icon: 'none' });
        }
        return;
      }
      const entry = result.entry;
      this.setData({
        records: this.data.records.concat({
          id: 'ex-' + Date.now(),
          title: entry.product,
          meta: '自提码 ' + entry.pickupCode,
          orderNo: entry.orderNo || entry.pickupCode,
          time: formatDateTime(new Date()),
          image: entry.image || '',
          pickupCode: entry.pickupCode,
          type: 'exchange'
        })
      });
      wx.showToast({ title: '兑换核销成功', icon: 'success' });
    },
    showUnavailable() {
      wx.showToast({ title: '该功能待接入真实接口', icon: 'none' });
    }
  })
);
