const { yuanToFen, fenToYuan } = require('../../utils/money');

function roundMoney(value) {
  // 按「分」取整，避免 0.04 被舍入为 0（1 位小数截断）
  return fenToYuan(yuanToFen(value));
}

function summarize(items) {
  const selectedItems = items.filter(item => item.selected);
  const selectedCount = selectedItems.reduce((sum, item) => sum + item.quantity, 0);
  const total = fenToYuan(selectedItems.reduce((sum, item) => sum + yuanToFen(item.price) * item.quantity, 0));
  const originalTotal = fenToYuan(
    selectedItems.reduce((sum, item) => sum + yuanToFen(item.originalPrice) * item.quantity, 0)
  );
  const discount = fenToYuan(yuanToFen(originalTotal) - yuanToFen(total));

  return {
    selectedItems,
    selectedCount,
    total,
    originalTotal,
    discount,
    allSelected: items.length > 0 && selectedItems.length === items.length
  };
}

Component({
  properties: {
    visible: { type: Boolean, value: false },
    placement: { type: String, value: 'tabbar' },
    items: { type: Array, value: [] },
    showCheckout: { type: Boolean, value: false },
    checkoutLabel: { type: String, value: '去结算' },
    showEdit: { type: Boolean, value: true },
    offsetForTabbar: { type: Boolean, value: true }
  },
  data: {
    summary: summarize([])
  },
  observers: {
    items(items) {
      this.setData({ summary: summarize(items) });
    }
  },
  methods: {
    noop() {},
    emitChange(items) {
      this.setData({ summary: summarize(items) });
      this.triggerEvent('change', { items });
    },
    handleClose() {
      this.triggerEvent('close');
    },
    toggleItem(event) {
      const { id } = event.currentTarget.dataset;
      const items = this.data.items.map(item => (item.id === id ? { ...item, selected: !item.selected } : item));
      this.emitChange(items);
    },
    toggleAll() {
      const nextSelected = !this.data.summary.allSelected;
      const items = this.data.items.map(item => ({ ...item, selected: nextSelected }));
      this.emitChange(items);
    },
    clearCart() {
      this.emitChange([]);
      this.triggerEvent('close');
    },
    increaseQuantity(event) {
      const { id } = event.currentTarget.dataset;
      const items = this.data.items.map(item => (item.id === id ? { ...item, quantity: item.quantity + 1 } : item));
      this.emitChange(items);
    },
    decreaseQuantity(event) {
      const { id } = event.currentTarget.dataset;
      const items = this.data.items.map(item =>
        item.id === id && item.quantity > 1 ? { ...item, quantity: item.quantity - 1 } : item
      );
      this.emitChange(items);
    },
    handleEdit(event) {
      this.triggerEvent('edit', { id: event.currentTarget.dataset.id });
    },
    handleInfo() {
      this.triggerEvent('info');
    },
    handleCheckout() {
      if (!this.data.summary.selectedCount) return;
      this.triggerEvent('checkout', { items: this.data.summary.selectedItems });
    }
  }
});
