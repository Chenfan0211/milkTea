<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { fetchQuickLoginConfig } from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { $t } from '@/locales';

defineOptions({
  name: 'PwdLogin'
});

const authStore = useAuthStore();
const { formRef, validate } = useNaiveForm();

interface FormModel {
  userName: string;
  password: string;
}

// 表单默认留空：原先预填 admin/123456 是为了演示方便，
// 但弱口令写在前端源码里会随产物直接暴露，正式环境必须清掉。
const model: FormModel = reactive({
  userName: '',
  password: ''
});

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
  // inside computed to make locale reactive, if not apply i18n, you can define it without computed
  const { formRules } = useFormRules();

  return {
    userName: formRules.userName,
    password: formRules.pwd
  };
});

/** 水平居中弹窗：登录相关错误统一走这里，保证中文文案可见 */
function showLoginError(message: string) {
  window.$dialog?.error({
    title: '登录失败',
    content: message || '登录失败，请稍后重试',
    class: 'login-error-dialog-center',
    style: { textAlign: 'center' },
    maskClosable: false,
    closable: false,
    positiveText: '我知道了'
  });
}

async function handleSubmit() {
  try {
    await validate();
  } catch {
    // 表单校验未通过（用户名/密码为空等），给出居中提示，避免用户以为按钮没反应
    showLoginError('请输入用户名和密码');
    return;
  }

  await authStore.login(model.userName, model.password);
}

// ---------------- 快捷登录（一键免密） ----------------
//
// 【设计口径】旧版把 4 个账号的弱口令（123456）硬编码在这个文件里，
// 口令会随构建产物一起发布出去 —— 任何人拿到 dist 就能读到 4 个后台口令。
// 现在改为：前端只提交账号标识，口令由服务端保管（QuickLoginController）。
//
// 为什么按钮写死而不是从接口拉账号列表：
//   快捷入口是「演示/联调」用途，账号是固定的 4 个角色视图；
//   写死的好处是登录页首屏不需要额外请求。但**开关**仍走接口
//   （/auth/quick-login/enabled）—— 这样正式上线时后端置
//   app.quick-login.enabled=false 就能一键摘掉整个入口，不必重新发版。

type QuickLoginKey = 'super' | 'operation' | 'finance' | 'audit';

interface QuickLoginAccount {
  key: QuickLoginKey;
  /** i18n key，避免中英文案写死在逻辑里 */
  labelKey: App.I18n.I18nKey;
}

const quickLoginAccounts: QuickLoginAccount[] = [
  { key: 'super', labelKey: 'page.login.pwdLogin.quickLoginSuper' },
  { key: 'operation', labelKey: 'page.login.pwdLogin.quickLoginOperation' },
  { key: 'finance', labelKey: 'page.login.pwdLogin.quickLoginFinance' },
  { key: 'audit', labelKey: 'page.login.pwdLogin.quickLoginAudit' }
];

/** 快捷入口是否可用。默认关闭，等接口确认后再渲染，避免「显示了却点不通」。 */
const quickLoginEnabled = ref(false);

/** 正在请求中的快捷账号（用于禁用按钮，防止连点造成并发登录） */
const pendingQuickKey = ref<QuickLoginKey | null>(null);

onMounted(async () => {
  const { data, error } = await fetchQuickLoginConfig();

  if (!error) {
    quickLoginEnabled.value = Boolean(data?.enabled);
  }
  // 接口不可用时静默隐藏快捷区：密码登录仍可用，不该因为一个附加入口
  // 给用户弹错误弹窗（登录页弹错会让人以为整个登录都坏了）。
});

/**
 * 忘记密码。
 *
 * 后端目前没有自助重置链路（改密入口在「授权中心 / 账号管理」，且只有超管可操作），
 * 所以这里给明确提示，而不是跳到一个填完也没用的表单页。
 */
function onForgetPassword() {
  window.$message?.info('请联系系统管理员在「授权中心 / 账号管理」中重置密码');
}

async function handleQuickLogin(account: QuickLoginAccount) {
  if (pendingQuickKey.value) return;

  pendingQuickKey.value = account.key;
  try {
    await authStore.quickLogin(account.key);
  } finally {
    pendingQuickKey.value = null;
  }
}

</script>

<template>
  <NForm ref="formRef" :model="model" :rules="rules" size="large" :show-label="false" @keyup.enter="handleSubmit">
    <NFormItem path="userName">
      <NInput v-model:value="model.userName" :placeholder="$t('page.login.common.userNamePlaceholder')" />
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
      />
    </NFormItem>
    <NSpace vertical :size="24">
      <div class="flex-y-center justify-between">
        <NCheckbox>{{ $t('page.login.pwdLogin.rememberMe') }}</NCheckbox>
        <NButton quaternary @click="onForgetPassword">
          {{ $t('page.login.pwdLogin.forgetPassword') }}
        </NButton>
      </div>
      <NButton type="primary" size="large" round block :loading="authStore.loginLoading" @click="handleSubmit">
        {{ $t('common.confirm') }}
      </NButton>
      <template v-if="quickLoginEnabled">
        <NDivider class="text-14px text-#666 !m-0">{{ $t('page.login.pwdLogin.otherAccountLogin') }}</NDivider>
        <div class="quick-login">
          <NButton
            v-for="item in quickLoginAccounts"
            :key="item.key"
            class="quick-login__btn"
            type="primary"
            :loading="pendingQuickKey === item.key"
            :disabled="pendingQuickKey !== null && pendingQuickKey !== item.key"
            @click="handleQuickLogin(item)"
          >
            {{ $t(item.labelKey) }}
          </NButton>
        </div>
        <p class="quick-login__hint">{{ $t('page.login.pwdLogin.quickLoginHint') }}</p>
      </template>
    </NSpace>
  </NForm>
</template>

<style scoped>
/* 快捷登录区：4 个按钮等宽铺满，窄屏一行 2 个，避免挤压变形 */
.quick-login {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.quick-login__btn {
  width: 100%;
}

.quick-login__hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #9b9b96;
}
</style>


