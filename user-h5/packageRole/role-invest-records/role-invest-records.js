const { withShare } = require('../../utils/share');
const { ensureBusinessRole } = require('../../utils/role-page');
const { formatDateTime } = require('../../utils/date-format');
const { getCurrentBusinessRole } = require('../../utils/roles');
const { listApplications, refreshInvestApplications, refreshInvestCatalog } = require('../../utils/invest');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';
const DETAIL_URL = '/packageRole/role-invest-detail/role-invest-detail';
const CATEGORIES = [
  { id: 'all', label: '全部' },
  { id: 'pending', label: '审核中' },
  { id: 'signed', label: '已签约' },
  { id: 'rejected', label: '已驳回' }
];

const DATE_PLACEHOLDER = { start: '开始时间', end: '结束时间' };

function pad(value) {
  return String(value).padStart(2, '0');
}

function toDateKey(year, month, day) {
  return `${year}-${pad(month)}-${pad(day)}`;
}

function recordDateKey(record) {
  return String((record && record.time) || '').slice(0, 10);
}

function daysInMonth(year, month) {
  return new Date(year, month, 0).getDate();
}

function buildRange(count, from) {
  return Array.from({ length: count }, (_, index) => from + index);
}

// 搜索同时匹配申请单号与点位名称。
function matchKeyword(record, keyword) {
  const key = String(keyword || '')
    .trim()
    .toLowerCase();
  if (!key) return true;
  const orderNo = String(record.orderNo || '').toLowerCase();
  const storeName = String(record.storeName || '').toLowerCase();
  return orderNo.indexOf(key) >= 0 || storeName.indexOf(key) >= 0;
}

// 闭区间比较，YYYY-MM-DD 定长可直接字典序比较。
function matchDateRange(record, start, end) {
  if (!start && !end) return true;
  const dateKey = recordDateKey(record);
  if (!dateKey) return false;
  if (start && dateKey < start) return false;
  if (end && dateKey > end) return false;
  return true;
}

Page(
  withShare({
    data: {
      ready: false,
      title: '投资申请记录',
      keyword: '',
      dateStart: '',
      dateEnd: '',
      dateStartLabel: DATE_PLACEHOLDER.start,
      dateEndLabel: DATE_PLACEHOLDER.end,
      hasFilter: false,
      dateRangeDirty: false,
      categories: CATEGORIES.map(item => Object.assign({}, item, { count: 0 })),
      activeCategory: 'all',
      records: [],
      filteredRecords: [],
      datePickerVisible: false,
      datePickerTitle: '选择开始时间',
      pickerValue: [0, 0, 0],
      pickerYears: [],
      pickerMonths: [],
      pickerDays: []
    },
    onLoad(options) {
      const now = new Date();
      this.todayKey = toDateKey(now.getFullYear(), now.getMonth() + 1, now.getDate());
      // 默认时间区间：当月 1 号 ~ 今天（按天闭区间）。用户可在工具栏调整，重置按钮恢复到此区间。
      const defaultStart = toDateKey(now.getFullYear(), now.getMonth() + 1, 1);
      this.defaultRange = { start: defaultStart, end: this.todayKey };
      this.setData({
        dateStart: defaultStart,
        dateStartLabel: defaultStart,
        dateEnd: this.todayKey,
        dateEndLabel: this.todayKey
      });
      // 从申请页跳转过来时直接高亮对应记录。
      this.focusId = (options && options.id) || '';
      ensureBusinessRole().then(() => this.syncRole());
    },
    onShow() {
      if (this.data.ready) this.syncRecords();
    },
    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role || role.id !== 'investor') {
        this.setData({ ready: false });
        wx.showToast({ title: '仅投资人角色可查看申请记录', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      // 必须先注入门店/城市目录，再拉申请镜像：
      // 历史记录 extra_form 可能缺 storeName/cityName，normalizeRemoteApplication 依赖
      // storeCatalog 按 subject_id 回填；顺序颠倒会导致城市、门店名为空。
      Promise.all([refreshInvestCatalog(), refreshInvestApplications()]).then(() => {
        this.setData({ ready: true }, () => this.syncRecords());
      });
    },
    syncRecords() {
      // statusLabel 由 listApplications() 统一按内部状态映射，不在此处重复查表
      // （历史 bug：这里用已归一化的小写状态查大写枚举表，退化成英文 pending/signed）。
      const records = listApplications().map(item => Object.assign({}, item, { timeText: formatDateTime(item.time) }));
      this.setData({ records }, () => {
        this.applyFilter();
        if (this.focusId) {
          this.focusId = '';
          const target = records.find(item => item.id === this.focusId);
          if (target) this.openDetail({ currentTarget: { dataset: { id: target.id } } });
        }
      });
    },
    applyFilter() {
      const { keyword, dateStart, dateEnd, activeCategory } = this.data;
      const matched = (this.data.records || []).filter(
        item => matchKeyword(item, keyword) && matchDateRange(item, dateStart, dateEnd)
      );
      const categories = CATEGORIES.map(item => ({
        id: item.id,
        label: item.label,
        count: item.id === 'all' ? matched.length : matched.filter(r => r.status === item.id).length
      }));
      const filteredRecords =
        activeCategory === 'all' ? matched : matched.filter(item => item.status === activeCategory);
      // 仅当时间偏离默认区间（当月 1 号 ~ 今天）时才显示「恢复本月」重置按钮
      const range = this.defaultRange || {};
      const dateRangeDirty = Boolean(dateStart && dateEnd && (dateStart !== range.start || dateEnd !== range.end));
      this.setData({
        categories,
        filteredRecords,
        dateRangeDirty,
        hasFilter: Boolean(keyword || dateStart || dateEnd)
      });
    },
    handleKeyword(event) {
      this.setData({ keyword: event.detail.value }, () => this.applyFilter());
    },
    clearKeyword() {
      this.setData({ keyword: '' }, () => this.applyFilter());
    },
    switchCategory(event) {
      this.setData({ activeCategory: event.currentTarget.dataset.id }, () => this.applyFilter());
    },
    openDatePicker(event) {
      const field = event.currentTarget.dataset.field;
      const current = field === 'start' ? this.data.dateStart : this.data.dateEnd;
      const base = current || this.todayKey;
      const [year, month, day] = base.split('-').map(Number);
      this.pickerField = field;
      this.setData(
        {
          datePickerVisible: true,
          datePickerTitle: field === 'start' ? '选择开始时间' : '选择结束时间',
          pickerYears: buildRange(6, year - 4),
          pickerMonths: buildRange(12, 1),
          pickerDays: buildRange(daysInMonth(year, month), 1),
          pickerValue: [4, month - 1, day - 1]
        },
        () => this.syncPickerDays()
      );
    },
    closeDatePicker() {
      this.setData({ datePickerVisible: false });
    },
    handleDateChange(event) {
      const [yearIndex, monthIndex] = event.detail.value;
      const year = this.data.pickerYears[yearIndex];
      const month = this.data.pickerMonths[monthIndex];
      this.setData({ pickerValue: event.detail.value }, () => {
        const total = daysInMonth(year, month);
        if (this.data.pickerDays.length !== total) {
          this.setData({ pickerDays: buildRange(total, 1) });
        }
      });
    },
    syncPickerDays() {
      const [yearIndex, monthIndex] = this.data.pickerValue;
      const total = daysInMonth(this.data.pickerYears[yearIndex], this.data.pickerMonths[monthIndex]);
      if (this.data.pickerDays.length !== total) {
        this.setData({ pickerDays: buildRange(total, 1) });
      }
    },
    confirmDate() {
      const [yearIndex, monthIndex, dayIndex] = this.data.pickerValue;
      const year = this.data.pickerYears[yearIndex];
      const month = this.data.pickerMonths[monthIndex];
      const day = Math.min(this.data.pickerDays[dayIndex], daysInMonth(year, month));
      const picked = toDateKey(year, month, day);
      const next = { datePickerVisible: false };
      if (this.pickerField === 'start') {
        next.dateStart = picked;
        next.dateStartLabel = picked;
        if (this.data.dateEnd && this.data.dateEnd < picked) {
          next.dateEnd = picked;
          next.dateEndLabel = picked;
        }
      } else {
        next.dateEnd = picked;
        next.dateEndLabel = picked;
        if (this.data.dateStart && this.data.dateStart > picked) {
          next.dateStart = picked;
          next.dateStartLabel = picked;
        }
      }
      this.setData(next, () => this.applyFilter());
    },
    /** 重置时间筛选：恢复到默认区间（当月 1 号 ~ 今天），而非清空。 */
    resetDates() {
      const range = this.defaultRange || { start: this.todayKey, end: this.todayKey };
      this.setData(
        {
          dateStart: range.start,
          dateStartLabel: range.start,
          dateEnd: range.end,
          dateEndLabel: range.end
        },
        () => this.applyFilter()
      );
    },
    copyOrderNo(event) {
      const orderNo = event.currentTarget.dataset.orderNo;
      if (!orderNo) return;
      wx.setClipboardData({
        data: orderNo,
        success: () => wx.showToast({ title: '单号已复制', icon: 'none' }),
        fail: () => wx.showToast({ title: '复制失败，请重试', icon: 'none' })
      });
    },
    openDetail(event) {
      const id = event.currentTarget.dataset.id;
      if (!id) return;
      wx.navigateTo({ url: `${DETAIL_URL}?id=${id}` });
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
