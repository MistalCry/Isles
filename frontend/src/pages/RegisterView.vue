<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { resolveErrorMessage } from '@/api/client'
import { validateNickname, validatePassword, validateUsername } from '@/utils/validators'
import { APP_NAME } from '@/constants/app'
import { useSiteStore } from '@/stores/site'
import AuthLayout from '@/layouts/AuthLayout.vue'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const site = useSiteStore()
/** 平台关闭注册时直接拦住，避免填完表单才报错 */
const registrationEnabled = computed(() => site.config.registrationEnabled)

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')

const form = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  agree: false,
})

const redirectTarget = computed(() => {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/feed'
})

const rules: FormRules = {
  username: [
    { required: true, message: t('auth.login.usernameRequired'), trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const message = validateUsername(String(value ?? ''))
        if (message) callback(new Error(message))
        else callback()
      },
      trigger: 'blur',
    },
  ],
  nickname: [
    {
      validator: (_rule, value, callback) => {
        const message = validateNickname(String(value ?? ''))
        if (message) callback(new Error(message))
        else callback()
      },
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: t('auth.register.passwordRequired'), trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const message = validatePassword(String(value ?? ''))
        if (message) callback(new Error(message))
        else callback()
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: t('auth.register.confirmRequired'), trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.password) callback(new Error(t('auth.register.passwordMismatch')))
        else callback()
      },
      trigger: 'blur',
    },
  ],
  agree: [
    {
      validator: (_rule, value, callback) => {
        if (value !== true) callback(new Error(t('auth.register.termsRequired')))
        else callback()
      },
      trigger: 'change',
    },
  ],
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.register({
      username: form.username.trim(),
      password: form.password,
      nickname: form.nickname.trim() || undefined,
    })
    ElMessage.success(t('auth.register.success'))
    await router.replace(redirectTarget.value)
  } catch (err) {
    errorMessage.value = resolveErrorMessage(err, t('auth.register.failed'))
  } finally {
    submitting.value = false
  }
}

function showPolicyTip(): void {
  ElMessage.info(t('auth.register.termsHint'))
}
</script>

<template>
  <AuthLayout>
    <div class="auth-card">
      <header class="auth-card__header">
        <h1>{{ t('auth.register.title', { app: APP_NAME }) }}</h1>
        <p>{{ t('auth.register.subtitle') }}</p>
      </header>

      <el-alert
        v-if="!registrationEnabled"
        :title="t('auth.register.closedTitle')"
        :description="t('auth.register.closedHint')"
        type="warning"
        :closable="false"
        show-icon
        class="auth-card__alert"
      />

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
        class="auth-card__alert"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="handleSubmit">
        <el-form-item :label="t('common.username')" prop="username">
          <el-input v-model="form.username" :placeholder="t('auth.usernameHint')" autocomplete="username" />
        </el-form-item>
        <el-form-item :label="t('auth.nicknameOptional')" prop="nickname">
          <el-input v-model="form.nickname" maxlength="20" :placeholder="t('auth.nicknameHint')" />
        </el-form-item>
        <el-form-item :label="t('common.password')" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="t('auth.passwordHint')"
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item :label="t('auth.confirmPassword')" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            :placeholder="t('auth.confirmPasswordPlaceholder')"
            autocomplete="new-password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>
        <el-form-item prop="agree">
          <el-checkbox v-model="form.agree">
            {{ t('auth.register.agreePrefix') }}
            <button type="button" class="link auth-card__policy" @click.prevent="showPolicyTip">{{ t('auth.register.terms') }}</button>
          </el-checkbox>
        </el-form-item>

        <el-button type="primary" class="auth-card__submit" :loading="submitting"
          :disabled="!registrationEnabled"
          @click="handleSubmit"
        >
          {{ t('auth.register.submit') }}
        </el-button>
      </el-form>

      <p class="auth-card__switch">
        {{ t('auth.register.hasAccount') }}
        <RouterLink :to="{ name: 'login', query: route.query }" class="link">{{ t('auth.register.toLogin') }}</RouterLink>
      </p>
      <RouterLink to="/feed" class="auth-card__back">{{ t('auth.login.browse') }}</RouterLink>
    </div>
  </AuthLayout>
</template>

<style scoped lang="scss">
.auth-card {
  width: 100%;
  max-width: 420px;
}

.auth-card__header {
  margin-bottom: var(--isles-space-5);

  h1 {
    font-size: var(--isles-font-xl);
    font-weight: 600;
  }

  p {
    margin-top: var(--isles-space-1);
    color: var(--isles-text-muted);
    font-size: var(--isles-font-sm);
  }
}

.auth-card__alert {
  margin-bottom: var(--isles-space-4);
}

.auth-card__submit {
  width: 100%;
  margin-top: var(--isles-space-2);
}

.auth-card__switch {
  margin-top: var(--isles-space-4);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  text-align: center;
}

.auth-card__back {
  display: block;
  margin-top: var(--isles-space-3);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  text-align: center;
}

.auth-card__policy {
  padding: 0;
  background: transparent;
  border: none;
  font-size: inherit;
}
</style>
