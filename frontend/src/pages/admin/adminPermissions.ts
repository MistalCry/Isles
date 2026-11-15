import type { Component } from 'vue'
import {
  Bell,
  DataAnalysis,
  Document,
  Files,
  List,
  Picture,
  Setting,
  Tools,
  User,
  UserFilled,
  Warning,
} from '@element-plus/icons-vue'
import type { AdminIdentity, AdminPermission, AdminRole } from './adminApi'

/**
 * 后台菜单 → 权限点映射（docs/11 §7.1）。
 *
 * <p>菜单显隐只看权限点，不看 `platformRole`：角色是权限点的组合，
 * 后端新增角色时前端无需改动。权限点缺失时菜单不渲染，路由也会被兜底跳走。
 */
export interface AdminNavItem {
  name: string
  path: string
  /** i18n key（菜单文案） */
  labelKey: string
  icon: Component
  permission: AdminPermission
}

export const ADMIN_NAV: AdminNavItem[] = [
  { name: 'admin-overview', path: '/admin/overview', labelKey: 'admin.layout.overview', icon: DataAnalysis, permission: 'STATS_READ' },
  { name: 'admin-settings', path: '/admin/settings', labelKey: 'admin.layout.settings', icon: Tools, permission: 'CONFIG_READ' },
  { name: 'admin-announcements', path: '/admin/announcements', labelKey: 'admin.layout.announcements', icon: Bell, permission: 'ANNOUNCEMENT_MANAGE' },
  { name: 'admin-words', path: '/admin/sensitive-words', labelKey: 'admin.layout.words', icon: Warning, permission: 'WORD_MANAGE' },
  { name: 'admin-reports', path: '/admin/reports', labelKey: 'admin.layout.reports', icon: Document, permission: 'REPORT_READ' },
  { name: 'admin-content', path: '/admin/content', labelKey: 'admin.layout.content', icon: Files, permission: 'CONTENT_READ' },
  { name: 'admin-users', path: '/admin/users', labelKey: 'admin.layout.users', icon: User, permission: 'USER_READ' },
  { name: 'admin-channels', path: '/admin/channels', labelKey: 'admin.channels.title', icon: Picture, permission: 'CONTENT_READ' },
  { name: 'admin-admins', path: '/admin/admins', labelKey: 'admin.layout.admins', icon: UserFilled, permission: 'ADMIN_MANAGE' },
  { name: 'admin-audit', path: '/admin/audit-logs', labelKey: 'admin.layout.audit', icon: List, permission: 'AUDIT_READ' },
  { name: 'admin-ai-models', path: '/admin/ai-models', labelKey: 'admin.layout.ai', icon: Setting, permission: 'AI_MANAGE' },
]

/** 是否有某个权限点 */
export function hasPermission(identity: AdminIdentity | null | undefined, permission: AdminPermission): boolean {
  if (!identity?.admin) return false
  return identity.permissions?.includes(permission) ?? false
}

/** 当前身份可见的菜单 */
export function visibleNav(identity: AdminIdentity | null | undefined): AdminNavItem[] {
  return ADMIN_NAV.filter((item) => hasPermission(identity, item.permission))
}

/** 角色 → i18n key（后台身份标签与授权页下拉共用） */
export const ADMIN_ROLE_LABEL_KEYS: Record<AdminRole, string> = {
  SUPER_ADMIN: 'admin.role.superAdmin',
  OPERATOR: 'admin.role.operator',
  MODERATOR: 'admin.role.moderator',
  VIEWER: 'admin.role.viewer',
}

export const ADMIN_ROLES: AdminRole[] = ['SUPER_ADMIN', 'OPERATOR', 'MODERATOR', 'VIEWER']

/** 权限点 → i18n key（授权页展示「该角色能做什么」时用） */
export const ADMIN_PERMISSION_LABEL_KEYS: Record<AdminPermission, string> = {
  CONFIG_READ: 'admin.permission.configRead',
  CONFIG_WRITE: 'admin.permission.configWrite',
  ADMIN_MANAGE: 'admin.permission.adminManage',
  AUDIT_READ: 'admin.permission.auditRead',
  USER_READ: 'admin.permission.userRead',
  CONTENT_READ: 'admin.permission.contentRead',
  REPORT_READ: 'admin.permission.reportRead',
  USER_BAN: 'admin.permission.userBan',
  USER_PENALTY: 'admin.permission.userPenalty',
  CONTENT_TAKEDOWN: 'admin.permission.contentTakedown',
  REPORT_HANDLE: 'admin.permission.reportHandle',
  AI_MANAGE: 'admin.permission.aiManage',
  ANNOUNCEMENT_MANAGE: 'admin.permission.announcementManage',
  WORD_MANAGE: 'admin.permission.wordManage',
  STATS_READ: 'admin.permission.statsRead',
}
