<script setup lang="ts">
defineOptions({
  name: 'auth_account'
});

import { h, onMounted, reactive, ref } from 'vue';
import { NButton, NCheckbox, NInput, NModal, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import { useAuthStore } from '@/store/modules/auth';
import { renderDateTime } from '@/views/_shared/render';
import {
  assignAdminAccountRoles,
  createAdminAccount,
  deleteAdminAccount,
  fetchAdminAccounts,
  fetchRoleOptions,
  resetAdminAccountPassword,
  updateAdminAccount
} from '@/service/api/admin-account';

/**
 * 账号管理（运营后台「授权中心 / 账号管理」）。
 *
 * 需求口径（2026-09-25）：
 * - 这里的账号是**手动新建的后台登录账号**（用户名 + 初始密码），
 *   不是小程序的 app_user —— 角色绑定的是「能登录运营后台的人」；
 * - 账号绑定角色后，用该账号登录即拥有对应角色的菜单权限；
 * - 超级管理员账号的信息不可修改，仅允许由超管本人重置密码，
 *   且同时只允许存在一个超管账号。
 *
 * <p>本页只做体验层限制（超管行不渲染危险按钮）；
 * 真正的拦截在服务端 AdminRbacGuard —— 前端藏按钮不算权限控制。
 */

const authStore = useAuthStore();
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);

/** 是否超管：决定危险操作按钮是否渲染 */
const isSuper = () => authStore.userInfo.isSuper === true;

/** 角色下拉选项（异步加载，供表单与筛选共用） */
const roleOptions = ref<{ label: string; value: string }[]>([]);

onMounted(async () => {
  try {
    const roles = await fetchRoleOptions();
    roleOptions.value = roles.map(item => ({
      // 内置角色（超管）标注出来，避免运营误以为可以随意分配
      label: item.isBuiltin ? `${item.name}（内置）` : item.name,
      value: item.code
    }));
  } catch (error: any) {
    window.$message?.error(error?.message || '加载角色选项失败');
  }
});

// ---------------- 重置密码弹窗 ----------------
// 为什么单独做一个弹窗而不是 window.prompt：
// prompt 在部分浏览器/内嵌 webview 下被禁用，且无法做长度校验与二次确认，
// 而这个操作是超管账号唯一允许的写操作，出错成本高。
const pwdVisible = ref(false);
const pwdRow = ref<any>(null);
const pwdModel = reactive({ password: '', confirm: '' });

function openResetPassword(row: any) {
  pwdRow.value = row;
  pwdModel.password = '';
  pwdModel.confirm = '';
  pwdVisible.value = true;
}

async function submitResetPassword() {
  if (!pwdRow.value) return;
  if (pwdModel.password.length < 6) {
    window.$message?.warning('密码长度不能少于 6 位');
    return;
  }
  if (pwdModel.password !== pwdModel.confirm) {
    window.$message?.warning('两次输入的密码不一致');
    return;
  }
  try {
    await resetAdminAccountPassword(pwdRow.value.id, pwdModel.password);
    window.$message?.success(`账号「${pwdRow.value.username}」密码已重置`);
    pwdVisible.value = false;
  } catch (error: any) {
    window.$message?.error(error?.message || '重置密码失败');
  }
}

// ---------------- 分配角色弹窗 ----------------
// 账号页只负责「账号 ↔ 角色」这一层；「角色 ↔ 菜单」在「角色与权限」页维护。
// 两者分开的原因：一个账号可绑多角色，一个角色可给多账号，
// 把两张关系表塞进一个弹窗会让「我改了谁」变得难以理解。
const roleVisible = ref(false);
const roleRow = ref<any>(null);
const rolePicked = ref<string[]>([]);

function openAssignRoles(row: any) {
  roleRow.value = row;
  rolePicked.value = Array.isArray(row.roleCodes) ? [...row.roleCodes] : [];
  roleVisible.value = true;
}

async function submitAssignRoles() {
  if (!roleRow.value) return;
  try {
    await assignAdminAccountRoles(roleRow.value.id, rolePicked.value);
    window.$message?.success('角色已更新，该账号下次登录即生效');
    roleVisible.value = false;
    reload();
  } catch (error: any) {
    window.$message?.error(error?.message || '分配角色失败');
  }
}

function reload() {
  listRef.value?.reload();
}

// ---------------- 列表配置 ----------------

const columns: DataTableColumns<any> = [
  { title: '用户名', key: 'username', width: 140 },
  { title: '昵称', key: 'nickName', width: 140, render: (row: any) => row.nickName || '—' },
  {
    title: '角色',
    key: 'roleNames',
    minWidth: 200,
    render: (row: any) => {
      const names: string[] = row.roleNames ?? [];
      if (!names.length) return '—';
      return h(
        'div',
        { style: 'display:flex;flex-wrap:wrap;gap:4px' },
        names.map(name => h(NTag, { size: 'small', type: 'info' }, { default: () => name }))
      );
    }
  },
  {
    title: '账号类型',
    key: 'isSuper',
    width: 130,
    render: (row: any) =>
      row.isSuper ? h(NTag, { size: 'small', type: 'warning' }, { default: () => '超级管理员' }) : '普通账号'
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row: any) =>
      row.status === 0
        ? h(NTag, { size: 'small', type: 'error' }, { default: () => '已停用' })
        : h(NTag, { size: 'small', type: 'success' }, { default: () => '正常' })
  },
  { title: '创建时间', key: 'createTime', render: renderDateTime('createTime'), width: 160 }
];

const searchFields: SearchField[] = [
  { key: 'username', label: '用户名', placeholder: '登录用户名' },
  { key: 'nickName', label: '昵称', placeholder: '昵称' }
];

const toolbar: RowAction[] = [{ label: '新增账号', type: 'primary', modal: 'add', visible: () => isSuper() }];

const formFields: FormField[] = [
  {
    key: 'username',
    label: '用户名',
    placeholder: '字母开头，3-32 位字母/数字/下划线',
    rules: [
      { required: true, message: '请输入用户名', trigger: ['input', 'blur'] },
      {
        pattern: /^[A-Za-z][A-Za-z0-9_]{2,31}$/,
        message: '需以字母开头，由字母/数字/下划线组成，长度 3-32 位',
        trigger: ['input', 'blur']
      }
    ]
  },
  { key: 'nickName', label: '昵称', placeholder: '不填则默认与用户名相同' },
  {
    key: 'password',
    label: '初始密码',
    type: 'password',
    placeholder: '至少 6 位；创建后可在列表中重置',
    rules: [
      { required: true, message: '请输入初始密码', trigger: ['input', 'blur'] },
      { min: 6, message: '密码长度不能少于 6 位', trigger: ['input', 'blur'] }
    ]
  },
  {
    key: 'roleCodes',
    label: '角色',
    type: 'multiple',
    options: () => roleOptions.value,
    placeholder: '决定该账号登录后可见的菜单'
  }
];

const rowActions: RowAction[] = [
  {
    label: '分配角色',
    type: 'primary',
    visible: (row: any) => isSuper() && row.mutable !== false,
    handler: (row: any) => openAssignRoles(row)
  },
  {
    label: '编辑',
    type: 'primary',
    modal: 'edit',
    visible: (row: any) => isSuper() && row.mutable !== false
  },
  {
    label: '重置密码',
    type: 'warning',
    // 超管账号同样允许：这是需求里唯一放行的写操作
    // （「这个账号只能修改密码，也只能有超级管理员去修改」）
    visible: () => isSuper(),
    handler: (row: any) => openResetPassword(row)
  },
  {
    label: '删除',
    type: 'error',
    // 超管行不渲染；后端 AdminRbacGuard 也会二次拒绝
    visible: (row: any) => isSuper() && row.mutable !== false,
    reasonPrompt: '删除后该账号无法再登录，确认删除？（请填写备注）',
    handler: async (row: any) => {
      await deleteAdminAccount(row.id);
      window.$message?.success(`账号「${row.username}」已删除`);
      reload();
    }
  }
];

const config: AdminListConfig = {
  title: '账号管理',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => {
    const res = await fetchAdminAccounts({ current: page, size: pageSize, ...search });
    return { data: res.records ?? [], total: res.total ?? 0 };
  },
  form: {
    title: '后台账号',
    fields: formFields,
    /**
     * 编辑回填：只回填表单声明字段（password 不回显，留空=不改密码）。
     * 不再整行 {...row} 回填，避免 id/updateTime/deleted 等服务端字段进入提交 payload。
     */
    toFormData: (row: any) => ({
      username: row.username,
      nickName: row.nickName,
      password: '',
      roleCodes: Array.isArray(row.roleCodes) ? row.roleCodes : []
    }),
    onSubmit: async (data, editing) => {
      if (editing) {
        // 用户名不可改（它是登录凭据，改名会让审计日志产生歧义）
        await updateAdminAccount(editing.id, { nickName: data.nickName });
        await assignAdminAccountRoles(editing.id, data.roleCodes ?? []);
      } else {
        await createAdminAccount({
          username: data.username,
          password: data.password,
          nickName: data.nickName,
          roleCodes: data.roleCodes ?? []
        });
      }
      window.$message?.success(editing ? '已保存' : '账号已创建，可直接用于登录');
      reload();
    }
  }
};
</script>

<template>
  <div class="page-root">
    <AdminListPage ref="listRef" :config="config" />

    <NModal v-model:show="pwdVisible" preset="card" title="重置密码" class="w-480px">
      <div class="pwd-form">
        <div class="pwd-row">
          <span class="pwd-label">账号</span>
          <span class="pwd-value">{{ pwdRow?.username }}</span>
        </div>
        <div class="pwd-row">
          <span class="pwd-label">新密码</span>
          <NInput v-model:value="pwdModel.password" type="password" show-password-on="click" placeholder="至少 6 位" />
        </div>
        <div class="pwd-row">
          <span class="pwd-label">确认密码</span>
          <NInput v-model:value="pwdModel.confirm" type="password" show-password-on="click" placeholder="再次输入" />
        </div>
      </div>
      <div class="mt-16px flex flex-wrap justify-end gap-12px">
        <NButton @click="pwdVisible = false">取消</NButton>
        <NButton type="primary" @click="submitResetPassword">确定</NButton>
      </div>
    </NModal>

    <NModal v-model:show="roleVisible" preset="card" title="分配角色" class="w-480px">
      <div class="pwd-form">
        <div class="pwd-row">
          <span class="pwd-label">账号</span>
          <span class="pwd-value">{{ roleRow?.username }}</span>
        </div>
        <div class="pwd-row">
          <span class="pwd-label">角色</span>
          <div class="role-checks">
            <NCheckbox
              v-for="opt in roleOptions"
              :key="opt.value"
              :checked="rolePicked.includes(opt.value)"
              @update:checked="
                (checked: boolean) => {
                  rolePicked = checked
                    ? [...rolePicked, opt.value]
                    : rolePicked.filter(code => code !== opt.value);
                }
              "
            >
              {{ opt.label }}
            </NCheckbox>
          </div>
        </div>
      </div>
      <div class="hint">角色的菜单权限在「角色与权限」页维护；此处只决定该账号属于哪些角色。</div>
      <div class="mt-16px flex flex-wrap justify-end gap-12px">
        <NButton @click="roleVisible = false">取消</NButton>
        <NButton type="primary" @click="submitAssignRoles">确定</NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.pwd-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pwd-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.pwd-label {
  width: 72px;
  flex: 0 0 72px;
  line-height: 34px;
  color: #666762;
}
.pwd-value {
  line-height: 34px;
  font-weight: 600;
}
.role-checks {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.hint {
  margin-top: 12px;
  font-size: 12px;
  color: #9b9b96;
  line-height: 1.6;
}
</style>

