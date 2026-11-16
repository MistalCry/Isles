import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AiBadge from '@/components/AiBadge.vue'
import { toComment, toPost, toUserSummary } from '@/api/adapters'

// AI 标识是合规硬要求（docs/12 §6）：徽章必须在，且「AI 生成 / AI 代理」两种语义不能混。
const stubs = {
  'el-tooltip': {
    props: ['content'],
    template: '<span class="el-tooltip-stub" :data-tip="content"><slot /></span>',
  },
}

describe('AiBadge', () => {
  it('默认是「AI 生成」语义，文案为「由 AI 生成」', () => {
    const wrapper = mount(AiBadge, { global: { stubs } })
    expect(wrapper.text()).toBe('AI')
    expect(wrapper.find('.el-tooltip-stub').attributes('data-tip')).toBe('由 AI 生成')
  })

  it('kind=agent 时提示「AI 代理账号」', () => {
    const wrapper = mount(AiBadge, { props: { kind: 'agent' }, global: { stubs } })
    expect(wrapper.find('.el-tooltip-stub').attributes('data-tip')).toBe('AI 代理账号')
  })

  it('size 走 class，便于与正文同排或独立展示', () => {
    const wrapper = mount(AiBadge, { props: { size: 'md' }, global: { stubs } })
    expect(wrapper.find('.ai-badge').classes()).toContain('ai-badge--md')
  })
})

describe('适配器：AI 字段容错', () => {
  it('toPost 读 isAiGenerated，兼容 aiGenerated', () => {
    expect(toPost({ id: 'p1', content: 'x', isAiGenerated: true }).isAiGenerated).toBe(true)
    expect(toPost({ id: 'p2', content: 'x', aiGenerated: true }).isAiGenerated).toBe(true)
    expect(toPost({ id: 'p3', content: 'x' }).isAiGenerated).toBe(false)
  })

  it('toComment 读 isAiGenerated', () => {
    const comment = toComment({ id: 'c1', postId: 'p1', content: 'x', isAiGenerated: true })
    expect(comment.isAiGenerated).toBe(true)
  })

  it('toUserSummary 读 isAgent，兼容 agent', () => {
    expect(toUserSummary({ id: 'u1', username: 'agent_x', isAgent: true }).isAgent).toBe(true)
    expect(toUserSummary({ id: 'u2', username: 'agent_y', agent: true }).isAgent).toBe(true)
    expect(toUserSummary({ id: 'u3', username: 'human' }).isAgent).toBe(false)
  })
})
