<script setup lang="ts">
defineOptions({
  name: 'ChannelStoreDialog'
});

import { computed, h, ref, watch } from 'vue';
import { NButton } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { bindChannelStore } from '@/service/api/crud';
import { fetchChannelStores, unbindChannelStores } from '@/service/api/subject';
import { formatDateTime } from '@/views/_shared/render';

/**
 * 资源方(渠道)门店绑定弹窗：一个组件覆盖三种模式。
 *
 * - view  ：点「绑定门店数」查看已绑定门店（只读表格）
 * - bind  ：绑定门店（上方已绑定列表 + 下方下拉，下拉已过滤掉已绑定门店）
 * - unbind：解绑门店（表格多选批量解绑 + 单行解绑）
 *
 * 已绑定列表以接口 fetchChannelStores 为准，不依赖前端 subjects 镜像，
 * 保证店铺明细完整（镜像受分页 size=200 限制）。
 */
const props = defineProps<{
  show: boolean;
  mode: 'view' | 'bind' | 'unbind';
  channel: any;
}>();

const emit = defineEmits<{
  'update:show': [boolean];
  changed: [];
}>();

const store = useAdminStore();

const loading = ref(false);
const boundStores = ref<any[]>([]);
const checkedIds = ref<number[]>([]);
const pickedStoreId = ref<string | null>(null);
const submitting = ref(false);

const titles: Record<'view' | 'bind' | 'unbind', string> = {
  view: '已绑定门店',
  bind: '绑定门店',
  unbind: '解绑门店'
};

const title = computed(() => titles[props.mode]);

async function reload() {
  if (!props.channel?.id) return;
  loading.value = true;
  try {
    boundStores.value = await fetchChannelStores(props.channel.id);
    checkedIds.value = [];
  } catch (error: any) {
    window.$message?.error(error?.message || '已绑定门店加载失败');
    boundStores.value = [];
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.show, props.channel?.id, props.mode],
  () => {
    if (props.show) {
      pickedStoreId.value = null;
      reload();
    }
  },
  { immediate: true }
);

function close() {
  emit('update:show', false);
}

/** 待绑定可选门店 = 全部门店 - 已绑定门店 */
const bindOptions = computed(() => {
  const boundIds = new Set(boundStores.value.map((s: any) => Number(s.id)));
  return store.subjects
    .filter((s: any) => s.type === 'store' && !boundIds.has(Number(s.id)))
    .map((s: any) => ({ label: s.name, value: String(s.id) }));
});

const storeColumns: DataTableColumns<any> = [
  { title: '门店编码', key: 'code', width: 120 },
  { title: '门店名称', key: 'name', minWidth: 160 },
  { title: '城市', key: 'city', width: 110, render: (row: any) => row.city || '—' },
  { title: '地址', key: 'address', minWidth: 200, render: (row: any) => row.address || '—' },
  { title: '绑定时间', key: 'createTime', width: 170, render: (row: any) => formatDateTime(row.createTime) }
];

/** 解绑模式：多选 + 单行解绑按钮 */
const unbindColumns = computed<DataTableColumns<any>>(() => [
  { type: 'selection' },
  ...storeColumns,
  {
    title: '操作',
    key: '__unbindAction__',
    width: 90,
    render: (row: any) =>
      h(
        NButton,
        { size: 'small', type: 'warning', onClick: () => doUnbindOne(Number(row.id)) },
        { default: () => '解绑' }
      )
  }
]);

async function doBind() {
  if (!pickedStoreId.value) {
    window.$message?.warning('请选择要绑定的门店');
    return;
  }
  submitting.value = true;
  try {
    await bindChannelStore(props.channel.id, Number(pickedStoreId.value));
    window.$message?.success('绑定成功');
    pickedStoreId.value = null;
    await reload();
    emit('changed');
  } catch (error: any) {
    window.$message?.error(error?.message || '绑定失败');
  } finally {
    submitting.value = false;
  }
}

async function doUnbindOne(storeId: number) {
  submitting.value = true;
  try {
    await unbindChannelStores(props.channel.id, [storeId]);
    window.$message?.success('解绑成功');
    await reload();
    emit('changed');
  } catch (error: any) {
    window.$message?.error(error?.message || '解绑失败');
  } finally {
    submitting.value = false;
  }
}

async function doBatchUnbind() {
  if (!checkedIds.value.length) {
    window.$message?.warning('请先勾选要解绑的门店');
    return;
  }
  submitting.value = true;
  try {
    await unbindChannelStores(props.channel.id, checkedIds.value);
    window.$message?.success(`已解绑 ${checkedIds.value.length} 个门店`);
    await reload();
    emit('changed');
  } catch (error: any) {
    window.$message?.error(error?.message || '批量解绑失败');
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <NModal
    :show="show"
    @update:show="emit('update:show', $event)"
    preset="card"
    :title="`${title} - ${channel?.name ?? ''}`"
    class="w-900px"
  >
    <template v-if="mode === 'bind'">
      <div class="mb-8px text-14px font-semibold text-#2F302D">选择门店绑定</div>
      <NSpace align="center">
        <NSelect
          v-model:value="pickedStoreId"
          :options="bindOptions"
          clearable
          filterable
          placeholder="选择要绑定的门店（已过滤已绑定门店）"
          class="w-320px"
        />
        <NButton type="primary" :loading="submitting" @click="doBind">绑定</NButton>
      </NSpace>
      <div class="mt-20px mb-8px text-14px font-semibold text-#2F302D">已绑定门店（{{ boundStores.length }}）</div>
    </template>

    <template v-else-if="mode === 'unbind'">
      <div class="mb-12px flex flex-wrap items-center justify-between gap-12px">
        <span class="text-13px text-#9B9B96">已绑定 {{ boundStores.length }} 个门店，勾选后可批量解绑</span>
        <NButton type="warning" :disabled="!checkedIds.length" :loading="submitting" @click="doBatchUnbind">
          批量解绑{{ checkedIds.length ? `（${checkedIds.length}）` : '' }}
        </NButton>
      </div>
    </template>

    <NDataTable
      v-if="mode === 'unbind'"
      v-model:checked-row-keys="checkedIds"
      :columns="unbindColumns"
      :data="boundStores"
      :loading="loading"
      :row-key="(row: any) => row.id"
      :bordered="false"
      :scroll-x="1000"
    />
    <NDataTable
      v-else
      :columns="storeColumns"
      :data="boundStores"
      :loading="loading"
      :row-key="(row: any) => row.id"
      :bordered="false"
      :scroll-x="1000"
    />

    <div class="mt-16px flex flex-wrap justify-end gap-12px">
      <NButton @click="close">关闭</NButton>
    </div>
  </NModal>
</template>

<style scoped></style>
