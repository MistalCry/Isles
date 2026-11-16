import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PostCard from '@/components/PostCard.vue'
import type { MediaItem, Post } from '@/types/models'

// 卡片展示回归（用户实测）：有封面时不该再堆媒体；无封面最多 3 张且第 3 张盖 +N；
// 有标题时正文压到 2 行（标题是主角，摘要只是补充）。
// 这里只断言渲染结果，接口调用全部打桩。
vi.mock('@/api', () => ({
  postsApi: { toggleLike: vi.fn(), share: vi.fn() },
}))

function image(index: number): MediaItem {
  return {
    type: 'IMAGE',
    url: `/uploads/2026/11/p${index}.jpg`,
    thumbUrl: `/uploads/2026/11/p${index}-thumb.jpg`,
  } as MediaItem
}

function makePost(overrides: Partial<Post> = {}): Post {
  return {
    id: '01POST0000000000000000000',
    channelId: '01CHAN0000000000000000000',
    author: {
      id: '01USER0000000000000000000',
      username: 'islander',
      nickname: '岛民',
      avatarUrl: '',
    },
    title: '标题',
    content: '正文内容',
    contentFormat: 'PLAIN',
    createdAt: '2026-11-15T10:00:00Z',
    likeCount: 1,
    commentCount: 0,
    viewCount: 0,
    isLiked: false,
    isPinned: false,
    isEssence: false,
    media: [],
    ...overrides,
  } as Post
}

/** 媒体区要真实渲染 MediaViewer（shallow 会把它桩掉，看不到格子与 +N） */
function mountCard(post: Post) {
  return mount(PostCard, {
    props: { post },
    // 子组件里的单选组与本用例无关，桩掉避免噪音告警
    global: { stubs: { 'el-radio-group': true, 'el-radio-button': true } },
  })
}

describe('PostCard 媒体区与摘要', () => {
  it('有封面时媒体区整体不渲染（即使帖子里带 9 张图）', () => {
    const wrapper = mountCard(
      makePost({
        coverUrl: '/uploads/2026/11/cover.png',
        media: Array.from({ length: 9 }, (_, index) => image(index)),
      }),
    )
    expect(wrapper.find('.post-card__cover').exists()).toBe(true)
    expect(wrapper.find('.post-card__media').exists()).toBe(false)
    expect(wrapper.find('.media-viewer').exists()).toBe(false)
  })

  it('无封面时最多渲染 3 张，第 3 张盖「+N」且 N 等于剩余张数', () => {
    const wrapper = mountCard(makePost({ media: Array.from({ length: 5 }, (_, index) => image(index)) }))
    const cells = wrapper.findAll('.media-viewer__cell')
    expect(cells).toHaveLength(3)
    // 5 张只展示 3 张，剩余 2 张
    expect(wrapper.find('.media-viewer__more').text()).toBe('+2')
    // +N 必须盖在第 3 张（最后一张可见）上
    expect(cells[2].find('.media-viewer__more').exists()).toBe(true)
    expect(cells[0].find('.media-viewer__more').exists()).toBe(false)
  })

  it('无封面且媒体不足 3 张时不出现「+N」', () => {
    const wrapper = mountCard(makePost({ media: [image(0), image(1)] }))
    expect(wrapper.findAll('.media-viewer__cell')).toHaveLength(2)
    expect(wrapper.find('.media-viewer__more').exists()).toBe(false)
  })

  it('恰好 3 张时不出现「+N」（没有剩余）', () => {
    const wrapper = mountCard(makePost({ media: [image(0), image(1), image(2)] }))
    expect(wrapper.findAll('.media-viewer__cell')).toHaveLength(3)
    expect(wrapper.find('.media-viewer__more').exists()).toBe(false)
  })

  it('卡片媒体区不可点开灯箱（整卡跳详情，避免点击被灯箱截走）', () => {
    const wrapper = mountCard(makePost({ media: [image(0)] }))
    expect(wrapper.find('.post-card__media').exists()).toBe(true)
    expect(wrapper.findAll('.media-viewer button')).toHaveLength(0)
    expect(wrapper.findAll('.media-viewer .media-viewer__image.is-static')).toHaveLength(1)
  })

  it('有标题时摘要用 2 行截断，且正文按 120 字截断', () => {
    const content = '很长的正文'.repeat(40)
    const wrapper = mountCard(makePost({ title: '有标题', content }))
    const excerptNode = wrapper.find('.post-card__excerpt')
    expect(excerptNode.classes()).toContain('line-clamp-2')
    expect(excerptNode.classes()).not.toContain('line-clamp-3')
    expect(excerptNode.text().length).toBe(121)
    expect(excerptNode.text().endsWith('…')).toBe(true)
  })

  it('无标题时摘要保留 3 行、按 220 字截断', () => {
    const content = '很长的正文'.repeat(50)
    const wrapper = mountCard(makePost({ title: '', content }))
    const excerptNode = wrapper.find('.post-card__excerpt')
    expect(excerptNode.classes()).toContain('line-clamp-3')
    expect(excerptNode.classes()).not.toContain('line-clamp-2')
    expect(excerptNode.text().length).toBe(221)
  })

  it('短正文不截断、不加省略号', () => {
    const wrapper = mountCard(makePost({ title: '标题', content: '短正文' }))
    expect(wrapper.find('.post-card__excerpt').text()).toBe('短正文')
  })
})
