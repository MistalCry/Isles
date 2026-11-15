<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { resolveErrorMessage } from '@/api/client'
import { validateUsername } from '@/utils/validators'
import { APP_NAME } from '@/constants/app'
import AuthLayout from '@/layouts/AuthLayout.vue'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')

const form = reactive({
  username: '',
  password: '',
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
  password: [{ required: true, message: t('auth.login.passwordRequired'), trigger: 'blur' }],
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.login({ username: form.username.trim(), password: form.password })
    ElMessage.success(t('auth.login.welcome'))
    await router.replace(redirectTarget.value)
  } catch (err) {
    errorMessage.value = resolveErrorMessage(err, t('auth.login.failed'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <div class="auth-card">
      <header class="auth-card__header">
        <h1>{{ t('auth.login.title', { app: APP_NAME }) }}</h1>
        <p>{{ t('auth.login.subtitle') }}</p>
      </header>

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
          <el-input v-model="form.username" :placeholder="t('auth.login.usernameRequired')" autocomplete="username" />
        </el-form-item>
        <el-form-item :label="t('common.password')" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="t('auth.login.passwordRequired')"
            autocomplete="current-password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-button type="primary" class="auth-card__submit" :loading="submitting" @click="handleSubmit">{{ t('common.login') }}</el-button>
      </el-form>

      <p class="auth-card__switch">
        {{ t('auth.login.noAccount') }}
        <RouterLink :to="{ name: 'register', query: route.query }" class="link">{{ t('auth.login.registerNow') }}</RouterLink>
      </p>
      <RouterLink to="/feed" class="auth-card__back">{{ t('auth.login.browse') }}</RouterLink>
    </div>
  </AuthLayout>
</template>

<style scoped lang="scss">
.auth-card {
  width: 100%;
  max-width: 400px;
}

.auth-card__header {
  margin-bottom: var(--isles-space-6);

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
</style>
