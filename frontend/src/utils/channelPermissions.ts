import type { Channel, ChannelSummary } from '@/types/models'

/**
 * 频道权限点（与后端 ChannelPermissions 保持一致，docs/03 §2.2）。
 * 前端只做展示层隐藏；最终权限以服务端逐资源校验为准（docs/02 §6.3）。
 */
export const CHANNEL_PERMISSIONS = {
  POST_CREATE: 'post.create',
  COMMENT_CREATE: 'comment.create',
  POST_PIN: 'post.pin',
  POST_ESSENCE: 'post.essence',
  POST_DELETE: 'post.delete',
  COMMENT_DELETE: 'comment.delete',
  CHANNEL_SETTINGS: 'channel.settings',
  MEMBER_INVITE: 'channel.member.invite',
  MEMBER_REMOVE: 'channel.member.remove',
  MEMBER_MUTE: 'channel.member.mute',
  MUTE_ALL: 'channel.mute_all',
  ROLE_MANAGE: 'channel.role.manage',
  INVITE_CREATE: 'channel.invite.create',
  INVITE_APPROVE: 'channel.invite.approve',
  VISIBILITY: 'channel.visibility',
  BOARD_MANAGE: 'channel.board.manage',
  CONTENT_MANAGE: 'channel.content.manage',
  LOG_VIEW: 'channel.log.view',
  DATA_VIEW: 'channel.data.view',
  AI_MANAGE: 'channel.ai.manage',
  DISSOLVE: 'channel.dissolve',
} as const

export type ChannelPermission = (typeof CHANNEL_PERMISSIONS)[keyof typeof CHANNEL_PERMISSIONS]

export const ALL_CHANNEL_PERMISSIONS: string[] = Object.values(CHANNEL_PERMISSIONS)

/** 仅频道主拥有的权限（超级管理员也不可解散频道）。 */
export const OWNER_ONLY_PERMISSIONS: string[] = [CHANNEL_PERMISSIONS.DISSOLVE]

export interface PermissionPointMeta {
  code: string
  name: string
  group: string
  description?: string
}

/** 身份组编辑器使用的权限点清单（中文名与后端权限点一一对应）。 */
export const PERMISSION_POINTS: PermissionPointMeta[] = [
  { code: CHANNEL_PERMISSIONS.POST_CREATE, name: '发帖', group: '内容' },
  { code: CHANNEL_PERMISSIONS.COMMENT_CREATE, name: '评论与回复', group: '内容' },
  { code: CHANNEL_PERMISSIONS.POST_DELETE, name: '删除帖子', group: '内容管理' },
  { code: CHANNEL_PERMISSIONS.COMMENT_DELETE, name: '删除评论', group: '内容管理' },
  { code: CHANNEL_PERMISSIONS.POST_PIN, name: '置顶帖子', group: '内容管理' },
  { code: CHANNEL_PERMISSIONS.POST_ESSENCE, name: '设置精华', group: '内容管理' },
  { code: CHANNEL_PERMISSIONS.CONTENT_MANAGE, name: '内容管理', group: '内容管理' },
  { code: CHANNEL_PERMISSIONS.BOARD_MANAGE, name: '板块管理', group: '内容管理' },
  { code: CHANNEL_PERMISSIONS.CHANNEL_SETTINGS, name: '修改频道资料', group: '频道' },
  { code: CHANNEL_PERMISSIONS.VISIBILITY, name: '修改可见性', group: '频道' },
  { code: CHANNEL_PERMISSIONS.DISSOLVE, name: '解散频道', group: '频道' },
  { code: CHANNEL_PERMISSIONS.MEMBER_INVITE, name: '邀请成员', group: '成员' },
  { code: CHANNEL_PERMISSIONS.MEMBER_REMOVE, name: '移除成员', group: '成员' },
  { code: CHANNEL_PERMISSIONS.MEMBER_MUTE, name: '禁言成员', group: '成员' },
  { code: CHANNEL_PERMISSIONS.MUTE_ALL, name: '全员禁言', group: '成员' },
  { code: CHANNEL_PERMISSIONS.INVITE_CREATE, name: '创建邀请链接', group: '成员' },
  { code: CHANNEL_PERMISSIONS.INVITE_APPROVE, name: '审批加入申请', group: '成员' },
  { code: CHANNEL_PERMISSIONS.ROLE_MANAGE, name: '身份组管理', group: '成员' },
  { code: CHANNEL_PERMISSIONS.LOG_VIEW, name: '查看管理日志', group: '运营' },
  { code: CHANNEL_PERMISSIONS.DATA_VIEW, name: '查看数据中心', group: '运营' },
  { code: CHANNEL_PERMISSIONS.AI_MANAGE, name: '频道助手设置', group: '运营' },
]

export function permissionLabel(code: string): string {
  return PERMISSION_POINTS.find((point) => point.code === code)?.name ?? code
}

/** 频道主判定：ownerId 优先；后端旧响应无 ownerId 时降级用身份组名称。 */
export function isChannelOwner(channel: Channel | null | undefined, userId?: string | null): boolean {
  if (!channel || !userId) return false
  if (channel.ownerId) return channel.ownerId === userId
  return channel.myRoleName === '频道主'
}

/** 系统身份组的默认权限兜底（后端未下发 myPermissions 时使用）。 */
function fallbackPermissionsByRole(roleName?: string | null): string[] {
  switch (roleName) {
    case '频道主':
      return [...ALL_CHANNEL_PERMISSIONS]
    case '超级管理员':
      return ALL_CHANNEL_PERMISSIONS.filter((code) => code !== CHANNEL_PERMISSIONS.DISSOLVE)
    case '子板块管理员':
      return [
        CHANNEL_PERMISSIONS.POST_CREATE,
        CHANNEL_PERMISSIONS.COMMENT_CREATE,
        CHANNEL_PERMISSIONS.POST_PIN,
        CHANNEL_PERMISSIONS.POST_ESSENCE,
        CHANNEL_PERMISSIONS.POST_DELETE,
        CHANNEL_PERMISSIONS.COMMENT_DELETE,
        CHANNEL_PERMISSIONS.CONTENT_MANAGE,
      ]
    case '普通成员':
      return [CHANNEL_PERMISSIONS.POST_CREATE, CHANNEL_PERMISSIONS.COMMENT_CREATE]
    default:
      return []
  }
}

/**
 * 解析当前用户在某频道的权限集合：
 * 1) 频道主始终拥有全部权限；
 * 2) 后端返回 myPermissions 时直接使用（支持自定义身份组）；
 * 3) 否则按身份组名称使用系统默认权限兜底。
 */
export function resolveChannelPermissions(
  channel: Channel | null | undefined,
  userId?: string | null,
): Set<string> {
  if (!channel) return new Set()
  if (isChannelOwner(channel, userId)) {
    return new Set(ALL_CHANNEL_PERMISSIONS)
  }
  if (Array.isArray(channel.myPermissions) && channel.myPermissions.length > 0) {
    return new Set(channel.myPermissions)
  }
  if (!channel.isJoined && !channel.joined) {
    return new Set()
  }
  return new Set(fallbackPermissionsByRole(channel.myRoleName ?? channel.myRole?.name))
}

export function hasChannelPermission(permissions: Set<string>, permission: string): boolean {
  return permissions.has(permission)
}

/** 是否可进入频道管理中心（拥有任一运营/管理权限）。 */
export function canManageChannel(permissions: Set<string>): boolean {
  const manageCodes = [
    CHANNEL_PERMISSIONS.CHANNEL_SETTINGS,
    CHANNEL_PERMISSIONS.CONTENT_MANAGE,
    CHANNEL_PERMISSIONS.MEMBER_REMOVE,
    CHANNEL_PERMISSIONS.MEMBER_MUTE,
    CHANNEL_PERMISSIONS.ROLE_MANAGE,
    CHANNEL_PERMISSIONS.LOG_VIEW,
    CHANNEL_PERMISSIONS.DATA_VIEW,
    CHANNEL_PERMISSIONS.BOARD_MANAGE,
    CHANNEL_PERMISSIONS.AI_MANAGE,
    CHANNEL_PERMISSIONS.DISSOLVE,
  ]
  return manageCodes.some((code) => permissions.has(code))
}

/** 频道摘要是否可管理（侧边栏频道选择器用；自定义身份组依赖后端 myPermissions）。 */
export function canManageChannelSummary(channel: ChannelSummary, userId?: string | null): boolean {
  if (isChannelOwner(channel, userId)) return true
  if (channel.myPermissions?.length) {
    return canManageChannel(new Set(channel.myPermissions))
  }
  return channel.myRoleName === '频道主' || channel.myRoleName === '超级管理员'
}
