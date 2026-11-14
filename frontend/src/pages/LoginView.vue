<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { resolveErrorMessage } from '@/api/client'
import { validateUsername } from '@/utils/validators'
import { APP_NAME } from '@/constants/app'
import AuthLayout from '@/layouts/AuthLayout.vue'

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
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const message = validateUsername(String(value ?? ''))
        if (message) callback(new Error(message))
        else callback()
      },
      trigger: 'blur',
    },
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.login({ username: form.username.trim(), password: form.password })
    ElMessage.success('欢迎回来')
    await router.replace(redirectTarget.value)
  } catch (err) {
    errorMessage.value = resolveErrorMessage(err, '登录失败，请检查用户名和密码')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <div class="auth-card">
      <header class="auth-card__header">
        <h1>登录{{ APP_NAME }}</h1>
        <p>回到你的岛屿，看看岛民们的新动态。</p>
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
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
            autocomplete="current-password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-button type="primary" class="auth-card__submit" :loading="submitting" @click="handleSubmit">
          登录
        </el-button>
      </el-form>

      <p class="auth-card__switch">
        还没有账号？
        <RouterLink :to="{ name: 'register', query: route.query }" class="link">立即注册</RouterLink>
      </p>
      <RouterLink to="/feed" class="auth-card__back">先随便逛逛 →</RouterLink>
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
