<script setup lang="ts">
defineOptions({
  name: 'GiftGroupManager'
});

import { ref, watch } from 'vue';
import { NButton, NInput, NInputNumber, NModal, NPopconfirm, NSpin } from 'naive-ui';
import { createGiftCardGroup, deleteGiftCardGroup, fetchGiftCardGroups, updateGiftCardGroup } from '@/service/api/crud';
import type { GiftCardGroup } from '@/service/api/crud';

interface GiftGroupDraft extends GiftCardGroup {
  saving?: boolean;
}

const props = defineProps<{
  show: boolean;
}>();

const emit = defineEmits<{
  'update:show': [boolean];
  changed: [];
}>();

const loading = ref(false);
const creating = ref(false);
const groups = ref<GiftGroupDraft[]>([]);
const newName = ref('');
const newSort = ref<number | null>(0);

async function loadGroups() {
  loading.value = true;
  try {
    const result = await fetchGiftCardGroups();
    groups.value = [...result]
      .sort((a, b) => Number(a.sort ?? 0) - Number(b.sort ?? 0))
      .map(group => ({ ...group, saving: false }));
  } catch (error: any) {
    groups.value = [];
    window.$message?.error(error?.message || '分组加载失败');
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.show,
  async show => {
    if (!show) return;
    newName.value = '';
    newSort.value = 0;
    await loadGroups();
  }
);

function close() {
  emit('update:show', false);
}

async function createGroup() {
  const name = newName.value.trim();
  if (!name) {
    window.$message?.warning('请输入分组名称');
    return;
  }
  creating.value = true;
  try {
    await createGiftCardGroup({ name, sort: Number(newSort.value ?? 0) });
    newName.value = '';
    newSort.value = 0;
    window.$message?.success('分组已新增');
    await loadGroups();
    emit('changed');
  } catch (error: any) {
    window.$message?.error(error?.message || '分组新增失败');
  } finally {
    creating.value = false;
  }
}

async function saveGroup(group: GiftGroupDraft) {
  const name = group.name.trim();
  if (!name) {
    window.$message?.warning('分组名称不能为空');
    return;
  }
  group.saving = true;
  try {
    await updateGiftCardGroup(group.code, { name, sort: Number(group.sort ?? 0) });
    window.$message?.success('分组已更新');
    await loadGroups();
    emit('changed');
  } catch (error: any) {
    window.$message?.error(error?.message || '分组更新失败');
  } finally {
    group.saving = false;
  }
}

async function removeGroup(group: GiftGroupDraft) {
  try {
    await deleteGiftCardGroup(group.code);
    window.$message?.success('分组已删除');
    await loadGroups();
    emit('changed');
  } catch (error: any) {
    window.$message?.error(error?.message || '分组删除失败');
  }
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    title="礼品卡分组管理"
    class="w-720px"
    @update:show="emit('update:show', $event)"
  >
    <NSpin :show="loading">
      <div class="group-create">
        <NInput v-model:value="newName" maxlength="30" placeholder="新分组名称" />
        <NInputNumber v-model:value="newSort" :min="0" :precision="0" placeholder="排序" />
        <NButton type="primary" :loading="creating" @click="createGroup">新增</NButton>
      </div>

      <div class="group-table">
        <div class="group-row group-row--head">
          <span>分组编码</span>
          <span>分组名称</span>
          <span>排序</span>
          <span>操作</span>
        </div>
        <div v-if="!groups.length" class="group-empty">暂无分组</div>
        <div v-for="group in groups" :key="group.code" class="group-row">
          <span class="group-code">{{ group.code }}</span>
          <NInput v-model:value="group.name" maxlength="30" />
          <NInputNumber v-model:value="group.sort" :min="0" :precision="0" />
          <div class="group-actions">
            <NButton size="small" type="primary" :loading="group.saving" @click="saveGroup(group)">保存</NButton>
            <NPopconfirm @positive-click="removeGroup(group)">
              <template #trigger>
                <NButton size="small" type="error" quaternary>删除</NButton>
              </template>
              确认删除该分组？有关联卡面时后端会拒绝。
            </NPopconfirm>
          </div>
        </div>
      </div>
    </NSpin>

    <div class="modal-actions">
      <NButton @click="close">关闭</NButton>
    </div>
  </NModal>
</template>

<style scoped>
.group-create,
.group-row {
  display: grid;
  grid-template-columns: 150px minmax(180px, 1fr) 120px 132px;
  gap: 12px;
  align-items: center;
}

.group-create {
  grid-template-columns: minmax(180px, 1fr) 120px 88px;
  margin-bottom: 18px;
  padding: 12px;
  border-radius: 8px;
  background: #fafaf8;
}

.group-row {
  min-height: 48px;
  border-bottom: 1px solid #eeeeea;
}

.group-row--head {
  min-height: 36px;
  color: #747570;
  font-size: 13px;
  font-weight: 600;
}

.group-code {
  overflow: hidden;
  color: #666762;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group-actions,
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.group-empty {
  padding: 32px 0;
  color: #9b9b96;
  text-align: center;
}

.modal-actions {
  margin-top: 20px;
  gap: 12px;
}
</style>