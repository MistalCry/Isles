import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

// AI 用户页是 Agent P0 的运维入口：列表字段、总开关提示、三个关键动作都要锁住，
// 避免以后改接口时页面静默变成空表。
const mocks = vi.hoisted(() => ({
  identity: { value: null as unknown },
  listAdminAgents: vi.fn(),
  getAgentSwitches: vi.fn(),
  createAdminAgent: vi.fn(),
  listAdminAgentRuns: vi.fn(),
  listAdminSettings: vi.fn(),
  listAdminChannels: vi.fn(),
  pauseAdminAgent: vi.fn(),
  resumeAdminAgent: vi.fn(),
  runAdminAgentNow: vi.fn(),
  deleteAdminAgent: vi.fn(),
  updateAdminAgent: vi.fn(),
}))

vi.mock('@/composables/useAdminAccess', () => ({
  useAdminAccess: () => ({ identity: mocks.identity }),
}))

vi.mock('@/pages/admin/agentApi', async () => {
  const actual = await vi.importActual<typeof import('@/pages/admin/agentApi')>('@/pages/admin/agentApi')
  return {
    toAgentUser: actual.toAgentUser,
    toAgentRun: actual.toAgentRun,
    listAdminAgents: mocks.listAdminAgents,
    getAgentSwitches: mocks.getAgentSwitches,
    createAdminAgent: mocks.createAdminAgent,
    listAdminAgentRuns: mocks.listAdminAgentRuns,
    pauseAdminAgent: mocks.pauseAdminAgent,
    resumeAdminAgent: mocks.resumeAdminAgent,
    runAdminAgentNow: mocks.runAdminAgentNow,
    deleteAdminAgent: mocks.deleteAdminAgent,
    updateAdminAgent: mocks.updateAdminAgent,
  }
})

vi.mock('@/pages/admin/adminApi', async () => {
  const actual = await vi.importActual<typeof import('@/pages/admin/adminApi')>('@/pages/admin/adminApi')
  return {
    ...actual,
    listAdminSettings: mocks.listAdminSettings,
    listAdminChannels: mocks.listAdminChannels,
  }
})

import AdminAgentsView from '@/pages/admin/AdminAgentsView.vue'

const ADMIN = {
  admin: true,
  userId: '01USER0000000000000000001',
  username: 'admin_demo',
  platformRole: 'SUPER_ADMIN',
  permissions: ['AGENT_MANAGE'],
}

const AGENT = {
  // 后端 AgentView.id 是人设 id，子资源接口只认 userId —— 两者故意不同以锁住行为
  id: '01PROFILE00000000000000001',
  userId: '01AGENT000000000000000001',
  username: 'agent_island',
  displayName: '岛上小助手',
  avatarUrl: null,
  bio: '喜欢建筑与红石',
  status: 'ACTIVE',
  profile: {
    interests: ['minecraft', '建筑'],
    tone: 'casual',
    activeHours: [[20, 24]],
    postsPerDay: 2,
    commentsPerDay: 8,
    channels: [],
    toolsEnabled: ['create_post', 'comment'],
  },
  todayPosts: 1,
  todayComments: 3,
  runCount: 12,
  failureRate: 0.25,
  lastRunAt: '2026-11-16T08:00:00Z',
}

async function mountView() {
  const wrapper = mount(AdminAgentsView, {
    global: {
      plugins: [ElementPlus],
      // el-input-number 在 jsdom 下会因内部 ref 未挂载而抛错（Element Plus 已知问题），
      // 这里只关心「页面行为」，数值控件用桩即可。
      stubs: { teleport: true, 'el-input-number': true },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mocks.identity.value = ADMIN
  mocks.listAdminAgents.mockResolvedValue({ items: [AGENT], total: 1, page: 1, size: 20 })
  mocks.listAdminAgentRuns.mockResolvedValue({
    items: [
      {
        id: 'run1',
        agentId: AGENT.id,
        startedAt: '2026-11-16T08:00:00Z',
        status: 'GUARDED',
        outputType: 'NONE',
        error: '命中敏感词',
        tokensIn: 120,
        tokensOut: 40,
        planJson: { action: 'create_post' },
        toolCallsJson: [{ name: 'search_site_posts' }],
      },
    ],
    total: 1,
    page: 1,
    size: 20,
  })
  mocks.getAgentSwitches.mockResolvedValue({ enabled: true, dailyPostLimit: 20, dailyTokenBudget: 200000 })
  mocks.listAdminSettings.mockResolvedValue([
    {
      group: 'AI',
      label: 'AI',
      items: [
        {
          key: 'agent.search.provider',
          group: 'AI',
          label: 'AI 代理联网搜索',
          type: 'ENUM',
          value: 'SEARXNG',
          defaultValue: 'NONE',
          overridden: true,
          publicVisible: false,
        },
      ],
    },
  ])
  mocks.listAdminChannels.mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
  mocks.pauseAdminAgent.mockResolvedValue(undefined)
  mocks.resumeAdminAgent.mockResolvedValue(undefined)
  mocks.runAdminAgentNow.mockResolvedValue({
    status: 'SUCCESS',
    reason: null,
    run: { id: 'run2', agentId: AGENT.userId, startedAt: '', status: 'SUCCESS', outputType: 'POST' },
  })
  mocks.deleteAdminAgent.mockResolvedValue(undefined)
  mocks.updateAdminAgent.mockResolvedValue(AGENT)
})

describe('AdminAgentsView', () => {
  it('列表渲染昵称、兴趣摘要、今日产出与失败率', async () => {
    const wrapper = await mountView()
    const text = wrapper.text()
    expect(text).toContain('岛上小助手')
    expect(text).toContain('minecraft · 建筑')
    expect(text).toContain('1 帖')
    expect(text).toContain('3 评')
    expect(text).toContain('25%')
    expect(text).toContain('共 12 次')
  })

  it('agent.enabled=false 时整页提示「AI 代理已全局停用」', async () => {
    mocks.getAgentSwitches.mockResolvedValue({ enabled: false, dailyPostLimit: 20, dailyTokenBudget: 200000 })
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('AI 代理已全局停用')
  })

  it('列表只露 userId 后 6 位做标识（id 与人设 id 不混用）', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('…000001')
    expect(wrapper.text()).not.toContain(AGENT.id)
  })

  it('运行日志里 SKIPPED 会显示原因', async () => {
    mocks.listAdminAgentRuns.mockResolvedValue({
      items: [
        {
          id: 'run-skip',
          agentId: AGENT.userId,
          startedAt: '2026-11-16T08:00:00Z',
          status: 'SKIPPED',
          outputType: 'NONE',
          reason: 'AI 代理总开关已关闭（agent.enabled=false）',
        },
      ],
      total: 1,
      page: 1,
      size: 20,
    })
    const wrapper = await mountView()
    await wrapper.findAll('button').find((b) => b.text().trim() === '日志')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已跳过')
    expect(wrapper.text()).toContain('总开关已关闭')
  })

  it('页头显示联网搜索已启用（SearXNG）', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('联网搜索：已启用（SearXNG）')
    expect(wrapper.text()).not.toContain('只能用站内检索')
  })

  it('agent.search.provider=NONE 时提示未配置且只能站内检索', async () => {
    mocks.listAdminSettings.mockResolvedValue([
      {
        group: 'AI',
        label: 'AI',
        items: [
          {
            key: 'agent.search.provider',
            group: 'AI',
            label: 'AI 代理联网搜索',
            type: 'ENUM',
            value: 'NONE',
            defaultValue: 'NONE',
            overridden: false,
            publicVisible: false,
          },
        ],
      },
    ])
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('联网搜索：未配置')
    expect(wrapper.text()).toContain('只能用站内检索，无法联网找话题')
  })

  it('读不到配置项时不误报「未配置」，显示状态未知', async () => {
    mocks.listAdminSettings.mockRejectedValue(new Error('403'))
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('联网搜索：状态未知')
    expect(wrapper.text()).not.toContain('联网搜索：未配置')
  })

  it('运行日志里的 web_search 结构化展示查询词、命中条数与标题', async () => {
    mocks.listAdminAgentRuns.mockResolvedValue({
      items: [
        {
          id: 'run-search',
          agentId: AGENT.userId,
          startedAt: '2026-11-16T09:00:00Z',
          status: 'SUCCESS',
          outputType: 'NONE',
          tokensIn: 200,
          tokensOut: 30,
          toolCallsJson: {
            action: 'web_search',
            args: { query: '海岛建筑 热点' },
            summary: '联网检索完成',
            results: [
              { title: 'A 岛新地标' },
              { title: 'B 岛红石农场' },
              { title: 'C 岛交通改造' },
              { title: 'D 岛不该出现' },
            ],
          },
        },
      ],
      total: 1,
      page: 1,
      size: 20,
    })
    const wrapper = await mountView()
    await wrapper.findAll('button').find((b) => b.text().trim() === '日志')!.trigger('click')
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('联网搜索')
    expect(text).toContain('海岛建筑 热点')
    expect(text).toContain('命中 4 条')
    // 标题只渲染前 3 条（原始 JSON 折叠里仍有全量，所以这里数列表项而不是搜文本）
    const titles = wrapper.findAll('.tool-call__title-list li').map((item) => item.text())
    expect(titles).toEqual(['A 岛新地标', 'B 岛红石农场', 'C 岛交通改造'])
  })

  it('运行一次 / 暂停分别调用对应接口', async () => {
    const wrapper = await mountView()
    const buttons = wrapper.findAll('button')
    const runBtn = buttons.find((b) => b.text().includes('运行一次'))
    expect(runBtn).toBeTruthy()
    await runBtn!.trigger('click')
    await flushPromises()
    expect(mocks.runAdminAgentNow).toHaveBeenCalledWith(AGENT.userId)

    const pauseBtn = buttons.find((b) => b.text().trim() === '暂停')
    await pauseBtn!.trigger('click')
    await flushPromises()
    expect(mocks.pauseAdminAgent).toHaveBeenCalledWith(AGENT.userId)
  })

  it('打开日志抽屉后展示运行状态、token 与计划 JSON', async () => {
    const wrapper = await mountView()
    const logBtn = wrapper.findAll('button').find((b) => b.text().trim() === '日志')
    await logBtn!.trigger('click')
    await flushPromises()
    expect(mocks.listAdminAgentRuns).toHaveBeenCalledWith(AGENT.userId, { page: 1, size: 30 })
    const text = wrapper.text()
    expect(text).toContain('被拦截')
    expect(text).toContain('命中敏感词')
    expect(text).toContain('token 120 / 40')
  })
})
