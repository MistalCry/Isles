import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getAiStatus, listAiModels, type AiModel, type AiStatus } from '@/api/ai'
import { resolveErrorMessage } from '@/api/client'

const LAST_MODEL_KEY = 'isles.ai.lastModelId'

/**
 * AI 能力共享状态：模型可用性（未配置时全站提示）、用户选择的模型与深度思考开关。
 * 会话与消息属于页面级数据，不放进全局 store（docs/02 §6.2）。
 */
export const useAiStore = defineStore('ai', () => {
  const status = ref<AiStatus | null>(null)
  const models = ref<AiModel[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const selectedModelId = ref<string | null>(localStorage.getItem(LAST_MODEL_KEY))
  const deepThinking = ref(false)

  const configured = computed(() => status.value?.configured === true)
  const enabledModels = computed(() => models.value.filter((model) => model.enabled))
  const selectedModel = computed(
    () => enabledModels.value.find((model) => model.id === selectedModelId.value) ?? null,
  )
  const canDeepThinking = computed(() => selectedModel.value?.reasoningEnabled === true)
  const canVision = computed(() => selectedModel.value?.visionEnabled === true)

  async function load(force = false): Promise<void> {
    if (loading.value) return
    if (!force && status.value) return
    loading.value = true
    error.value = null
    try {
      const [nextStatus, nextModels] = await Promise.all([getAiStatus(), listAiModels()])
      status.value = nextStatus
      models.value = nextModels
      ensureSelectedModel()
    } catch (cause) {
      error.value = resolveErrorMessage(cause, 'AI 能力状态加载失败')
    } finally {
      loading.value = false
    }
  }

  function ensureSelectedModel(): void {
    const candidates = enabledModels.value
    if (candidates.length === 0) {
      selectedModelId.value = null
      return
    }
    if (selectedModelId.value && candidates.some((model) => model.id === selectedModelId.value)) {
      return
    }
    const fallback = candidates.find((model) => model.isDefault) ?? candidates[0]
    selectModel(fallback.id)
  }

  function selectModel(modelId: string | null): void {
    selectedModelId.value = modelId
    if (modelId) {
      localStorage.setItem(LAST_MODEL_KEY, modelId)
      const model = enabledModels.value.find((item) => item.id === modelId)
      if (!model?.reasoningEnabled) {
        deepThinking.value = false
      }
    } else {
      localStorage.removeItem(LAST_MODEL_KEY)
      deepThinking.value = false
    }
  }

  function toggleDeepThinking(value: boolean): void {
    deepThinking.value = value && canDeepThinking.value
  }

  function clear(): void {
    status.value = null
    models.value = []
    error.value = null
    loading.value = false
  }

  return {
    status,
    models,
    loading,
    error,
    selectedModelId,
    selectedModel,
    enabledModels,
    deepThinking,
    configured,
    canDeepThinking,
    canVision,
    load,
    selectModel,
    toggleDeepThinking,
    clear,
  }
})
