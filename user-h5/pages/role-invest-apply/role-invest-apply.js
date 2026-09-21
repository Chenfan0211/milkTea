const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole } = require('../../utils/roles');
const { getSpotById, submitApplication } = require('../../utils/invest');

const ROLE_CENTER_URL = '/pages/role-center/role-center';
const RECORDS_URL = '/pages/role-invest-records/role-invest-records';
const INVESTOR_ID = 'INV-1001';

const FIELDS = [
  { id: 'contact', label: '联系人', placeholder: '请输入联系人姓名', type: 'text' },
  { id: 'phone', label: '联系手机号', placeholder: '请输入手机号', type: 'number' },
  { id: 'budget', label: '投资预算（元）', placeholder: '请输入投资预算', type: 'digit' },
  { id: 'remark', label: '意向说明', placeholder: '选填：对该点位的了解', type: 'text' }
];

const NOTES = [
  {
    id: 'audit',
    icon: '/assets/icons/lucide/clock-muted.svg',
    title: '审核时效',
    description: '提交后由运营审核，预计 3 个工作日给出结果。'
  },
  {
    id: 'sign',
    icon: '/assets/icons/lucide/handshake.svg',
    title: '签约入驻',
    description: '审核通过后需完成签约与保证金缴纳，方可开通分级权限。'
  },
  {
    id: 'multi',
    icon: '/assets/icons/lucide/map-pinned.svg',
    title: '多点位申请',
    description: '同一投资人可申请多个未绑定点位，各自独立审核。'
  },
  {
    id: 'retry',
    icon: '/assets/icons/lucide/refresh-cw.svg',
    title: '驳回重提',
    description: '未通过的点位可调整预算或说明后重新提交申请。'
  }
];

function createEmptyForm() {
  return { contact: '', phone: '', budget: '', remark: '' };
}

// 手机号：11 位数字，1 开头。
function isValidPhone(value) {
  return /^1\d{10}$/.test(String(value || '').trim());
}

Page(
  withShare({
    data: {
      ready: false,
      title: '填写投资申请',
      spot: null,
      fields: FIELDS,
      notes: NOTES,
      form: createEmptyForm(),
      canSubmit: false
    },
    onLoad(options) {
      const role = getCurrentBusinessRole();
      if (!role || role.id !== 'investor') {
        wx.showToast({ title: '仅投资人角色可申请点位投资', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.investorId = INVESTOR_ID;
      const storeId = (options && options.storeId) || '';
      const spot = getSpotById(storeId);
      // 只有「可申请」状态的点位能进入表单，避免绕过列表直接提交。
      if (!spot || spot.spotStatus !== 'available') {
        this.setData({ ready: false });
        wx.showToast({ title: '该点位当前不可申请', icon: 'none' });
        return;
      }
      this.storeId = spot.id;
      this.setData({ ready: true, title: `${spot.name} · 投资申请`, spot });
    },
    handleInput(event) {
      const { field } = event.currentTarget.dataset;
      this.setData({ [`form.${field}`]: event.detail.value }, () => this.syncSubmitState());
    },
    syncSubmitState() {
      const { contact, phone, budget } = this.data.form;
      this.setData({
        canSubmit: Boolean(String(contact).trim() && isValidPhone(phone) && String(budget).trim())
      });
    },
    submit() {
      const { form } = this.data;
      if (!String(form.contact).trim()) {
        wx.showToast({ title: '请填写联系人', icon: 'none' });
        return;
      }
      if (!isValidPhone(form.phone)) {
        wx.showToast({ title: '请填写正确的手机号', icon: 'none' });
        return;
      }
      if (!String(form.budget).trim()) {
        wx.showToast({ title: '请填写投资预算', icon: 'none' });
        return;
      }
      const result = submitApplication({
        investorId: this.investorId,
        storeId: this.storeId,
        contact: String(form.contact).trim(),
        phone: String(form.phone).trim(),
        budget: String(form.budget).trim(),
        remark: String(form.remark).trim()
      });
      if (!result.ok) {
        wx.showToast({ title: result.message, icon: 'none' });
        return;
      }
      wx.showToast({ title: '已提交，等待运营审核', icon: 'none' });
      setTimeout(() => {
        wx.redirectTo({ url: `${RECORDS_URL}?id=${result.record.id}` });
      }, 800);
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