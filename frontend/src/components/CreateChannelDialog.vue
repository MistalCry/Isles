<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Picture } from '@element-plus/icons-vue'
import { mediaApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useChannelStore } from '@/stores/channel'
import { validateChannelDescription, validateChannelName } from '@/utils/validators'
import type { Channel, ChannelType, ChannelVisibility } from '@/types/models'
import UserAvatar from './UserAvatar.vue'

const { t } = useI18n()

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  created: [channel: Channel]
}>()

const channelStore = useChannelStore()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const formRef = ref<FormInstance>()
const fileInputRef = ref<HTMLInputElement>()
const submitting = ref(false)
const uploading = ref(false)
const errorMessage = ref<string | null>(null)

const form = reactive({
  name: '',
  description: '',
  type: 'PUBLIC' as ChannelType,
  visibility: 'PUBLIC_VISIBLE' as ChannelVisibility,
  avatarUrl: '',
})

const rules: FormRules = {
  name: [
    {
      validator: (_rule, value, callback) => {
        const message = validateChannelName(String(value ?? ''))
        if (message) callback(new Error(message))
        else callback()
      },
      trigger: 'blur',
    },
  ],
  description: [
    {
      validator: (_rule, value, callback) => {
        const message = validateChannelDescription(String(value ?? ''))
        if (message) callback(new Error(message))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}

function resetForm(): void {
  form.name = ''
  form.description = ''
  form.type = 'PUBLIC'
  form.visibility = 'PUBLIC_VISIBLE'
  form.avatarUrl = ''
  errorMessage.value = null
  submitting.value = false
  uploading.value = false
  formRef.value?.clearValidate()
}

watch(visible, (open) => {
  if (!open) resetForm()
})

function triggerFilePicker(): void {
  fileInputRef.value?.click()
}

async function handleAvatarChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const invalid = mediaApi.validateMediaFile(file)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  uploading.value = true
  try {
    const media = await mediaApi.uploadMedia(file)
    form.avatarUrl = media.url
    ElMessage.success(t('channel.create.avatarUploaded'))
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('channel.create.avatarFailed')))
  } finally {
    uploading.value = false
  }
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  errorMessage.value = null
  try {
    const channel = await channelStore.createChannel({
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      type: form.type,
      visibility: form.type === 'PRIVATE' ? form.visibility : 'PUBLIC_VISIBLE',
      avatarUrl: form.avatarUrl || null,
    })
    ElMessage.success(t('channel.create.success'))
    emit('created', channel)
    visible.value = false
  } catch (err) {
    errorMessage.value = resolveErrorMessage(err, t('channel.create.failed'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="t('manage.nav.createChannel')" width="520px" :close-on-click-modal="false" append-to-body>
    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="create-channel__alert"
    />

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
      <div class="create-channel__avatar">
        <UserAvatar :src="form.avatarUrl" :name="form.name || t('channel.create.island')" :size="64" square />
        <div class="create-channel__avatar-actions">
          <el-button size="small" :icon="Picture" :loading="uploading" @click="triggerFilePicker">
            {{ t('channel.create.avatarUpload') }}
          </el-button>
          <span class="muted">{{ t('channel.create.avatarHint') }}</span>
        </div>
        <input
          ref="fileInputRef"
          type="file"
          accept="image/*"
          class="visually-hidden"
          :aria-label="t('channel.avatarPick')"
          @change="handleAvatarChange"
        />
      </div>

      <el-form-item :label="t('channel.nameLabel')" prop="name">
        <el-input v-model="form.name" maxlength="30" show-word-limit :placeholder="t('channel.namePlaceholder')" />
      </el-form-item>

      <el-form-item :label="t('channel.page.intro')" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          :placeholder="t('channel.descPlaceholder')"
        />
      </el-form-item>

      <el-form-item :label="t('channel.type')">
        <el-radio-group v-model="form.type">
          <el-radio value="PUBLIC">{{ t('channel.profile.public') }}</el-radio>
          <el-radio value="PRIVATE">{{ t('channel.profile.private') }}</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="form.type === 'PRIVATE'" :label="t('channel.visibility')">
        <el-select v-model="form.visibility" class="create-channel__select">
          <el-option :label="t('channel.visibilityPublic')" value="PUBLIC_VISIBLE" />
          <el-option :label="t('channel.visibilityMembers')" value="MEMBERS_ONLY" />
        </el-select>
      </el-form-item>
    </el-form>

    <p class="create-channel__tip">{{ t('channel.create.tip') }}</p>

    <template #footer>
      <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">{{ t('manage.nav.createChannel') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.create-channel__alert {
  margin-bottom: var(--isles-space-4);
}

.create-channel__avatar {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  margin-bottom: var(--isles-space-4);
}

.create-channel__avatar-actions {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--isles-font-xs);
}

.create-channel__select {
  width: 100%;
}

.create-channel__tip {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  line-height: var(--isles-line-base);
}
</style>
