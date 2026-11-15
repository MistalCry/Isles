<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { usersApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import UserAvatar from '@/components/UserAvatar.vue'
import ImageCropUpload from '@/components/media/ImageCropUpload.vue'
import type { Gender } from '@/types/models'

/**
 * 个人资料编辑（docs/03 FR-USER-01 / FR-NAV-03）：
 * 头像、昵称、签名、性别、地区；头像走 /media/upload 后回填 URL。
 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; updated: [] }>()

const auth = useAuthStore()
const saving = ref(false)

const form = reactive({
  nickname: '',
  avatarUrl: '',
  signature: '',
  gender: 'UNKNOWN' as Gender,
  region: '',
})

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const genderOptions: Array<{ value: Gender; label: string }> = [
  { value: 'UNKNOWN', label: '不公开' },
  { value: 'MALE', label: '男' },
  { value: 'FEMALE', label: '女' },
]

function fillFromUser(): void {
  const user = auth.currentUser
  form.nickname = user?.nickname ?? ''
  form.avatarUrl = user?.avatarUrl ?? ''
  form.signature = user?.signature ?? ''
  form.gender = (user?.gender as Gender) ?? 'UNKNOWN'
  form.region = user?.region ?? ''
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) fillFromUser()
  },
)

async function save(): Promise<void> {
  const nickname = form.nickname.trim()
  if (!nickname) {
    ElMessage.warning('昵称不能为空')
    return
  }
  saving.value = true
  try {
    const updated = await usersApi.updateMe({
      nickname,
      avatarUrl: form.avatarUrl.trim() || null,
      signature: form.signature.trim() || undefined,
      gender: form.gender,
      region: form.region.trim() || undefined,
    })
    auth.currentUser = { ...(auth.currentUser ?? updated), ...updated }
    ElMessage.success('资料已保存')
    emit('updated')
    visible.value = false
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '保存失败'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="个人资料" width="min(520px, 94vw)" append-to-body>
    <div class="profile-head">
      <UserAvatar :src="form.avatarUrl || null" :name="form.nickname || auth.displayName" :size="56" />
      <div class="profile-head__meta">
        <ImageCropUpload
          v-model="form.avatarUrl"
          :aspect-ratio="1"
          :output-width="256"
          :box-width="200"
          :preview-width="72"
          shape="circle"
          label="上传头像"
          hint="建议正方形，1:1"
        />
      </div>
    </div>

    <el-form label-position="top">
      <el-form-item label="昵称" required>
        <el-input v-model="form.nickname" maxlength="32" show-word-limit />
      </el-form-item>
      <el-form-item label="签名">
        <el-input v-model="form.signature" maxlength="200" show-word-limit placeholder="介绍一下自己" />
      </el-form-item>
      <div class="profile-grid">
        <el-form-item label="性别">
          <el-select v-model="form.gender">
            <el-option v-for="item in genderOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="地区">
          <el-input v-model="form.region" maxlength="64" placeholder="如：上海" />
        </el-form-item>
      </div>
      <el-form-item label="用户名">
        <el-input :model-value="auth.currentUser?.username" disabled />
        <p class="muted profile-hint">用户名用于登录，暂不支持修改。</p>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.profile-head {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  padding-bottom: var(--isles-space-3);
  margin-bottom: var(--isles-space-3);
  border-bottom: 1px solid var(--isles-border);
}

.profile-head__meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--isles-space-3);
}

.profile-hint {
  margin-top: 4px;
  font-size: var(--isles-font-xs);
}

.muted {
  color: var(--isles-text-muted);
}
</style>
