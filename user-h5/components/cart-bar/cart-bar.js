Component({
  properties: {
    count: { type: Number, value: 2 },
    total: { type: Number, value: 16 }
  },
  methods: {
    handleCart() { this.triggerEvent('cart') },
    handleCheckout() { this.triggerEvent('checkout') }
  }
})
