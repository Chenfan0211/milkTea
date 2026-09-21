function roundMoney(value) {
  return Math.round(value * 10) / 10;
}

function summarize(items) {
  const selectedItems = items.filter(item => item.selected);
  const selectedCount = selectedItems.reduce((sum, item) => sum + item.quantity, 0);
  const total = roundMoney(selectedItems.reduce((sum, item) => sum + item.price * item.quantity, 0));
  const originalTotal = roundMoney(selectedItems.reduce((sum, item) => sum + item.originalPrice * item.quantity, 0));
  const discount = roundMoney(originalTotal - total);

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
    items: { type: Array, value: [] }
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
