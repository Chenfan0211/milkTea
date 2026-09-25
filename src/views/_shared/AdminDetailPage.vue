<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NButton, NCard, NEmpty, NSpin } from 'naive-ui';
import type { VNodeChild } from 'vue';

export interface DetailField {
  label: string;
  key?: string;
  render?: (row: any) => VNodeChild | string | number;
}

export interface DetailGroup {
  title?: string;
  fields: DetailField[];
}

interface Props {
  title: string;
  backPath: string;
  groups: DetailGroup[];
  fetchRow: () => any | null | Promise<any | null>;
}

const props = defineProps<Props>();
const route = useRoute();
const router = useRouter();

const loading = ref(false);
const row = ref<any>(null);

function isVNode(field: DetailField): boolean {
  const v = display(field);
  return v != null && typeof v === 'object' && !Array.isArray(v);
}

function display(field: DetailField): VNodeChild | string | number {
  if (!row.value) return '—';
  if (field.render) return field.render(row.value);
  const val = field.key ? row.value[field.key] : null;
  return val == null || val === '' ? '—' : val;
}

async function load() {
  loading.value = true;
  try {
    row.value = await props.fetchRow();
  } finally {
    loading.value = false;
  }
}

function goBack() {
  if (window.history.length > 1) {
    router.back();
  } else {
    router.push(props.backPath);
  }
}

watch(() => route.query.id, load, { immediate: true });

const hasRow = computed(() => row.value != null);
</script>

<template>
  <div class="admin-detail-page">
    <NCard :bordered="false" class="card-wrapper">
      <template #header>
        <div class="flex items-center justify-between">
          <span>{{ title }}</span>
          <NButton size="small" @click="goBack">返回列表</NButton>
        </div>
      </template>

      <NSpin :show="loading">
        <template v-if="hasRow">
          <template v-for="(group, gi) in groups" :key="gi">
            <div v-if="gi > 0" class="detail-divider" />
            <div v-if="group.title" class="detail-group-title">{{ group.title }}</div>
            <div class="detail-grid">
              <div v-for="field in group.fields" :key="field.label" class="detail-item">
                <span class="detail-label">{{ field.label }}</span>
                <span class="detail-value"><component v-if="isVNode(field)" :is="display(field)" /><template v-else>{{ display(field) }}</template></span>
              </div>
            </div>
          </template>
        </template>
        <NEmpty v-else-if="!loading" description="数据不存在">
          <template #extra>
            <NButton size="small" @click="goBack">返回列表</NButton>
          </template>
        </NEmpty>
      </NSpin>
    </NCard>
  </div>
</template>

<style scoped>
.admin-detail-page {
  padding: 0;
}
.card-wrapper {
  border-radius: 12px;
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
