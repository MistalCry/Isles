<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { PERMISSION_POINTS } from '@/utils/channelPermissions'
import type { Board, ChannelRoleItem } from '@/types/models'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    channelId: string
    role?: ChannelRoleItem | null
    boards?: Board[]
  }>(),
  {
    role: null,
    boards: () => [],
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [role: ChannelRoleItem]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const saving = ref(false)
const errorMessage = ref<string | null>(null)
const form = reactive({
  name: '',
  description: '',
  permissions: [] as string[],
  scopeBoardId: '',
})

const permissionGroups = computed(() => {
  const groups = new Map<string, typeof PERMISSION_POINTS>()
  PERMISSION_POINTS.forEach((point) => {
    const list = groups.get(point.group) ?? []
    list.push(point)
    groups.set(point.group, list)
  })
  return [...groups.entries()].map(([group, points]) => ({ group, points }))
})

const isEdit = computed(() => Boolean(props.role))

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    form.name = props.role?.name ?? ''
    form.description = props.role?.description ?? ''
    form.permissions = [...(props.role?.permissions ?? [])]
    form.scopeBoardId = props.role?.scopeBoardId ?? ''
    errorMessage.value = null
  },
  { immediate: true },
)

function togglePermission(code: string, checked: boolean): void {
  if (checked) {
    if (!form.permissions.includes(code)) form.permissions.push(code)
  } else {
    form.permissions = form.permissions.filter((item) => item !== code)
  }
}

function handlePermissionChange(code: string, checked: boolean | string | number): void {
  togglePermission(code, Boolean(checked))
}

async function save(): Promise<void> {
  if (!form.name.trim()) {
    errorMessage.value = t('manage.role.nameRequired')
    return
  }
  saving.value = true
  errorMessage.value = null
  try {
    const payload = {
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      permissions: form.permissions,
      scopeBoardId: form.scopeBoardId || null,
    }
    const saved = props.role
      ? await manageApi.updateRole(props.channelId, props.role.id, payload)
      : await manageApi.createRole(props.channelId, payload)
    ElMessage.success(isEdit.value ? t('channel.member.roleUpdated') : t('manage.role.created'))
    emit('saved', saved)
    visible.value = false
  } catch (err) {
    errorMessage.value = resolveErrorMessage(err, t('manage.role.saveFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="isEdit ? t('manage.role.edit') : t('manage.role.create')" width="min(680px, 94vw)" append-to-body>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon class="role-editor__alert" />
    <el-form label-position="top" @submit.prevent>
      <el-form-item :label="t('channel.roleName')" required>
        <el-input v-model="form.name" maxlength="32" show-word-limit :placeholder="t('channel.roleNamePlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('common.description')">
        <el-input v-model="form.description" maxlength="200" :placeholder="t('channel.roleDescPlaceholder')" />
      </el-form-item>
      <el-form-item v-if="boards.length > 0" :label="t('channel.roleBoards')">
        <el-select v-model="form.scopeBoardId" clearable :placeholder="t('channel.roleAllBoards')">
          <el-option v-for="board in boards" :key="board.id" :label="board.name" :value="board.id" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('channel.rolePermissions')">
        <div class="role-editor__groups">
          <section v-for="group in permissionGroups" :key="group.group" class="role-editor__group">
            <h4>{{ group.group }}</h4>
            <el-checkbox
              v-for="point in group.points"
              :key="point.code"
              :model-value="form.permissions.includes(point.code)"
              @change="(checked) => handlePermissionChange(point.code, checked)"
            >
              {{ point.name }}
            </el-checkbox>
          </section>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.role-editor__alert {
  margin-bottom: var(--isles-space-3);
}

.role-editor__groups {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-4);
  width: 100%;
}

.role-editor__group {
  min-width: 180px;

  h4 {
    margin-bottom: var(--isles-space-2);
    color: var(--isles-text-secondary);
    font-size: var(--isles-font-xs);
    font-weight: 600;
  }

  :deep(.el-checkbox) {
    display: flex;
    margin-right: 0;
  }
}
</style>
