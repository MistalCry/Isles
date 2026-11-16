import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

// 举报页的状态列是 D1/D2 修复后的对外表现：后端统一为 PENDING/ACCEPTED/REJECTED，
// 页面必须把三个枚举都映射成中文标签，且未知值不能渲染成空白。
const mocks = vi.hoisted(() => ({
  identity: { value: null as unknown },
  listAdminReports: vi.fn(),
  handleAdminReport: vi.fn(),
}))

vi.mock('@/composables/useAdminAccess', () => ({
  useAdminAccess: () => ({ identity: mocks.identity }),
}))

vi.mock('@/pages/admin/adminApi', () => ({
  listAdminReports: mocks.listAdminReports,
  handleAdminReport: mocks.handleAdminReport,
}))

import AdminReportsView from '@/pages/admin/AdminReportsView.vue'

const MODERATOR = {
  admin: true,
  userId: '01USER0000000000000000002',
  username: 'moderator',
  platformRole: 'MODERATOR',
  permissions: ['REPORT_READ', 'REPORT_HANDLE'],
}

function makeReport(id: string, status: string) {
  return {
    id,
    reporterId: '01USER0000000000000000003',
    targetType: 'POST',
    targetId: '01POST0000000000000000000',
    reason: 'SPAM',
    detail: '广告',
    status,
    createdAt: '2026-11-15T10:00:00Z',
    channelId: '01CHAN0000000000000000000',
    handlerId: null,
    handleAction: null,
    handleNote: null,
    handledAt: null,
  }
}

const PAGE = {
  items: [makeReport('01REPORT00000000000000001', 'PENDING'), makeReport('01REPORT00000000000000002', 'ACCEPTED'), makeReport('01REPORT00000000000000003', 'REJECTED')],
  total: 3,
  available: true,
  dependency: null,
  page: 1,
  size: 20,
}

let lastWrapper: Awaited<ReturnType<typeof mount>> | null = null

function wrapperTextOfLastMount(): string {
  return lastWrapper?.text() ?? ''
}

async function mountView() {
  mocks.identity.value = MODERATOR
  mocks.listAdminReports.mockResolvedValue(PAGE)
  const wrapper = mount(AdminReportsView, {
    // el-table 需要真实组件才能把行数据喂给列插槽，这里直接用 Element Plus 本体；
    // el-tag 仍走 src/test/setup.ts 的轻量桩，桩会把 type 透传到 DOM 上，够断言用。
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  lastWrapper = wrapper
  return wrapper
}

describe('AdminReportsView 举报状态列', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listAdminReports.mockResolvedValue(PAGE)
  })

  it('PENDING / ACCEPTED / REJECTED 分别渲染为待处理 / 已通过 / 已驳回', async () => {
    const wrapper = await mountView()
    const text = wrapper.text()
    expect(text).toContain('待处理')
    expect(text).toContain('已通过')
    expect(text).toContain('已驳回')
    // 旧状态值不应再出现（V6 起统一）
    expect(text).not.toContain('DISMISSED')
    expect(text).not.toContain('RESOLVED')
  })

  it('状态标签按状态区分类型（已通过=success、已驳回=info、待处理=warning）', async () => {
    const wrapper = await mountView()
    const tags = wrapper.findAll('.el-tag-stub')
    expect(tags.map((tag) => tag.text())).toEqual(['待处理', '已通过', '已驳回'])
    expect(tags.map((tag) => tag.attributes('type'))).toEqual(['warning', 'success', 'info'])
  })

  it('只有 PENDING 行可点「处置」，MODERATOR 有 REPORT_HANDLE 时不整体禁用', async () => {
    const wrapper = await mountView()
    const rows = wrapper.findAll('.el-table__row')
    expect(rows.length).toBe(3)
    const buttons = wrapper.findAll('button').filter((item) => item.text().includes('处置'))
    expect(buttons.length).toBe(3)
    expect(buttons[0].attributes('disabled')).toBeUndefined()
    expect(buttons[1].attributes('disabled')).toBeDefined()
    expect(buttons[2].attributes('disabled')).toBeDefined()
  })

  it('默认筛选「待处理」：进页面就只拉 PENDING，不必先手动切', async () => {
    await mountView()
    const firstCall = mocks.listAdminReports.mock.calls[0][0]
    expect(firstCall.status).toBe('PENDING')
    expect(firstCall.page).toBe(1)
    // 筛选下拉默认展示「待处理」
    expect(wrapperTextOfLastMount()).toContain('待处理')
  })

  it('操作列存在且钉在右侧（横向滚动不会把处置入口藏起来）', async () => {
    const wrapper = await mountView()
    const headers = wrapper.findAll('.el-table__header th')
    const actionHeader = headers.at(-1)!
    expect(actionHeader.text()).toContain('操作')
    expect(actionHeader.classes()).toContain('el-table-fixed-column--right')
    // 其余列不应被钉住
    expect(headers[0].classes()).not.toContain('el-table-fixed-column--right')
  })

  it('没有 REPORT_HANDLE 的角色看到全禁用的处置按钮', async () => {
    mocks.identity.value = { ...MODERATOR, permissions: ['REPORT_READ'] }
    const wrapper = mount(AdminReportsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const buttons = wrapper.findAll('button').filter((item) => item.text().includes('处置'))
    expect(buttons.length).toBe(3)
    expect(buttons.every((item) => item.attributes('disabled') !== undefined)).toBe(true)
  })
})
