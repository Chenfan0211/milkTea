<script setup lang="ts">
defineOptions({
  name: 'auth_role'
});

import { computed, h, onMounted, ref } from 'vue';
import { NButton, NModal, NTag, NTree } from 'naive-ui';
import type { DataTableColumns, TreeOption } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import { useAdminStore } from '@/store/modules/admin';
import { useAuthStore } from '@/store/modules/auth';
import { renderDateTime } from '@/views/_shared/render';
import { fetchMenuTree, fetchRoleMenus, saveRoleMenus, type MenuNode } from '@/service/api/admin-account';

/**
 * 角色与权限（运营后台「授权中心 / 角色与权限」）。
 *
 * 需求口径（2026-09-25）：
 * - 角色可以分配菜单权限；账号绑定角色后，登录即拥有该角色的菜单；
 * - 超级管理员角色不可修改、不可删除，且默认拥有全部菜单；
 * - 只有超级管理员才有「授权中心」这个菜单的权限，其他角色都没有。
 *
 * 页面的按钮禁用只是体验层；服务端 CrudService 对 roles/grants 资源、
 * AdminRoleMenuController 对菜单权限接口都做了超管强校验。
 */

const store = useAdminStore();
const authStore = useAuthStore();
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);

const isSuper = () => authStore.userInfo.isSuper === true;

/** 菜单树（权限分配弹窗用），懒加载一次后缓存 */
const menuTree = ref<MenuNode[]>([]);
const menuLoaded = ref(false);

/** 角色码 -> 该角色已勾选的菜单数（列表列展示用，按需拉取） */
const menuCounts = ref<Record<string, number>>({});

onMounted(async () => {
  try {
    const data = await fetchMenuTree();
    menuTree.value = data.tree ?? [];
    menuLoaded.value = true;
  } catch (error: any) {
    window.$message?.error(error?.message || '加载菜单树失败');
  }
});

/** MenuNode -> NTree 的 TreeOption（key 用 code，与后端保存口径一致） */
function toTreeOptions(nodes: MenuNode[]): TreeOption[] {
  return nodes.map(node => ({
    key: node.code,
    label: node.name,
    children: node.children?.length ? toTreeOptions(node.children) : undefined
  }));
}

const treeOptions = computed(() => toTreeOptions(menuTree.value));

// ---------------- 分配权限弹窗 ----------------
const permVisible = ref(false);
const permRole = ref<any>(null);
const permChecked = ref<string[]>([]);
const permLoading = ref(false);

async function openPermissions(row: any) {
  if (!menuLoaded.value) {
    window.$message?.warning('菜单数据尚未加载完成，请稍后重试');
    return;
  }
  permRole.value = row;
  permLoading.value = true;
  permVisible.value = true;
  try {
    permChecked.value = await fetchRoleMenus(row.id);
  } catch (error: any) {
    permChecked.value = [];
    window.$message?.error(error?.message || '读取角色权限失败');
  } finally {
    permLoading.value = false;
  }
}

async function submitPermissions() {
  if (!permRole.value) return;
  try {
    await saveRoleMenus(permRole.value.id, permChecked.value);
    window.$message?.success('权限已保存，使用该角色的账号重新登录后生效');
    permVisible.value = false;
    reload();
  } catch (error: any) {
    window.$message?.error(error?.message || '保存权限失败');
  }
}

function reload() {
  listRef.value?.reload();
}

// ---------------- 列表配置 ----------------

const columns: DataTableColumns<any> = [
  { title: '角色编码', key: 'code', width: 140 },
  { title: '名称', key: 'name', width: 140 },
  {
    title: '类型',
    key: 'isBuiltin',
    width: 120,
    render: (row: any) =>
      row.isBuiltin
        ? h(NTag, { size: 'small', type: 'warning' }, { default: () => '内置角色' })
        : h(NTag, { size: 'small' }, { default: () => '自定义' })
  },
  { title: '数据范围', key: 'dataScope', width: 110, render: (row: any) => row.dataScope || '—' },
  {
    // 超管直通全部菜单，因此不显示数字，用「全部」表达，避免让人误以为可以改
    title: '菜单权限',
    key: 'menuCount',
    width: 120,
    render: (row: any) => {
      if (row.isBuiltin) return h(NTag, { size: 'small', type: 'success' }, { default: () => '全部菜单' });
      const count = menuCounts.value[row.id];
      return count == null ? '—' : `${count} 项`;
    }
  },
  {
    title: '绑定账号',
    key: 'boundAccountCount',
    width: 100,
    render: (row: any) => `${row.boundAccountCount ?? 0} 个`
  },
  { title: '创建时间', key: 'createTime', render: renderDateTime('createTime'), width: 160 }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '角色', placeholder: '角色名称' },
  {
    key: 'dataScope',
    label: '数据范围',
    type: 'select',
    options: [
      { label: '平台级', value: '平台级' },
      { label: '门店级', value: '门店级' }
    ]
  }
];

const formFields: FormField[] = [
  { key: 'code', label: '编码', rules: [{ required: true, message: '请输入角色编码', trigger: ['input', 'blur'] }] },
  { key: 'name', label: '名称', rules: [{ required: true, message: '请输入角色名称', trigger: ['input', 'blur'] }] },
  {
    key: 'dataScope',
    label: '数据范围',
    type: 'select',
    options: [
      { label: '平台级', value: 'all' },
      { label: '门店级', value: 'store' }
    ]
  }
];

const toolbar: RowAction[] = [{ label: '新增角色', type: 'primary', modal: 'add', visible: () => isSuper() }];

const rowActions: RowAction[] = [
  {
    label: '分配权限',
    type: 'primary',
    // 内置角色（超管）直通全部菜单，不给分配入口；后端同样拒绝
    visible: (row: any) => isSuper() && !row.isBuiltin,
    handler: (row: any) => openPermissions(row)
  },
  {
    label: '编辑',
    type: 'primary',
    modal: 'edit',
    visible: (row: any) => isSuper() && !row.isBuiltin
  },
  {
    label: '删除',
    type: 'error',
    visible: (row: any) => isSuper() && !row.isBuiltin,
    reasonPrompt: '确认删除该角色？（请填写备注）',
    handler: async (row: any, reason) => {
      await store.remove('roles', row.id, '授权中心', 'name', reason);
      reload();
    }
  }
];

const config: AdminListConfig = {
  title: '角色与权限',
  remoteKey: 'roles',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => {
    const res = await store.queryRemote('roles', search, page, pageSize);
    // 顺带刷新各角色的菜单数：列表「菜单权限」列需要它
    await Promise.all(
      (res.data ?? [])
        .filter((row: any) => !row.isBuiltin)
        .map(async (row: any) => {
          try {
            const codes = await fetchRoleMenus(row.id);
            menuCounts.value = { ...menuCounts.value, [row.id]: codes.length };
          } catch {
            // 单个角色读取失败不影响整页列表；该行显示 —
          }
        })
    );
    return res;
  },
  form: {
    title: '角色',
    fields: formFields,
    onSubmit: async (data, editing) => {
      if (editing) await store.update('roles', editing.id, data, '授权中心', 'name');
      else await store.add('roles', data, '授权中心', 'name');
      reload();
    }
  }
};
</script>

<template>
  <div class="page-root">
    <AdminListPage ref="listRef" :config="config" />

    <NModal v-model:show="permVisible" preset="card" class="w-520px">
      <template #header>
        <span>分配菜单权限 —— {{ permRole?.name }}</span>
      </template>
      <div v-if="permLoading" class="perm-loading">正在读取权限…</div>
      <template v-else>
        <div class="perm-hint">
          勾选该角色可以访问的菜单。账号绑定此角色后，登录即拥有这些菜单；未勾选的菜单会被隐藏且直接访问会跳 403。
        </div>
        <div class="perm-tree">
          <NTree
            v-model:checked-keys="permChecked"
            :data="treeOptions"
            checkable
            cascade
            block-line
            default-expand-all
            selectable
          />
        </div>
      </template>
      <div class="mt-16px flex flex-wrap justify-end gap-12px">
        <NButton @click="permVisible = false">取消</NButton>
        <NButton type="primary" :loading="permLoading" @click="submitPermissions">保存</NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.perm-hint {
  font-size: 12px;
  color: #9b9b96;
  line-height: 1.6;
  margin-bottom: 12px;
}
.perm-tree {
  max-height: 380px;
  overflow-y: auto;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 8px 4px;
}
.perm-loading {
  padding: 24px 0;
  text-align: center;
  color: #9b9b96;
}
</style>
