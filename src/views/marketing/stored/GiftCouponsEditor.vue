<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useAdminStore } from '@/store/modules/admin';

interface GiftCoupon {
  couponId: number | null;
  amount: number;
  quantity: number;
  description: string;
}

const props = defineProps<{ modelValue: GiftCoupon[] }>();
const emit = defineEmits<{ 'update:modelValue': [value: GiftCoupon[]] }>();

const store = useAdminStore();
const items = ref<GiftCoupon[]>([]);

// 已启用的优惠券作为可选券池
const enabledCoupons = computed(() => store.coupons.filter(c => c.status === 'enabled'));

watch(
  () => props.modelValue,
  val => {
    items.value = JSON.parse(JSON.stringify(val || []));
  },
  { immediate: true, deep: true }
);

function emitChange() {
  emit('update:modelValue', JSON.parse(JSON.stringify(items.value)));
}

function onSelectCoupon(item: GiftCoupon, couponId: number) {
  const coupon = enabledCoupons.value.find(c => c.id === couponId);
  item.couponId = couponId;
  item.amount = coupon ? coupon.amount : 0;
  item.description = coupon ? `${coupon.title}` : '';
  emitChange();
}

function addRow() {
  items.value.push({ couponId: null, amount: 0, quantity: 1, description: '' });
  emitChange();
}

function removeRow(index: number) {
  items.value.splice(index, 1);
  emitChange();
}

const couponOptions = computed(() => enabledCoupons.value.map(c => ({ label: c.title, value: c.id })));
</script>

<template>
  <div class="gift-coupons-editor">
    <div v-if="!items.length" class="gc-empty">暂无赠送券，点击下方按钮添加</div>

    <div v-for="(item, index) in items" :key="index" class="gc-row">
      <NSelect
        :value="item.couponId"
        :options="couponOptions"
        placeholder="选择优惠券"
        filterable
        class="gc-select"
        @update:value="(v: number) => onSelectCoupon(item, v)"
      />
      <NInputNumber
        :value="item.quantity"
        :min="1"
        placeholder="张数"
        class="gc-quantity"
        @update:value="(v: number | null) => { item.quantity = v || 1; emitChange(); }"
      />
      <span class="gc-amount">¥{{ item.amount }} x {{ item.quantity }}张</span>
      <NButton size="tiny" quaternary type="error" @click="removeRow(index)">删除</NButton>
    </div>

    <NButton size="small" dashed type="primary" @click="addRow">+ 添加赠送券</NButton>
  </div>
</template>

<style scoped>
.gift-coupons-editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.gc-empty {
  color: #9b9b96;
  font-size: 13px;
}
.gc-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.gc-row :deep(.n-button) {
  flex-shrink: 0;
}
.gc-select {
  flex: 1;
  min-width: 200px;
}
.gc-quantity {
  width: 110px;
}
.gc-amount {
  font-size: 13px;
  color: #666762;
}
</style>
