Component({
  properties: {
    visible: { type: Boolean, value: false },
    activity: { type: Object, value: {} },
    store: { type: Object, value: {} }
  },
  methods: {
    noop() {},
    handleClose() {
      this.triggerEvent('close');
    },
    handlePhone() {
      this.triggerEvent('phone');
    },
    handleView() {
      this.triggerEvent('view');
    },
    handleUnavailable(event) {
      const label = event.currentTarget.dataset.label || '功能';
      this.triggerEvent('unavailable', { label });
    }
  }
});
