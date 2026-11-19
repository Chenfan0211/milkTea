<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NFormItem, NSelect } from 'naive-ui';
import { fetchProductsPage } from '@/service/api/admin-product';
import { fetchMallProducts } from '@/service/api/mall';

const props = defineProps<{ targetType: string; targetId: string; allowedTypes?: string[] }>();
const emit = defineEmits<{ 'update:targetType': [string]; 'update:targetId': [string] }>();
const allOptions = [
  { label: '不跳转', value: 'none' },
  { label: '订单页', value: 'orders' },
  { label: '优惠券页', value: 'coupons' },
  { label: '充值页', value: 'recharge' },
  { label: '点单商品列表', value: 'menu' },
  { label: '点单商品', value: 'menuProduct' },
  { label: '商城商品', value: 'mallProduct' }
];
const options = computed(() =>
  Array.isArray(props.allowedTypes)
    ? allOptions.filter(option => props.allowedTypes?.includes(option.value))
    : allOptions
);
const products = ref<{ label: string; value: string }[]>([]);
const searching = ref(false);
let requestId = 0;

function changeType(value: string) {
  emit('update:targetType', value);
  emit('update:targetId', '');
}

async function searchProducts(keyword = '') {
  if (!['menuProduct', 'mallProduct'].includes(props.targetType)) return;
  const current = ++requestId;
  searching.value = true;
  try {
    if (props.targetType === 'menuProduct') {
      const result = await fetchProductsPage({ current: 1, size: 50, search: keyword });
      if (current === requestId)
        products.value = (result.records || [])
          .filter(row => row.productId)
          .map(row => ({ label: `${row.name || row.productId}（${row.productId}）`, value: String(row.productId) }));
    } else {
      const result = await fetchMallProducts({ page: 1, size: 50, keyword });
      if (current === requestId)
        products.value = (result.records || []).map(row => ({
          label: `${row.name}（#${row.id}）`,
          value: String(row.id)
        }));
    }
    if (current === requestId && props.targetId && !products.value.some(row => row.value === props.targetId)) {
      products.value.unshift({ label: `已选商品（${props.targetId}）`, value: props.targetId });
    }
  } catch (error: any) {
    window.$message?.error(error?.message || '商品列表加载失败');
  } finally {
    if (current === requestId) searching.value = false;
  }
}

watch(
  () => props.targetType,
  () => {
    products.value = [];
    void searchProducts();
  },
  { immediate: true }
);
</script>

<template>
  <div>
    <NFormItem label="点击后跳转">
      <NSelect :value="targetType" :options="options" @update:value="changeType" />
    </NFormItem>
    <NFormItem v-if="targetType === 'menuProduct' || targetType === 'mallProduct'" label="目标商品" required>
      <NSelect
        :value="targetId || null"
        :options="products"
        :loading="searching"
        filterable
        remote
        clearable
        placeholder="搜索并选择商品"
        @search="searchProducts"
        @update:value="emit('update:targetId', $event || '')"
      />
    </NFormItem>
  </div>
</template>
