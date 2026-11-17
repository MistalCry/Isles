import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

// 配置中心是「元数据驱动」的页面：后端给 type，前端只负责选控件。
// 这组用例锁住「4 种 type → 4 种控件」的映射，避免以后新增 type 时静默退化成文本框。
const mocks = vi.hoisted(() => ({
  identity: { value: null as unknown },
  listAdminSettings: vi.fn(),
  updateAdminSetting: vi.fn(),
  updateAdminSettings: vi.fn(),
  resetAdminSetting: vi.fn(),
  listAdminSettingHistory: vi.fn(),
}))

vi.mock('@/composables/useAdminAccess', () => ({
  useAdminAccess: () => ({ identity: mocks.identity }),
}))

vi.mock('@/pages/admin/adminApi', () => ({
  listAdminSettings: mocks.listAdminSettings,
  updateAdminSetting: mocks.updateAdminSetting,
  updateAdminSettings: mocks.updateAdminSettings,
  resetAdminSetting: mocks.resetAdminSetting,
  listAdminSettingHistory: mocks.listAdminSettingHistory,
}))

import AdminSettingsView from '@/pages/admin/AdminSettingsView.vue'

/** 本地桩：把「控件类型 + 关键 props」落到 DOM 上，便于断言「渲染了哪种控件」 */
const stubs = {
  'el-switch': {
    props: ['modelValue', 'disabled'],
    template: '<span class="el-switch-stub" :data-value="String(modelValue)" :data-disabled="String(!!disabled)" />',
  },
  'el-select': {
    props: ['modelValue', 'disabled'],
    template: '<div class="el-select-stub" :data-value="modelValue"><slot /></div>',
  },
  'el-option': {
    props: ['label', 'value'],
    template: '<span class="el-option-stub">{{ label }}</span>',
  },
  'el-input-number': {
    name: 'ElInputNumberStub',
    props: ['modelValue', 'min', 'max', 'disabled'],
    template:
      '<div class="el-input-number-stub" :data-value="String(modelValue ?? \'\')" :data-min="String(min ?? \'\')" :data-max="String(max ?? \'\')" />',
  },
  'el-drawer': { template: '<div class="el-drawer-stub"><slot /></div>' },
  'el-pagination': { template: '<div class="el-pagination-stub" />' },
  'el-skeleton': { template: '<div class="el-skeleton-stub" />' },
  'el-empty': { template: '<div class="el-empty-stub" />' },
  'el-tag': { props: ['type'], template: '<span class="el-tag-stub"><slot /></span>' },
}

/** 四种 type 各一条，顺序即渲染顺序 */
const GROUPS = [
  {
    group: 'site',
    label: '站点信息',
    items: [
      {
        key: 'site.name',
        group: 'site',
        label: '站点名称',
        type: 'STRING',
        value: '群岛',
        defaultValue: '群岛',
        overridden: false,
        publicVisible: true,
        description: '显示在浏览器标题',
        allowedValues: [],
      },
      {
        key: 'auth.register.enabled',
        group: 'site',
        label: '开放注册',
        type: 'BOOL',
        value: 'true',
        defaultValue: 'true',
        overridden: false,
        publicVisible: true,
        allowedValues: [],
      },
      {
        key: 'media.upload.maxSizeMb',
        group: 'site',
        label: '上传上限',
        type: 'INT',
        value: '20',
        defaultValue: '10',
        overridden: true,
        publicVisible: false,
        minValue: 1,
        maxValue: 100,
        allowedValues: [],
      },
      {
        key: 'ai.qa.dailyQuota',
        group: 'site',
        label: '每日配额',
        type: 'ENUM',
        value: 'STANDARD',
        defaultValue: 'STANDARD',
        overridden: false,
        publicVisible: false,
        allowedValues: ['LOW', 'STANDARD', 'HIGH'],
      },
    ],
  },
]

const WRITER = {
  admin: true,
  userId: '01USER0000000000000000000',
  username: 'admin_demo',
  platformRole: 'SUPER_ADMIN',
  permissions: ['CONFIG_READ', 'CONFIG_WRITE'],
}

const READER = {
  admin: true,
  userId: '01USER0000000000000000001',
  username: 'viewer',
  platformRole: 'VIEWER',
  permissions: ['CONFIG_READ'],
}

async function mountView(identity: unknown) {
  mocks.identity.value = identity
  mocks.listAdminSettings.mockResolvedValue(GROUPS)
  const wrapper = mount(AdminSettingsView, { global: { stubs } })
  await flushPromises()
  return wrapper
}

function rowOf(wrapper: Awaited<ReturnType<typeof mountView>>, key: string) {
  const row = wrapper.findAll('.setting-row').find((item) => item.text().includes(key))
  if (!row) throw new Error(`未找到配置项 ${key} 的渲染行`)
  return row
}

describe('AdminSettingsView 配置中心', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listAdminSettings.mockResolvedValue(GROUPS)
  })

  it('按后端 type 渲染 4 种控件：BOOL→开关、ENUM→下拉、INT→数字框、STRING→文本框', async () => {
    const wrapper = await mountView(WRITER)

    expect(wrapper.findAll('.setting-row')).toHaveLength(4)

    const bool = rowOf(wrapper, 'auth.register.enabled')
    expect(bool.find('.el-switch-stub').exists()).toBe(true)
    expect(bool.find('.el-switch-stub').attributes('data-value')).toBe('true')

    const enumRow = rowOf(wrapper, 'ai.qa.dailyQuota')
    expect(enumRow.find('.el-select-stub').exists()).toBe(true)
    expect(enumRow.findAll('.el-option-stub').map((item) => item.text())).toEqual(['LOW', 'STANDARD', 'HIGH'])

    const int = rowOf(wrapper, 'media.upload.maxSizeMb')
    expect(int.find('.el-input-number-stub').exists()).toBe(true)
    expect(int.find('.el-input-number-stub').attributes('data-value')).toBe('20')
    expect(int.find('.el-input-number-stub').attributes('data-min')).toBe('1')
    expect(int.find('.el-input-number-stub').attributes('data-max')).toBe('100')

    const string = rowOf(wrapper, 'site.name')
    expect(string.find('.el-input-stub').exists()).toBe(true)
    expect(string.find('.el-input-stub').attributes('value')).toBe('群岛')
  })

  it('每行只渲染自己 type 的控件（不会混用）', async () => {
    const wrapper = await mountView(WRITER)
    expect(rowOf(wrapper, 'auth.register.enabled').find('.el-input-stub').exists()).toBe(false)
    expect(rowOf(wrapper, 'ai.qa.dailyQuota').find('.el-switch-stub').exists()).toBe(false)
    expect(rowOf(wrapper, 'media.upload.maxSizeMb').find('.el-select-stub').exists()).toBe(false)
    expect(rowOf(wrapper, 'site.name').find('.el-input-number-stub').exists()).toBe(false)
  })

  it('覆盖状态与前台可见标记来自后端字段', async () => {
    const wrapper = await mountView(WRITER)
    expect(rowOf(wrapper, 'media.upload.maxSizeMb').text()).toContain('已覆盖')
    expect(rowOf(wrapper, 'site.name').text()).toContain('默认值')
    expect(rowOf(wrapper, 'site.name').text()).toContain('前台可见')
    expect(rowOf(wrapper, 'media.upload.maxSizeMb').text()).not.toContain('前台可见')
  })

  it('没有 CONFIG_WRITE 时显示只读提示并禁用控件', async () => {
    const wrapper = await mountView(READER)
    const readonly = wrapper.find('.admin-settings__readonly')
    expect(readonly.exists()).toBe(true)
    expect(readonly.text()).toContain('只能查看配置')
    expect(rowOf(wrapper, 'auth.register.enabled').find('.el-switch-stub').attributes('data-disabled')).toBe('true')
    expect(rowOf(wrapper, 'site.name').find('.el-input-stub').attributes('disabled')).toBeDefined()
  })

  it('INT 为 0 时渲染成数字框，且 0 能被提交（不会被当空值丢掉）', async () => {
    // agent.minPostIntervalMinutes / minCommentIntervalMinutes 是通用 INT 配置项，
    // 0 是合法值（不限制间隔），这是最容易在「空值判断」里被吃掉的边界。
    mocks.identity.value = WRITER
    const zeroItem = {
      key: 'agent.minPostIntervalMinutes',
      group: 'agent',
      label: '发帖最小间隔',
      type: 'INT',
      value: '0',
      defaultValue: '30',
      overridden: true,
      publicVisible: false,
      minValue: 0,
      maxValue: 720,
      allowedValues: [],
    }
    const thirtyItem = {
      ...zeroItem,
      key: 'agent.minCommentIntervalMinutes',
      label: '评论最小间隔',
      value: '30',
      overridden: false,
    }
    mocks.listAdminSettings.mockResolvedValue([{ group: 'agent', label: 'AI 代理', items: [zeroItem, thirtyItem] }])
    mocks.updateAdminSettings.mockResolvedValue([{ ...thirtyItem, value: '0' }])

    const wrapper = mount(AdminSettingsView, { global: { stubs } })
    await flushPromises()

    // 渲染：0 不能变成空输入框
    const zeroRow = rowOf(wrapper, 'agent.minPostIntervalMinutes')
    expect(zeroRow.find('.el-input-number-stub').attributes('data-value')).toBe('0')
    expect(zeroRow.find('.el-input-number-stub').attributes('data-min')).toBe('0')

    // 提交：30 改成 0，草稿要记成 '0' 并原样提交
    const thirtyRow = rowOf(wrapper, 'agent.minCommentIntervalMinutes')
    thirtyRow.findComponent({ name: 'ElInputNumberStub' }).vm.$emit('update:modelValue', 0)
    await flushPromises()

    const saveAll = wrapper.findAll('button').find((item) => item.text().includes('保存全部'))!
    expect(saveAll.text()).toContain('(1)')
    await saveAll.trigger('click')
    await flushPromises()
    expect(mocks.updateAdminSettings).toHaveBeenCalledWith([
      { key: 'agent.minCommentIntervalMinutes', value: '0' },
    ])
  })

  it('有 CONFIG_WRITE 时不显示只读提示，改动后「保存全部」可用并显示待保存条数', async () => {
    const wrapper = await mountView(WRITER)
    expect(wrapper.find('.admin-settings__readonly').exists()).toBe(false)

    const saveAll = wrapper.findAll('button').find((item) => item.text().includes('保存全部'))
    expect(saveAll).toBeTruthy()
    expect(saveAll!.attributes('disabled')).toBeDefined()

    await rowOf(wrapper, 'site.name').find('.el-input-stub').setValue('群岛新名')
    const after = wrapper.findAll('button').find((item) => item.text().includes('保存全部'))
    expect(after!.text()).toContain('(1)')
    expect(after!.attributes('disabled')).toBeUndefined()
  })
})
