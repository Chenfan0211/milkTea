<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, NEmpty, NModal, NSpin } from 'naive-ui';
import type { VNodeChild } from 'vue';
import type { DetailField, DetailGroup } from './detail-types';

interface Props {
  title?: string;
  groups?: DetailGroup[];
  /** 详情数据加载器：弹层打开时调用 */
  load?: (row: any) => any | Promise<any>;
  /** 直接传入的行数据（不配 load 时使用） */
  row?: any;
  width?: string;
}

const props = withDefaults(defineProps<Props>(), {
  title: '详情',
  groups: () => [],
  load: undefined,
  row: null,
  width: '720px'
});

// 动态类名会让 UnoCSS 无法静态提取，这里显式列出用到的宽度档位
const widthClass = computed(() => (props.width === '560px' ? 'w-560px' : 'w-720px'));

const visible = defineModel<boolean>('show', { default: false });

const loading = ref(false);
const detailRow = ref<any>(null);

function display(field: DetailField): VNodeChild | string | number {
  if (detailRow.value == null) return '—';
  if (field.render) return field.render(detailRow.value);
  const val = field.key ? detailRow.value[field.key] : null;
  return val == null || val === '' ? '—' : val;
}

function isVNode(field: DetailField): boolean {
  const v = display(field);
  return v != null && typeof v === 'object' && !Array.isArray(v);
}

async function loadData() {
  if (props.row != null) {
    detailRow.value = props.row;
  }
  if (!props.load) return;
  loading.value = true;
  try {
    detailRow.value = await props.load(props.row);
  } finally {
    loading.value = false;
  }
}

watch(
  () => visible.value,
  async open => {
    if (!open) return;
    detailRow.value = null;
    await loadData();
  },
  { immediate: true }
);

const hasRow = computed(() => detailRow.value != null);
</script>

<template>
  <NModal v-model:show="visible" preset="card" :title="title" :class="widthClass">
    <NSpin :show="loading">
      <div class="detail-modal-body">
        <template v-if="hasRow">
          <template v-for="(group, gi) in groups" :key="gi">
            <div v-if="gi > 0" class="detail-divider" />
            <div v-if="group.title" class="detail-group-title">{{ group.title }}</div>
            <div class="detail-grid">
              <div v-for="field in group.fields" :key="field.label" class="detail-item">
                <span class="detail-label">{{ field.label }}</span>
                <span class="detail-value">
                  <component v-if="isVNode(field)" :is="display(field)" />
                  <template v-else>{{ display(field) }}</template>
                </span>
              </div>
            </div>
          </template>
        </template>
        <NEmpty v-else-if="!loading" description="数据不存在">
          <template #extra>
            <NButton size="small" @click="visible = false">关闭</NButton>
          </template>
        </NEmpty>
      </div>
    </NSpin>
  </NModal>
</template>

<style scoped>
.detail-modal-body {
  max-height: 68vh;
  overflow-y: auto;
  padding-right: 4px;
}
.detail-group-title {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  margin-bottom: 12px;
}
.detail-divider {
  height: 1px;
  background: #f0f0f0;
  margin: 20px 0;
}
.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 24px;
}
.detail-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.detail-label {
  font-size: 13px;
  color: #9b9b96;
}
.detail-value {
  font-size: 14px;
  color: #333;
  word-break: break-all;
}
@media (max-width: 900px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
