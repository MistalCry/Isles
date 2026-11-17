<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Close, Search } from '@element-plus/icons-vue'
import { searchApi } from '@/api'
import { useSearchHistory } from '@/composables/useSearchHistory'
import { useRequestSeq } from '@/composables/useRequestSeq'
import UserAvatar from './UserAvatar.vue'
import type { HotKeyword, SearchSuggestion } from '@/types/models'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    scope?: 'global' | 'channel'
    channelId?: string
    initialKeyword?: string
    navigateOnSubmit?: boolean
  }>(),
  {
    scope: 'global',
    channelId: '',
    initialKeyword: '',
    navigateOnSubmit: true,
  },
)

const emit = defineEmits<{
  submit: [keyword: string]
}>()

const router = useRouter()
const keyword = ref(props.initialKeyword)
const suggestion = ref<SearchSuggestion>({ channels: [], keywords: [] })
const suggesting = ref(false)
const hotKeywords = ref<HotKeyword[]>([])
const hotLoading = ref(false)
const { items: history, loading: historyLoading, usingLocalFallback, load, record, remove, clear } = useSearchHistory()

let debounceTimer: number | undefined
/** 联想请求序号：连打几个字时只认最后一次查询的结果 */
const suggestSeq = useRequestSeq()

watch(
  () => props.initialKeyword,
  (value) => {
    keyword.value = value
  },
)

watch(keyword, (value) => {
  window.clearTimeout(debounceTimer)
  const query = value.trim()
  if (!query) {
    // 清空时把在途联想请求一起作废，避免旧结果回填
    suggestSeq.invalidate()
    suggestion.value = { channels: [], keywords: [] }
    suggesting.value = false
    return
  }
  debounceTimer = window.setTimeout(async () => {
    const seq = suggestSeq.next()
    suggesting.value = true
    try {
      const result = await searchApi.getSearchSuggestion(query)
      if (!suggestSeq.isLatest(seq)) return
      suggestion.value = result
    } catch {
      // 联想失败不打扰用户：清空结果即可，输入框本身仍可用
      if (suggestSeq.isLatest(seq)) suggestion.value = { channels: [], keywords: [] }
    } finally {
      if (suggestSeq.isLatest(seq)) suggesting.value = false
    }
  }, 300)
})

onMounted(() => {
  void load()
  void loadHotKeywords()
})

async function loadHotKeywords(): Promise<void> {
  hotLoading.value = true
  try {
    hotKeywords.value = await searchApi.getHotKeywords(10)
  } finally {
    hotLoading.value = false
  }
}

onBeforeUnmount(() => {
  window.clearTimeout(debounceTimer)
})

function submit(value?: string): void {
  const query = (value ?? keyword.value).trim()
  if (!query) {
    ElMessage.warning(t('search.keywordRequired'))
    return
  }
  keyword.value = query
  record(query)
  emit('submit', query)
  suggestion.value = { channels: [], keywords: [] }
  if (props.scope === 'global' && props.navigateOnSubmit) {
    void router.push({ name: 'search', query: { q: query } })
  }
}

function goChannel(channelId: string): void {
  void router.push({ name: 'channel', params: { id: channelId } })
}

async function removeHistory(id: string): Promise<void> {
  await remove(id)
}

async function clearHistory(): Promise<void> {
  await clear()
}
</script>

<template>
  <section class="search-panel" :aria-label="t('common.search')">
    <form class="search-panel__bar" role="search" @submit.prevent="submit()">
      <el-input
        v-model="keyword"
        size="large"
        clearable
        :prefix-icon="Search"
        :placeholder="t('search.placeholder')"
        :aria-label="t('search.keywordLabel')"
        @keyup.enter="submit()"
      />
      <el-button type="primary" size="large" native-type="submit">{{ t('common.search') }}</el-button>
    </form>

    <p v-if="scope === 'channel'" class="search-panel__scope">
      {{ t('search.scopeChannelHint', { name: channelId }) }}
    </p>

    <div v-if="keyword.trim()" class="search-panel__suggest">
      <div v-if="suggesting" class="search-panel__suggest-status muted">{{ t('search.suggesting') }}</div>
      <template v-else>
        <button
          v-for="channel in suggestion.channels"
          :key="channel.id"
          type="button"
          class="suggest-item"
          @click="goChannel(channel.id)"
        >
          <UserAvatar :src="channel.avatarUrl" :name="channel.name" :size="24" square />
          <span class="suggest-item__text">{{ channel.name }}</span>
          <span class="muted">{{ t('common.channel') }}</span>
        </button>
        <button
          v-for="word in suggestion.keywords"
          :key="word"
          type="button"
          class="suggest-item"
          @click="submit(word)"
        >
          <el-icon><Search /></el-icon>
          <span class="suggest-item__text">{{ word }}</span>
        </button>
        <p v-if="suggestion.channels.length === 0 && suggestion.keywords.length === 0" class="muted search-panel__suggest-status">
          {{ t('search.pressEnter', { kw: keyword.trim() }) }}
        </p>
      </template>
    </div>

    <div v-else class="search-panel__history">
      <div v-if="hotKeywords.length > 0 || hotLoading" class="search-panel__hot">
        <div class="search-panel__history-head">
          <h3>{{ t('search.hot') }}</h3>
        </div>
        <el-skeleton v-if="hotLoading" :rows="1" animated />
        <div v-else class="search-panel__chips">
          <button
            v-for="(item, index) in hotKeywords"
            :key="item.keyword"
            type="button"
            class="hot-chip"
            @click="submit(item.keyword)"
          >
            <span class="hot-chip__rank">{{ index + 1 }}</span>
            {{ item.keyword }}
          </button>
        </div>
      </div>

      <div class="search-panel__history-head">
        <h3>{{ t('search.history') }}</h3>
        <button v-if="history.length > 0" type="button" class="link" @click="clearHistory">{{ t('search.clear') }}</button>
      </div>

      <el-skeleton v-if="historyLoading" :rows="1" animated />

      <div v-else-if="history.length > 0" class="search-panel__chips">
        <span v-for="item in history" :key="item.id" class="history-chip">
          <button type="button" class="history-chip__keyword" @click="submit(item.keyword)">
            {{ item.keyword }}
          </button>
          <button
            type="button"
            class="history-chip__remove"
            :aria-label="t('search.removeHistoryItem', { kw: item.keyword })"
            @click="removeHistory(item.id)"
          >
            <el-icon :size="12"><Close /></el-icon>
          </button>
        </span>
      </div>

      <p v-else class="muted search-panel__empty">{{ t('search.historyEmpty') }}</p>

      <p v-if="usingLocalFallback && history.length > 0" class="muted search-panel__fallback">
        {{ t('search.localFallback') }}
      </p>
    </div>
  </section>
</template>

<style scoped lang="scss">
.search-panel__bar {
  display: flex;
  gap: var(--isles-space-2);
}

.search-panel__scope {
  margin-top: var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.search-panel__suggest {
  margin-top: var(--isles-space-3);
  padding: var(--isles-space-2);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-md);
  box-shadow: var(--isles-shadow-sm);
}

.search-panel__suggest-status {
  padding: var(--isles-space-2);
  font-size: var(--isles-font-sm);
}

.suggest-item {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  width: 100%;
  padding: var(--isles-space-2);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  font-size: var(--isles-font-sm);
  text-align: left;
  cursor: pointer;

  &:hover {
    background: var(--isles-hover);
  }
}

.suggest-item__text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-panel__history {
  margin-top: var(--isles-space-4);
}

.search-panel__hot {
  margin-bottom: var(--isles-space-4);
}

.hot-chip {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 4px 12px;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-pill);
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
    border-color: var(--isles-border-strong);
  }
}

.hot-chip__rank {
  color: var(--isles-accent);
  font-weight: 600;
}

.search-panel__history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--isles-space-3);

  h3 {
    font-size: var(--isles-font-sm);
    font-weight: 600;
  }

  button {
    background: transparent;
    border: none;
    font-size: var(--isles-font-xs);
  }
}

.search-panel__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
}

.history-chip {
  display: inline-flex;
  align-items: center;
  overflow: hidden;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-pill);
}

.history-chip__keyword {
  padding: 4px 4px 4px 12px;
  background: transparent;
  border: none;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
  }
}

.history-chip__remove {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px 4px 4px;
  background: transparent;
  border: none;
  color: var(--isles-text-muted);
  cursor: pointer;

  &:hover {
    color: var(--isles-danger);
  }
}

.search-panel__empty {
  font-size: var(--isles-font-sm);
}

.search-panel__fallback {
  margin-top: var(--isles-space-2);
  font-size: var(--isles-font-xs);
}
</style>
