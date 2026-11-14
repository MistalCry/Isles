<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { resolveErrorMessage } from '@/api/client'
import { validateNickname, validatePassword, validateUsername } from '@/utils/validators'
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
    { required: true, message: '请设置密码', trigger: 'blur' },
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
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.password) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
  agree: [
    {
      validator: (_rule, value, callback) => {
        if (value !== true) callback(new Error('请先阅读并同意用户协议与隐私政策'))
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
    ElMessage.success('注册成功，欢迎来到群岛')
    await router.replace(redirectTarget.value)
  } catch (err) {
    errorMessage.value = resolveErrorMessage(err, '注册失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

function showPolicyTip(): void {
  ElMessage.info('用户协议与隐私政策将在上线前完成法务确认')
}
</script>

<template>
  <AuthLayout>
    <div class="auth-card">
      <header class="auth-card__header">
        <h1>注册{{ APP_NAME }}</h1>
        <p>创建账号，开始探索属于你的岛屿。</p>
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
          <el-input v-model="form.username" placeholder="3–20 位字母、数字、下划线或连字符" autocomplete="username" />
        </el-form-item>
        <el-form-item label="昵称（选填）" prop="nickname">
          <el-input v-model="form.nickname" maxlength="20" placeholder="展示给其他岛民的名字" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="至少 8 位，包含字母与数字"
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            placeholder="再次输入密码"
            autocomplete="new-password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>
        <el-form-item prop="agree">
          <el-checkbox v-model="form.agree">
            我已阅读并同意
            <button type="button" class="link auth-card__policy" @click.prevent="showPolicyTip">《用户协议》与《隐私政策》</button>
          </el-checkbox>
        </el-form-item>

        <el-button type="primary" class="auth-card__submit" :loading="submitting" @click="handleSubmit">
          注册并登录
        </el-button>
      </el-form>

      <p class="auth-card__switch">
        已经有账号？
        <RouterLink :to="{ name: 'login', query: route.query }" class="link">直接登录</RouterLink>
      </p>
      <RouterLink to="/feed" class="auth-card__back">先随便逛逛 →</RouterLink>
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
