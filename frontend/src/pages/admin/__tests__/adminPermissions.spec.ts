import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import i18n from '@/i18n'
import { ADMIN_NAV, hasPermission, visibleNav } from '@/pages/admin/adminPermissions'
import type { AdminIdentity } from '@/pages/admin/adminApi'

// 菜单显隐只看权限点，不看角色名：角色变化时前端不应改代码。
// 这组用例同时锁住「纯函数（visibleNav）」与「布局渲染（AdminLayout）」两层，
// 避免出现「函数对了但模板没接上」的情况。
// AdminLayout 在模板里直接用 identity（Vue 只对真正的 ref 自动解包），
// 所以这里必须给一个真 ref，而不是 { value } 形状的普通对象。
vi.mock('@/composables/useAdminAccess', async () => {
  const { ref } = await import('vue')
  const identity = ref(null)
  const ensure = vi.fn()
  return {
    useAdminAccess: () => ({ identity, ensure }),
    __adminAccessTest: { identity, ensure },
  }
})

import * as adminAccessModule from '@/composables/useAdminAccess'

const access = (
  adminAccessModule as unknown as {
    __adminAccessTest: { identity: { value: unknown }; ensure: ReturnType<typeof vi.fn> }
  }
).__adminAccessTest

import AdminLayout from '@/pages/admin/AdminLayout.vue'

const ALL_PERMISSIONS = ADMIN_NAV.map((item) => item.permission)

function identityOf(role: string, permissions: string[]): AdminIdentity {
  return {
    admin: true,
    userId: '01USER0000000000000000009',
    username: 'tester',
    platformRole: role,
    permissions,
  }
}

const SUPER_ADMIN = identityOf('SUPER_ADMIN', ALL_PERMISSIONS)
const OPERATOR = identityOf('OPERATOR', [
  'CONFIG_READ',
  'CONFIG_WRITE',
  'AUDIT_READ',
  'AI_MANAGE',
  'ANNOUNCEMENT_MANAGE',
  'WORD_MANAGE',
  'USER_READ',
  'CONTENT_READ',
  'REPORT_READ',
  'STATS_READ',
])
const MODERATOR = identityOf('MODERATOR', [
  'CONFIG_READ',
  'AUDIT_READ',
  'USER_READ',
  'CONTENT_READ',
  'REPORT_READ',
  'USER_BAN',
  'USER_PENALTY',
  'CONTENT_TAKEDOWN',
  'REPORT_HANDLE',
  'STATS_READ',
])
const VIEWER = identityOf('VIEWER', [
  'CONFIG_READ',
  'AUDIT_READ',
  'USER_READ',
  'CONTENT_READ',
  'REPORT_READ',
  'STATS_READ',
])

const labelOf = (name: string) => {
  const item = ADMIN_NAV.find((entry) => entry.name === name)
  if (!item) throw new Error(`菜单项 ${name} 不存在`)
  return i18n.global.t(item.labelKey)
}

async function mountLayout(identity: AdminIdentity | null) {
  access.identity.value = identity
  access.ensure.mockResolvedValue(undefined)
  const wrapper = mount(AdminLayout)
  await flushPromises()
  return wrapper
}

function navLabels(wrapper: Awaited<ReturnType<typeof mountLayout>>): string[] {
  return wrapper.findAll('.admin-nav__item').map((item) => item.text().trim())
}

describe('adminPermissions 菜单显隐', () => {
  it('超管看到全部菜单', () => {
    expect(visibleNav(SUPER_ADMIN)).toHaveLength(ADMIN_NAV.length)
  })

  it('MODERATOR 看不到管理员/公告/词库/AI 配置，但能看到举报与内容', () => {
    const names = visibleNav(MODERATOR).map((item) => item.name)
    expect(names).toContain('admin-reports')
    expect(names).toContain('admin-content')
    expect(names).not.toContain('admin-admins')
    expect(names).not.toContain('admin-announcements')
    expect(names).not.toContain('admin-words')
    expect(names).not.toContain('admin-ai-models')
  })

  it('OPERATOR 看不到管理员菜单，但能看到公告/词库/AI 配置', () => {
    const names = visibleNav(OPERATOR).map((item) => item.name)
    expect(names).toContain('admin-announcements')
    expect(names).toContain('admin-words')
    expect(names).toContain('admin-ai-models')
    expect(names).not.toContain('admin-admins')
  })

  it('VIEWER 只读：能看配置与举报，看不到任何管理类菜单', () => {
    const names = visibleNav(VIEWER).map((item) => item.name)
    expect(names).toEqual(
      expect.arrayContaining(['admin-overview', 'admin-settings', 'admin-reports', 'admin-content']),
    )
    expect(names).not.toContain('admin-admins')
    expect(names).not.toContain('admin-announcements')
    expect(names).not.toContain('admin-words')
    expect(names).not.toContain('admin-ai-models')
  })

  it('无身份或 admin=false 时菜单为空', () => {
    expect(visibleNav(null)).toEqual([])
    expect(visibleNav({ ...VIEWER, admin: false })).toEqual([])
  })

  it('hasPermission 对缺失 permissions 字段容错', () => {
    expect(hasPermission({ ...VIEWER, permissions: undefined as unknown as string[] }, 'CONFIG_READ')).toBe(false)
    expect(hasPermission(VIEWER, 'CONFIG_READ')).toBe(true)
  })

  it('菜单项权限点唯一且路径不重复（防止复制粘贴出错）', () => {
    const permissions = ADMIN_NAV.map((item) => item.permission)
    const paths = ADMIN_NAV.map((item) => item.path)
    expect(new Set(paths).size).toBe(paths.length)
    expect(permissions.every((value) => typeof value === 'string' && value.length > 0)).toBe(true)
  })
})

describe('AdminLayout 菜单渲染', () => {
  beforeEach(() => {
    access.ensure.mockReset()
  })

  it('超管渲染全部菜单项，文案来自 i18n', async () => {
    const wrapper = await mountLayout(SUPER_ADMIN)
    expect(navLabels(wrapper)).toEqual(ADMIN_NAV.map((item) => i18n.global.t(item.labelKey)))
  })

  it('MODERATOR 不渲染管理员/公告/词库/AI 菜单', async () => {
    const wrapper = await mountLayout(MODERATOR)
    const labels = navLabels(wrapper)
    expect(labels).toContain(labelOf('admin-reports'))
    expect(labels).toContain(labelOf('admin-content'))
    expect(labels).not.toContain(labelOf('admin-admins'))
    expect(labels).not.toContain(labelOf('admin-announcements'))
    expect(labels).not.toContain(labelOf('admin-words'))
    expect(labels).not.toContain(labelOf('admin-ai-models'))
  })

  it('非管理员看到「没有平台管理权限」且不渲染导航', async () => {
    const wrapper = await mountLayout({ ...VIEWER, admin: false, permissions: [] })
    expect(wrapper.text()).toContain(i18n.global.t('admin.noPermission'))
    expect(wrapper.findAll('.admin-nav__item')).toHaveLength(0)
  })
})
