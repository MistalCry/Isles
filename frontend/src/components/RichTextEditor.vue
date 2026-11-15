<script setup lang="ts">
/**
 * 轻量富文本编辑器（长文发布使用）。
 *
 * 设计取舍：不引入第三方编辑器依赖（体积/离线可用性），基于 contenteditable + execCommand
 * 实现常用格式；输出 HTML，由后端 `HtmlSanitizer` 白名单净化后再入库与渲染。
 * 所有格式按钮均为无样式按钮，视觉由本组件统一控制。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Link, Picture } from '@element-plus/icons-vue'
import { mediaApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'

const props = withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    minHeight?: number
    maxLength?: number
    disabled?: boolean
  }>(),
  {
    placeholder: '写下你想分享的内容…',
    minHeight: 240,
    maxLength: 50_000,
    disabled: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const editorRef = ref<HTMLElement>()
const fileInput = ref<HTMLInputElement>()
const uploading = ref(false)
const focused = ref(false)
const activeMarks = ref<Record<string, boolean>>({})

const plainText = computed(() => (props.modelValue || '').replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim())
const isEmpty = computed(() => plainText.value.length === 0)

const blocks = [
  { label: '正文', command: 'formatBlock', value: 'p' },
  { label: 'H2', command: 'formatBlock', value: 'h2' },
  { label: 'H3', command: 'formatBlock', value: 'h3' },
  { label: '引用', command: 'formatBlock', value: 'blockquote' },
]

const marks = [
  { label: 'B', title: '加粗', command: 'bold', className: 'is-bold' },
  { label: 'I', title: '斜体', command: 'italic', className: 'is-italic' },
  { label: 'U', title: '下划线', command: 'underline', className: 'is-underline' },
  { label: 'S', title: '删除线', command: 'strikeThrough', className: 'is-strike' },
]

function syncFromDom(): void {
  const html = editorRef.value?.innerHTML ?? ''
  const normalized = html === '<br>' ? '' : html
  if (normalized !== props.modelValue) {
    emit('update:modelValue', normalized)
  }
}

function exec(command: string, value?: string): void {
  if (props.disabled) return
  editorRef.value?.focus()
  document.execCommand(command, false, value)
  syncFromDom()
  refreshActiveMarks()
}

function refreshActiveMarks(): void {
  const next: Record<string, boolean> = {}
  for (const mark of marks) {
    try {
      next[mark.command] = document.queryCommandState(mark.command)
    } catch {
      next[mark.command] = false
    }
  }
  activeMarks.value = next
}

async function insertLink(): Promise<void> {
  if (props.disabled) return
  editorRef.value?.focus()
  try {
    const { value } = await ElMessageBox.prompt('输入链接地址（https:// 开头）', '插入链接', {
      confirmButtonText: '插入',
      cancelButtonText: '取消',
      inputPattern: /^https?:\/\/.+/,
      inputErrorMessage: '请输入 http(s) 开头的完整地址',
    })
    exec('createLink', value)
  } catch {
    // 用户取消
  }
}

function triggerImagePick(): void {
  if (props.disabled) return
  fileInput.value?.click()
}

async function handleImagePick(event: Event): Promise<void> {
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
    const uploaded = await mediaApi.uploadMedia(file)
    editorRef.value?.focus()
    exec('insertImage', uploaded.url)
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, '图片上传失败'))
  } finally {
    uploading.value = false
  }
}

/** 粘贴时只保留基础格式，避免把外部样式整块带进正文 */
function handlePaste(event: ClipboardEvent): void {
  event.preventDefault()
  const html = event.clipboardData?.getData('text/html')
  const text = event.clipboardData?.getData('text/plain') ?? ''
  if (html) {
    const allowed = new Set(['P', 'DIV', 'BR', 'STRONG', 'B', 'EM', 'I', 'U', 'S', 'UL', 'OL', 'LI', 'BLOCKQUOTE', 'H2', 'H3', 'A', 'IMG', 'CODE', 'PRE', 'SPAN'])
    const template = document.createElement('template')
    template.innerHTML = html
    const walk = (node: Node): void => {
      const children = Array.from(node.childNodes)
      for (const child of children) {
        if (child.nodeType === Node.ELEMENT_NODE) {
          const element = child as HTMLElement
          if (!allowed.has(element.tagName)) {
            element.replaceWith(...Array.from(element.childNodes))
            walk(node)
            continue
          }
          for (const attr of Array.from(element.attributes)) {
            if (!['href', 'src', 'alt', 'title'].includes(attr.name)) {
              element.removeAttribute(attr.name)
            }
          }
          walk(element)
        } else if (child.nodeType !== Node.TEXT_NODE) {
          child.remove()
        }
      }
    }
    walk(template.content)
    document.execCommand('insertHTML', false, template.innerHTML)
  } else {
    document.execCommand('insertText', false, text)
  }
  syncFromDom()
}

watch(
  () => props.modelValue,
  (value) => {
    const editor = editorRef.value
    if (!editor || focused.value) return
    if ((value || '') !== editor.innerHTML) {
      editor.innerHTML = value || ''
    }
  },
)

onMounted(() => {
  if (editorRef.value) editorRef.value.innerHTML = props.modelValue || ''
})
</script>

<template>
  <div class="rich-editor" :class="{ 'rich-editor--disabled': disabled }">
    <div class="rich-editor__toolbar" role="toolbar" aria-label="富文本工具栏">
      <button
        v-for="mark in marks"
        :key="mark.command"
        type="button"
        class="rich-editor__btn"
        :class="[mark.className, { 'is-active': activeMarks[mark.command] }]"
        :title="mark.title"
        :aria-label="mark.title"
        @mousedown.prevent
        @click="exec(mark.command)"
      >
        {{ mark.label }}
      </button>
      <span class="rich-editor__divider" aria-hidden="true" />
      <button
        v-for="block in blocks"
        :key="block.value"
        type="button"
        class="rich-editor__btn rich-editor__btn--text"
        :title="block.label"
        @mousedown.prevent
        @click="exec(block.command, block.value)"
      >
        {{ block.label }}
      </button>
      <span class="rich-editor__divider" aria-hidden="true" />
      <button type="button" class="rich-editor__btn" title="无序列表" @mousedown.prevent @click="exec('insertUnorderedList')">•</button>
      <button type="button" class="rich-editor__btn" title="有序列表" @mousedown.prevent @click="exec('insertOrderedList')">1.</button>
      <button type="button" class="rich-editor__btn" title="行内代码" @mousedown.prevent @click="exec('formatBlock', 'pre')">&lt;/&gt;</button>
      <span class="rich-editor__divider" aria-hidden="true" />
      <button type="button" class="rich-editor__btn" title="插入链接" :disabled="disabled" @mousedown.prevent @click="insertLink">
        <el-icon><Link /></el-icon>
      </button>
      <button
        type="button"
        class="rich-editor__btn"
        :title="uploading ? '上传中…' : '插入图片'"
        :disabled="disabled || uploading"
        @mousedown.prevent
        @click="triggerImagePick"
      >
        <el-icon><Picture /></el-icon>
      </button>
      <span class="rich-editor__divider" aria-hidden="true" />
      <button type="button" class="rich-editor__btn" title="撤销" @mousedown.prevent @click="exec('undo')">↺</button>
      <button type="button" class="rich-editor__btn" title="重做" @mousedown.prevent @click="exec('redo')">↻</button>
      <button type="button" class="rich-editor__btn rich-editor__btn--text" title="清除格式" @mousedown.prevent @click="exec('removeFormat')">
        清除格式
      </button>
    </div>

    <div
      ref="editorRef"
      class="rich-editor__content"
      :style="{ minHeight: `${minHeight}px` }"
      :contenteditable="!disabled"
      role="textbox"
      aria-multiline="true"
      :aria-label="placeholder"
      spellcheck="false"
      @input="syncFromDom"
      @blur="focused = false; syncFromDom()"
      @focus="focused = true"
      @keyup="refreshActiveMarks"
      @mouseup="refreshActiveMarks"
      @paste="handlePaste"
    />
    <p v-if="isEmpty" class="rich-editor__placeholder">{{ placeholder }}</p>

    <div class="rich-editor__footer">
      <span class="muted">支持加粗、标题、列表、引用、链接与图片</span>
      <span class="muted">{{ plainText.length }} / {{ maxLength }}</span>
    </div>

    <input ref="fileInput" type="file" accept="image/*" class="visually-hidden" @change="handleImagePick" />
  </div>
</template>

<style scoped lang="scss">
.rich-editor {
  position: relative;
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-md);
  transition: border-color 0.15s ease;

  &:focus-within {
    border-color: var(--isles-primary);
  }
}

.rich-editor--disabled {
  background: var(--isles-surface-muted);
}

.rich-editor__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  align-items: center;
  padding: 6px 8px;
  border-bottom: 1px solid var(--isles-border);
}

.rich-editor__btn {
  min-width: 28px;
  height: 28px;
  padding: 0 6px;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  cursor: pointer;

  &:hover:not(:disabled) {
    color: var(--isles-text);
    background: var(--isles-surface-muted);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }

  &.is-active {
    color: var(--isles-primary);
    background: var(--isles-primary-soft, rgb(64 128 255 / 12%));
  }

  &.is-bold {
    font-weight: 700;
  }

  &.is-italic {
    font-style: italic;
  }

  &.is-underline {
    text-decoration: underline;
  }

  &.is-strike {
    text-decoration: line-through;
  }
}

.rich-editor__btn--text {
  font-size: var(--isles-font-xs);
}

.rich-editor__divider {
  width: 1px;
  height: 16px;
  margin: 0 4px;
  background: var(--isles-border);
}

.rich-editor__content {
  padding: 12px 14px;
  font-size: var(--isles-font-md);
  line-height: 1.7;
  outline: none;

  :deep(h2) {
    margin: 12px 0 6px;
    font-size: 1.3em;
    font-weight: 600;
  }

  :deep(h3) {
    margin: 10px 0 4px;
    font-size: 1.15em;
    font-weight: 600;
  }

  :deep(blockquote) {
    padding: 4px 12px;
    margin: 8px 0;
    color: var(--isles-text-secondary);
    border-left: 3px solid var(--isles-border-strong);
  }

  :deep(ul),
  :deep(ol) {
    padding-left: 22px;
    margin: 6px 0;
  }

  :deep(pre) {
    padding: 10px 12px;
    overflow-x: auto;
    font-family: var(--isles-font-mono, monospace);
    font-size: var(--isles-font-sm);
    background: var(--isles-surface-muted);
    border-radius: var(--isles-radius-sm);
  }

  :deep(img) {
    max-width: 100%;
    height: auto;
    margin: 6px 0;
    border-radius: var(--isles-radius-sm);
  }

  :deep(a) {
    color: var(--isles-primary);
    text-decoration: underline;
  }
}

.rich-editor__placeholder {
  position: absolute;
  top: 46px;
  left: 15px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-md);
  pointer-events: none;
}

.rich-editor__footer {
  display: flex;
  justify-content: space-between;
  padding: 4px 12px 8px;
  font-size: var(--isles-font-xs);
}
</style>
