<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import {
  CHANNEL_PERMISSIONS,
  hasChannelPermission,
  resolveChannelPermissions,
} from '@/utils/channelPermissions'
import type { Channel } from '@/types/models'
import ImageCropUpload from '../media/ImageCropUpload.vue'

const { t } = useI18n()

const props = defineProps<{ channel: Channel }>()

const emit = defineEmits<{
  updated: [channel: Channel]
}>()

const auth = useAuthStore()
const saving = ref(false)
const errorMessage = ref<string | null>(null)

const permissions = computed(() => resolveChannelPermissions(props.channel, auth.currentUser?.id))
const canEdit = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.CHANNEL_SETTINGS))
const canEditVisibility = computed(() =>
  hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.VISIBILITY),
)

type ChannelType = 'PUBLIC' | 'PRIVATE'
type Visibility = 'PUBLIC_VISIBLE' | 'MEMBERS_ONLY'

const form = reactive({
  name: '',
  description: '',
  avatarUrl: '',
  coverUrl: '',
  type: 'PUBLIC' as ChannelType,
  visibility: 'PUBLIC_VISIBLE' as Visibility,
})

function reset(): void {
  form.name = props.channel.name
  form.description = props.channel.description ?? ''
  form.avatarUrl = props.channel.avatarUrl ?? ''
  form.coverUrl = props.channel.coverUrl ?? ''
  form.type = (props.channel.type === 'PRIVATE' ? 'PRIVATE' : 'PUBLIC') as ChannelType
  form.visibility = (props.channel.visibility === 'MEMBERS_ONLY' ? 'MEMBERS_ONLY' : 'PUBLIC_VISIBLE') as Visibility
  errorMessage.value = null
}

// 只在切换频道时重置，避免编辑过程中被后台数据刷新覆盖
watch(() => props.channel.id, reset, { immediate: true })

watch(
  () => form.type,
  (type) => {
    // 私密频道默认仅成员可见，减少一次手动操作
    if (type === 'PRIVATE' && form.visibility === 'PUBLIC_VISIBLE') {
      form.visibility = 'MEMBERS_ONLY'
    }
  },
)

async function save(): Promise<void> {
  if (!canEdit.value) return
  if (!form.name.trim()) {
    ElMessage.warning(t('channel.profile.nameRequired'))
    return
  }
  saving.value = true
  errorMessage.value = null
  try {
    const updated = await manageApi.updateChannel(props.channel.id, {
      name: form.name.trim(),
      // 空串 = 清空；后端约定 null 表示「不修改」，所以清空必须显式传 ""
      description: form.description.trim(),
      avatarUrl: form.avatarUrl,
      coverUrl: form.coverUrl,
      type: form.type,
      ...(canEditVisibility.value ? { visibility: form.visibility } : {}),
    })
    ElMessage.success(t('channel.profile.saved'))
    emit('updated', updated)
  } catch (err) {
    errorMessage.value = resolveErrorMessage(err, t('channel.profile.saveFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="channel-profile-form">
    <el-alert
      v-if="!canEdit"
      :title="t('channel.profileReadonly')"
      type="info"
      :closable="false"
      show-icon
      class="channel-profile-form__alert"
    />
    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="channel-profile-form__alert"
    />

    <div class="channel-profile-form__media">
      <div class="channel-profile-form__avatar">
        <span class="channel-profile-form__label">{{ t('channel.profile.avatar') }}</span>
        <ImageCropUpload
          v-model="form.avatarUrl"
          :aspect-ratio="1"
          :max-output-width="1024"
          :box-width="220"
          :preview-width="88"
          shape="circle"
          :label="t('media.crop.avatarLabel')"
          :hint="t('media.crop.squareHint')"
          :disabled="!canEdit"
        />
      </div>
      <div class="channel-profile-form__cover">
        <span class="channel-profile-form__label">{{ t('channel.profile.cover') }}</span>
        <ImageCropUpload
          v-model="form.coverUrl"
          :aspect-ratio="4"
          :max-output-width="2560"
          :box-width="320"
          :preview-width="300"
          :label="t('media.crop.backgroundLabel')"
          :hint="t('media.crop.backgroundHint')"
          :disabled="!canEdit"
        />
      </div>
    </div>

    <el-form label-position="top" :disabled="!canEdit" @submit.prevent>
      <el-form-item :label="t('channel.nameLabel')">
        <el-input v-model="form.name" maxlength="30" show-word-limit />
      </el-form-item>
      <el-form-item :label="t('channel.page.intro')">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="200" show-word-limit />
      </el-form-item>
      <el-form-item :label="t('channel.joinMethod')">
        <el-radio-group v-model="form.type">
          <el-radio value="PUBLIC">{{ t('channel.profile.public') }}</el-radio>
          <el-radio value="PRIVATE">{{ t('channel.profile.private') }}</el-radio>
        </el-radio-group>
        <p class="channel-profile-form__hint">
          {{ t('channel.profile.visibilityHint') }}
        </p>
      </el-form-item>
      <el-form-item v-if="form.type === 'PRIVATE'" :label="t('channel.visibility')">
        <el-select v-model="form.visibility" :disabled="!canEditVisibility">
          <el-option :label="t('channel.visibilityPublic')" value="PUBLIC_VISIBLE" />
          <el-option :label="t('channel.visibilityMembers')" value="MEMBERS_ONLY" />
        </el-select>
        <p v-if="!canEditVisibility" class="channel-profile-form__hint">{{ t('channel.profile.noVisibilityPermission') }}</p>
      </el-form-item>
      <el-form-item :label="t('channel.channelNo')">
        <el-input :model-value="channel.channelNo ? `#${channel.channelNo}` : t('channel.profile.unassigned')" disabled />
      </el-form-item>
    </el-form>

    <el-button v-if="canEdit" type="primary" :loading="saving" @click="save">{{ t('channel.profile.save') }}</el-button>
  </div>
</template>

<style scoped lang="scss">
.channel-profile-form__alert {
  margin-bottom: var(--isles-space-3);
}

.channel-profile-form__media {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-5);
  margin-bottom: var(--isles-space-4);
}

.channel-profile-form__avatar,
.channel-profile-form__cover {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  align-items: flex-start;
}

.channel-profile-form__label {
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
}

.channel-profile-form__hint {
  width: 100%;
  margin-top: 2px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  line-height: 1.5;
}
</style>
