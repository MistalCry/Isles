import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'

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
  listAdminAiModels: vi.fn(),
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
    isRequestTimeout: actual.isRequestTimeout,
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
    listAdminAiModels: mocks.listAdminAiModels,
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

const MODELS = [
  { id: 'm-1', name: 'Qwen3 8B', model: 'qwen3-vl-8b-instruct', enabled: true, isDefault: true },
  { id: 'm-2', name: 'DeepSeek Chat', model: 'deepseek-chat', enabled: true, isDefault: false },
  { id: 'm-3', name: '已停用模型', model: 'llama3-8b', enabled: false, isDefault: false },
]

async function mountView() {
  const wrapper = mount(AdminAgentsView, {
    global: {
      plugins: [ElementPlus],
      // el-input-number 在 jsdom 下会因内部 ref 未挂载而抛错（Element Plus 已知问题），
      // 这里只关心「页面行为」，数值控件用桩即可。
      // el-drawer 的桩要带上 footer 插槽，否则抽屉底部的保存按钮点不到。
      stubs: {
        teleport: true,
        'el-input-number': true,
        'el-drawer': { template: '<div class="el-drawer-stub"><slot /><slot name="footer" /></div>' },
        // 下拉只在展开后才渲染选项，用桩把「选项标签」直接落到 DOM 上
        'el-select': {
          name: 'ElSelectStub',
          props: ['modelValue', 'loading', 'multiple', 'disabled'],
          template: '<div class="el-select-stub" :data-value="String(modelValue ?? \'\')"><slot /></div>',
        },
        'el-option': {
          props: ['label', 'value'],
          template: '<span class="el-option-stub" :data-value="String(value ?? \'\')"><slot>{{ label }}</slot></span>',
        },
      },
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
        {
          key: 'agent.search.baseUrl',
          group: 'AI',
          label: 'SearXNG 地址',
          type: 'STRING',
          value: 'http://127.0.0.1:8888',
          defaultValue: '',
          overridden: true,
          publicVisible: false,
        },
      ],
    },
  ])
  mocks.listAdminChannels.mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
  mocks.listAdminAiModels.mockResolvedValue(MODELS)
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

  it('provider=SEARXNG 但没填 baseUrl 时不能报「已启用」（K8）', async () => {
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
          {
            key: 'agent.search.baseUrl',
            group: 'AI',
            label: 'SearXNG 地址',
            type: 'STRING',
            value: '',
            defaultValue: '',
            overridden: false,
            publicVisible: false,
          },
        ],
      },
    ])
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('联网搜索：已启用但未填地址')
    expect(wrapper.text()).toContain('agent.search.baseUrl')
    expect(wrapper.text()).not.toContain('联网搜索：已启用（SearXNG）')
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

  it('运行一次：loading 文案提示最长 3 分钟，超时按「超时」而不是「失败」提示', async () => {
    const warn = vi.spyOn(ElMessage, 'warning')
    let finish: (value: unknown) => void = () => undefined
    mocks.runAdminAgentNow.mockReturnValue(new Promise((resolve) => {
      finish = resolve
    }))
    const wrapper = await mountView()
    const runBtn = wrapper.findAll('button').find((b) => b.text().includes('运行一次'))!
    await runBtn.trigger('click')
    await flushPromises()
    // 按钮在跑的时候要告诉用户会等很久
    expect(runBtn.text()).toContain('正在运行，最长约 3 分钟')

    finish({ status: 'SUCCESS', reason: null, run: null })
    await flushPromises()

    // 超时：后端可能已经成功了，不能报「失败」
    warn.mockClear()
    mocks.runAdminAgentNow.mockRejectedValue({ cause: { code: 'ECONNABORTED' } })
    await runBtn.trigger('click')
    await flushPromises()
    expect(warn).toHaveBeenCalledWith('已超时，请稍后刷新运行日志查看结果')
    expect(wrapper.text()).not.toContain('操作失败')
    warn.mockRestore()
  })

  it('人设抽屉带头像上传，保存时把 avatarUrl 一起提交', async () => {
    mocks.listAdminAgents.mockResolvedValue({
      items: [{ ...AGENT, avatarUrl: '/media/agent-avatar.png' }],
      total: 1,
      page: 1,
      size: 20,
    })
    const wrapper = await mountView()
    await wrapper.findAll('button').find((b) => b.text().trim() === '编辑')!.trigger('click')
    await flushPromises()
    // 1:1 裁剪的头像上传（ImageCropUpload）
    expect(wrapper.text()).toContain('头像')
    expect(wrapper.find('.image-crop-upload').exists()).toBe(true)

    await wrapper.findAll('button').find((b) => b.text().trim() === '保存')!.trigger('click')
    await flushPromises()
    expect(mocks.updateAdminAgent).toHaveBeenCalledWith(
      AGENT.userId,
      expect.objectContaining({ avatarUrl: '/media/agent-avatar.png' }),
    )
  })

  it('列表显示代理所用模型，未指定时显示「跟随默认」', async () => {
    mocks.listAdminAgents.mockResolvedValue({
      items: [
        { ...AGENT, modelConfigId: 'm-1', modelName: 'Qwen3 8B' },
        { ...AGENT, id: 'p2', userId: 'u2', displayName: '另一个代理' },
      ],
      total: 2,
      page: 1,
      size: 20,
    })
    const wrapper = await mountView()
    const text = wrapper.text()
    expect(text).toContain('模型')
    expect(text).toContain('Qwen3 8B')
    expect(text).toContain('跟随默认')
  })

  it('人设抽屉的模型下拉：只列 enabled，第一项是跟随平台默认，并显示模型标识', async () => {
    const wrapper = await mountView()
    await wrapper.findAll('button').find((b) => b.text().trim() === '编辑')!.trigger('click')
    await flushPromises()

    const select = wrapper.findAll('.el-select-stub').find((item) => item.text().includes('跟随平台默认'))
    expect(select).toBeTruthy()
    const labels = select!.findAll('.el-option-stub').map((item) => item.text())
    // 第一项固定是「跟随平台默认」，停用的模型不出现
    expect(labels[0]).toBe('跟随平台默认')
    expect(labels.join(' ')).toContain('Qwen3 8B')
    expect(labels.join(' ')).toContain('DeepSeek Chat')
    expect(labels.join(' ')).not.toContain('已停用模型')
    // 副标题（模型标识）也渲染了
    expect(select!.text()).toContain('qwen3-vl-8b-instruct')
  })

  it('模型改了才下发 modelConfigId；选「跟随默认」下发 null', async () => {
    mocks.listAdminAgents.mockResolvedValue({
      items: [{ ...AGENT, modelConfigId: 'm-1', modelName: 'Qwen3 8B' }],
      total: 1,
      page: 1,
      size: 20,
    })
    // 后端保存后会回显模型字段：没下发（undefined）时保持原值，下发 null 才是跟随默认
    mocks.updateAdminAgent.mockImplementation(async (_id: string, payload: { modelConfigId?: string | null }) => ({
      ...AGENT,
      modelName: payload.modelConfigId === null ? null : 'Qwen3 8B',
      modelConfigId: payload.modelConfigId === undefined ? 'm-1' : payload.modelConfigId,
    }))
    const wrapper = await mountView()

    // 第一次：不动模型，保存时不下发该字段
    await wrapper.findAll('button').find((b) => b.text().trim() === '编辑')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().trim() === '保存')!.trigger('click')
    await flushPromises()
    expect(mocks.updateAdminAgent.mock.calls[0][1].modelConfigId).toBeUndefined()

    // 第二次：切到「跟随平台默认」→ 下发 null
    await wrapper.findAll('button').find((b) => b.text().trim() === '编辑')!.trigger('click')
    await flushPromises()
    const select = wrapper
      .findAllComponents({ name: 'ElSelectStub' })
      .find((item) => item.text().includes('跟随平台默认'))!
    select.vm.$emit('update:modelValue', '')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().trim() === '保存')!.trigger('click')
    await flushPromises()
    expect(mocks.updateAdminAgent.mock.calls[1][1].modelConfigId).toBeNull()

    // 第三次：选具体模型 → 下发 id
    await wrapper.findAll('button').find((b) => b.text().trim() === '编辑')!.trigger('click')
    await flushPromises()
    const again = wrapper
      .findAllComponents({ name: 'ElSelectStub' })
      .find((item) => item.text().includes('跟随平台默认'))!
    again.vm.$emit('update:modelValue', 'm-2')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().trim() === '保存')!.trigger('click')
    await flushPromises()
    expect(mocks.updateAdminAgent.mock.calls[2][1].modelConfigId).toBe('m-2')
  })

  it('没有 AI 模型权限（403）时下拉禁用并说明原因', async () => {
    mocks.listAdminAiModels.mockRejectedValue(new Error('403'))
    const wrapper = await mountView()
    await wrapper.findAll('button').find((b) => b.text().trim() === '编辑')!.trigger('click')
    await flushPromises()
    const select = wrapper
      .findAllComponents({ name: 'ElSelectStub' })
      .find((item) => item.text().includes('跟随平台默认'))!
    expect(select.props('disabled')).toBe(true)
    expect(wrapper.text()).toContain('读不到模型列表')
  })

  it('指定模型已被停用时，下拉保留只读选项而不是露出 26 位 id', async () => {
    mocks.listAdminAgents.mockResolvedValue({
      items: [{ ...AGENT, modelConfigId: 'm-9', modelName: '旧模型' }],
      total: 1,
      page: 1,
      size: 20,
    })
    const wrapper = await mountView()
    await wrapper.findAll('button').find((b) => b.text().trim() === '编辑')!.trigger('click')
    await flushPromises()
    const select = wrapper
      .findAllComponents({ name: 'ElSelectStub' })
      .find((item) => item.text().includes('跟随平台默认'))!
    const orphan = select.findAll('.el-option-stub').find((item) => item.attributes('data-value') === 'm-9')
    expect(orphan).toBeTruthy()
    expect(orphan!.text()).toBe('旧模型')
  })

  it('保存人设后行内 userId 不被写坏，日志/运行一次仍指向正确的代理（K7）', async () => {
    // 真实故障：PUT 返回的人设视图可能没有 id，toAgentUser 会拿 userId 当 id；
    // 若直接整体覆盖行数据，行内 userId 就被写坏，之后「日志」「运行一次」全部 404。
    mocks.updateAdminAgent.mockResolvedValue({ id: AGENT.id, displayName: AGENT.displayName })
    const wrapper = await mountView()

    await wrapper.findAll('button').find((b) => b.text().trim() === '编辑')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().trim() === '保存')!.trigger('click')
    await flushPromises()

    // 行内标识仍是 userId 后 6 位（没被替换成别的东西）
    expect(wrapper.text()).toContain('…000001')

    await wrapper.findAll('button').find((b) => b.text().trim() === '日志')!.trigger('click')
    await flushPromises()
    expect(mocks.listAdminAgentRuns).toHaveBeenCalledWith(AGENT.userId, { page: 1, size: 30 })
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

  it('保存模型后列表立即显示新模型名（不用刷新页面）', async () => {
    mocks.listAdminAgents.mockResolvedValue({ items: [{ ...AGENT }], total: 1, page: 1, size: 20 })
    // 后端保存后回显 modelName；页面把它并回列表行
    mocks.updateAdminAgent.mockResolvedValue({
      ...AGENT,
      modelConfigId: 'm-2',
      modelName: 'DeepSeek Chat',
    })
    const wrapper = await mountView()
    // 保存前是「跟随默认」
    expect(wrapper.text()).toContain('跟随默认')

    await wrapper.findAll('button').find((b) => b.text().trim() === '编辑')!.trigger('click')
    await flushPromises()
    const select = wrapper
      .findAllComponents({ name: 'ElSelectStub' })
      .find((item) => item.text().includes('跟随平台默认'))!
    select.vm.$emit('update:modelValue', 'm-2')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().trim() === '保存')!.trigger('click')
    await flushPromises()

    expect(mocks.updateAdminAgent).toHaveBeenCalledWith(
      AGENT.userId,
      expect.objectContaining({ modelConfigId: 'm-2' }),
    )
    // 只看模型列：抽屉里还有「跟随平台默认」选项与提示文案，整页 text 会误报
    const modelCell = wrapper.find('.agent-model')
    expect(modelCell.exists()).toBe(true)
    expect(modelCell.text()).toContain('DeepSeek Chat')
    expect(modelCell.text()).not.toContain('跟随默认')
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
