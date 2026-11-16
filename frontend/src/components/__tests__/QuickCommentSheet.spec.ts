import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'

// 弹窗评论权限回归（用户实测）：非频道成员打开「快捷评论」弹窗时，
// 输入区必须直接禁用并给出「加入频道」提示，而不是让用户写完再被 403 打回来。
const mocks = vi.hoisted(() => ({
  getComments: vi.fn(),
}))

vi.mock('@/api', () => ({
  commentsApi: {
    getComments: mocks.getComments,
    createComment: vi.fn(),
    deleteComment: vi.fn(),
    toggleCommentLike: vi.fn(),
  },
  manageApi: { submitReport: vi.fn() },
  mediaApi: { uploadMedia: vi.fn() },
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAuthenticated: true, user: { id: '01USER0000000000000000000' } }),
}))

import QuickCommentSheet from '@/components/QuickCommentSheet.vue'
import CommentSection from '@/components/CommentSection.vue'
import type { Post } from '@/types/models'

const POST = {
  id: '01POST0000000000000000000',
  channelId: '01CHAN0000000000000000000',
  author: { id: '01USER0000000000000000001', username: 'author', nickname: '作者', avatarUrl: '' },
  title: '标题',
  content: '正文内容',
  contentFormat: 'PLAIN',
  createdAt: '2026-11-15T10:00:00Z',
  likeCount: 0,
  commentCount: 2,
  viewCount: 0,
  isLiked: false,
  isPinned: false,
  isEssence: false,
  media: [],
} as Post

/** CommentSection 桩：把 canComment 透到 DOM，用来验证弹窗是否把权限传下去 */
const CommentSectionStub = defineComponent({
  name: 'CommentSection',
  props: ['postId', 'postAuthorId', 'channelId', 'commentCount', 'canComment', 'embedded'],
  template: '<div class="comment-section-stub" :data-can-comment="String(canComment)"></div>',
})

function mountSheet(props: Record<string, unknown> = {}) {
  return mount(QuickCommentSheet, {
    props: { modelValue: true, post: POST, ...props },
    global: {
      stubs: {
        'el-dialog': { template: '<div class="el-dialog-stub"><slot /><slot name="footer" /></div>' },
        CommentSection: CommentSectionStub,
      },
    },
  })
}

function mountSection(canComment: boolean) {
  return mount(CommentSection, {
    props: {
      postId: POST.id,
      postAuthorId: POST.author.id,
      channelId: POST.channelId,
      commentCount: 2,
      canComment,
      embedded: true,
    },
    global: {
      stubs: {
        'el-dropdown': { template: '<div><slot /></div>' },
        'el-dropdown-menu': { template: '<div><slot /></div>' },
        'el-dropdown-item': { template: '<div><slot /></div>' },
      },
    },
  })
}

describe('快捷评论弹窗的成员权限', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    mocks.getComments.mockResolvedValue({ items: [], nextCursor: null })
  })

  it('非成员（canComment=false）打开弹窗：输入区换成「加入频道」提示', async () => {
    const wrapper = mountSection(false)
    await flushPromises()
    expect(wrapper.find('.comment-section__join-tip').exists()).toBe(true)
    expect(wrapper.find('.comment-composer').exists()).toBe(false)
  })

  it('成员（canComment=true）打开弹窗：正常渲染输入区', async () => {
    const wrapper = mountSection(true)
    await flushPromises()
    expect(wrapper.find('.comment-section__join-tip').exists()).toBe(false)
    expect(wrapper.find('.comment-composer').exists()).toBe(true)
  })

  it('弹窗把 canComment 透传给评论组件（非成员）', () => {
    const wrapper = mountSheet({ canComment: false })
    const section = wrapper.find('.comment-section-stub')
    expect(section.exists()).toBe(true)
    expect(section.attributes('data-can-comment')).toBe('false')
  })

  // Vue 的 Boolean prop 缺省值是 false：不传 canComment 会按「不能评论」处理。
  // 这是安全侧的默认（宁可禁掉也别让人写完再 403），但调用方必须显式传值——
  // 目前 PostCard / PostDetailView 都传了，这里把默认值钉住防止被无意改成 true。
  it('未显式传 canComment 时按拒绝处理（Boolean 缺省 false）', () => {
    const wrapper = mountSheet()
    expect(wrapper.find('.comment-section-stub').attributes('data-can-comment')).toBe('false')
  })
})
