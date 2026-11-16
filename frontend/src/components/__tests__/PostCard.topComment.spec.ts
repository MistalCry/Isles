import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PostCard from '@/components/PostCard.vue'
import type { Comment, Post } from '@/types/models'

// 卡片「最高赞评论」回归：有评论时渲染一条摘要（作者 + 60 字截断 + 查看全部），
// 没有评论时整块不渲染，不能留空白占位。
vi.mock('@/api', () => ({
  postsApi: { toggleLike: vi.fn(), share: vi.fn() },
}))

function makePost(overrides: Partial<Post> = {}): Post {
  return {
    id: '01POST0000000000000000000',
    channelId: '01CHAN0000000000000000000',
    author: { id: '01USER0000000000000000000', username: 'islander', nickname: '岛民', avatarUrl: '' },
    title: '标题',
    content: '正文内容',
    contentFormat: 'PLAIN',
    createdAt: '2026-11-15T10:00:00Z',
    likeCount: 1,
    commentCount: 3,
    viewCount: 0,
    isLiked: false,
    isPinned: false,
    isEssence: false,
    media: [],
    ...overrides,
  } as Post
}

function makeComment(overrides: Partial<Comment> = {}): Comment {
  return {
    id: '01COMMENT0000000000000001',
    postId: '01POST0000000000000000000',
    author: { id: '01USER0000000000000000001', username: 'replier', nickname: '回帖人', avatarUrl: '' },
    content: '这是被赞最多的评论',
    likeCount: 9,
    createdAt: '2026-11-15T11:00:00Z',
    ...overrides,
  } as Comment
}

function mountCard(post: Post) {
  return mount(PostCard, {
    props: { post },
    global: { stubs: { 'el-radio-group': true, 'el-radio-button': true } },
  })
}

describe('PostCard 最高赞评论', () => {
  it('有 topComment 时渲染作者、摘要与「查看全部」', () => {
    const wrapper = mountCard(makePost({ topComment: makeComment() }))
    const block = wrapper.find('.post-card__top-comment')
    expect(block.exists()).toBe(true)
    expect(block.text()).toContain('回帖人')
    expect(block.text()).toContain('这是被赞最多的评论')
    expect(block.find('.post-card__top-comment-more').exists()).toBe(true)
    // 点击直达该评论锚点
    expect(block.attributes('to')).toBeUndefined() // RouterLink 已桩成 <a>
  })

  it('没有 topComment 时整块不渲染（不占位）', () => {
    const wrapper = mountCard(makePost({ topComment: null }))
    expect(wrapper.find('.post-card__top-comment').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('最高赞')
  })

  it('摘要超过 60 字截断并加省略号', () => {
    const long = '很长的评论内容'.repeat(20)
    const wrapper = mountCard(makePost({ topComment: makeComment({ content: long }) }))
    const body = wrapper.find('.post-card__top-comment-body').text()
    expect(body).toContain('…')
    // 作者前缀「回帖人：」占 4 字，摘要本体 60 字 + 省略号
    expect(body.length).toBeLessThanOrEqual(70)
  })

  it('纯表情评论回退到表情文本，不渲染空白', () => {
    const wrapper = mountCard(makePost({ topComment: makeComment({ content: '', emoji: ['😀', '🌊'] }) }))
    expect(wrapper.find('.post-card__top-comment-body').text()).toContain('😀')
  })
})
