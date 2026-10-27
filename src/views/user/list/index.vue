<script setup lang="ts">
defineOptions({
  name: 'UserList'
});

import { h, onMounted, shallowRef } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { NAvatar } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import type { RoleType } from '@/store/modules/admin';
import defaultAvatar from '@/assets/images/profile-avatar.jpg';
import { renderTag, statusMap, renderDateTime, formatFen } from '@/views/_shared/render';
import { fetchLeaders, fetchUserLeaders, assignUserLeader, removeUserLeader } from '@/service/api/crud';

const store = useAdminStore();

const roleOptions = [
  { label: '门店', value: 'store' },
  { label: '投资人', value: 'investor' },
  { label: '资源方', value: 'resource' },
  { label: '团长', value: 'leader' }
];

/** 团长用户ID -> 团长主体ID（用于展示与解绑） */
const leaderSubjectMap = shallowRef<Record<number, number>>({});
/** 用户ID -> 归属团长用户ID */
const userLeaderMap = shallowRef<Record<number, number>>({});
/** 团长下拉选项（归属团长弹窗用，预加载缓存） */
const leaderListOptions = shallowRef<{ label: string; value: string }[]>([]);

async function refreshLeaderMaps() {
  try {
    const [leaders, userLeaders] = await Promise.all([
      fetchLeaders({ current: 1, size: 1000 }),
      fetchUserLeaders({ current: 1, size: 1000 })
    ]);
    const leaderMap: Record<number, number> = {};
    (leaders?.records ?? []).forEach((s: any) => {
      if (s.boundUserId != null && s.id != null) leaderMap[Number(s.boundUserId)] = Number(s.id);
    });
    const ulMap: Record<number, number> = {};
    (userLeaders?.records ?? []).forEach((ul: any) => {
      if (ul.userId != null && ul.leaderUserId != null) ulMap[Number(ul.userId)] = Number(ul.leaderUserId);
    });
    leaderSubjectMap.value = leaderMap;
    userLeaderMap.value = ulMap;
    leaderListOptions.value = (leaders?.records ?? [])
      .filter((s: any) => s.boundUserId != null)
      .map((s: any) => ({
        label: `${s.name || '团长'}${s.boundUserNick ? `（${s.boundUserNick}）` : ''}`,
        value: String(s.boundUserId)
      }));
  } catch {
    // 团长映射加载失败不阻塞用户列表展示
  }
}

const isLeaderUser = (row: any) => leaderSubjectMap.value[Number(row?.id)] != null;

const roleLabel = (v: string | null) => {
  if (!v) return '未绑定';
  return roleOptions.find(o => o.value === v)?.label ?? v;
};
/** 经营角色列：主角色为空但用户是团长时显示「团长」 */
const effectiveRoleLabel = (row: any) => {
  const base = roleLabel(row.businessRole ?? null);
  if (base === '未绑定' && isLeaderUser(row)) return '团长';
  return base;
};

const genderMap = statusMap({
  male: ['男', 'info'],
  female: ['女', 'success'],
  unknown: ['未知', 'default']
});

const columns: DataTableColumns<any> = [
  {
    title: '头像',
    key: 'avatar',
    width: 80,
    render: (row: any) =>
      h(NAvatar, { round: true, size: 40, src: row.avatar || defaultAvatar, style: 'display:block' })
  },
  { title: '用户ID', key: 'id', width: 110, render: (row: any) => row.id ?? '—' },
  { title: '手机号', key: 'phone', width: 130, render: (row: any) => row.phone || '—' },
  { title: '昵称', key: 'nickName', width: 140 },
  {
    title: '性别',
    key: 'gender',
    width: 80,
    render: renderTag('gender', genderMap)
  },
  { title: '生日', key: 'birthday', width: 120, render: (row: any) => row.birthday || '—' },
  {
    title: '等级',
    key: 'vipLevel',
    width: 90,
    render: (row: any) => row.vipLevel || '—'
  },
  { title: '时光币', key: 'points', width: 90, align: 'right', render: (row: any) => String(row.points ?? 0) },
  { title: '储值金额', key: 'balance', width: 88, align: 'right', render: (row: any) => `¥${formatFen(row.balance)}` },
  {
    title: '经营角色',
    key: 'businessRole',
    width: 95,
    render: (row: any) => effectiveRoleLabel(row)
  },
  { title: '绑定主体', key: 'boundSubjectName', minWidth: 140, render: (row: any) => row.boundSubjectName || '—' },
  { title: '注册时间', key: 'createTime', width: 170, render: renderDateTime('createTime') }
];

const searchFields: SearchField[] = [
  { key: 'phone', label: '手机号', placeholder: '请输入手机号' },
  { key: 'nickName', label: '昵称', placeholder: '微信昵称' },
  { key: 'eq_id', label: '用户ID', placeholder: '请输入用户ID' }
];

const rowActions: RowAction[] = [
  {
    label: '绑定',
    type: 'info',
    visible: row => !row.businessRole && !isLeaderUser(row),
    cascadePicker: {
      title: '绑定主体',
      steps: [
        {
          label: '经营者类型',
          options: () => [
            { label: '门店', value: 'store' },
            { label: '投资人', value: 'investor' },
            { label: '资源方', value: 'resource' },
            { label: '团长', value: 'leader' }
          ]
        },
        {
          label: '选择主体',
          options: type => {
            if (type === 'leader') return [{ label: '确认设为团长（无需选择主体）', value: '__leader__' }];
            return store.subjects
              .filter(s => s.type === type && !s.boundUserId && !s.deleted)
              .map(s => ({ label: s.name, value: s.code }));
          }
        }
      ],
      handler: async (row, values) => {
        if (values.first === 'leader') {
          await store.bindUserRole(row.id, 'leader', '__leader__', row.nickName || '');
          await refreshLeaderMaps();
          return;
        }
        const subj = store.subjects.find(x => x.code === values.second);
        if (subj) await store.bindUserRole(row.id, values.first as RoleType, subj.code, subj.name);
      }
    }
  },
  {
    label: '归属团长',
    type: 'info',
    visible: row => !isLeaderUser(row),
    cascadePicker: {
      title: '设置归属团长',
      steps: [
        {
          label: '团长用户',
          options: () => leaderListOptions.value
        },
        {
          label: '操作',
          options: () => [{ label: '确认归属', value: 'confirm' }]
        }
      ],
      handler: async (row, values) => {
        await assignUserLeader(Number(row.id), Number(values.first));
        window.$message?.success('归属团长已设置');
        await refreshLeaderMaps();
      }
    }
  },
  {
    label: '取消归属',
    type: 'warning',
    reasonPrompt: '确认移除该用户的归属团长？',
    handler: async (row, _reason) => {
      await removeUserLeader(Number(row.id));
      await refreshLeaderMaps();
    },
    visible: row => userLeaderMap.value[Number(row.id)] != null
  },
  {
    label: '解绑',
    type: 'error',
    reasonPrompt: '确认解绑该用户的经营角色？（请填写备注）',
    handler: async (row, reason) => {
      if (isLeaderUser(row)) {
        const subjectId = leaderSubjectMap.value[Number(row.id)];
        if (subjectId != null) {
          const { removeLeader } = await import('@/service/api/crud');
          await removeLeader(Number(subjectId));
        }
        await refreshLeaderMaps();
        return;
      }
      await store.unbindUserRole(row.id, reason);
    },
    visible: row => Boolean(row.businessRole) || isLeaderUser(row)
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该用户？删除后列表不再展示（逻辑删除），请填写备注',
    handler: async (row, reason) => await store.remove('users', row.id, '用户管理', 'nickName', reason)
  }
];

onMounted(refreshLeaderMaps);

const config: AdminListConfig = {
  title: '用户列表',
  remoteKey: 'users',
  // 「绑定主体」列需把 app_user.bound_subject_id（数字）解析成主体名，
  // 必须先把 subjects 镜像加载好，否则该列恒为 —（详见 store.normalizeRemoteRow）
  remoteDeps: ['subjects'],
  columns,
  searchFields,
  toolbar: [],
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('users', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
