import { describe, expect, it } from 'vitest'

/**
 * 抽屉内边距守卫。
 *
 * <p>之前 `AppLayout.vue` 里有一段**无条件**的 `.el-drawer__body { padding: 0 }`（外加
 * `element.scss` 把 `--el-drawer-padding-primary` 设成 0），导致处罚、AI 用户人设、
 * 运行日志、配置历史四个抽屉的文字全都贴边。
 *
 * <p>现在全局恢复 20px，满宽抽屉必须挂在自己的 class 下。这里扫所有 `.vue` 源码，
 * 防止有人再写一条全局清零。
 *
 * <p>注：SCSS/CSS 的 `?raw` 导入在 Vitest 里被 CSS 处理链吞掉（返回空串），
 * 所以守卫只覆盖模板/组件源码，样式声明本身靠 review。
 */
const SOURCES = import.meta.glob(['../**/*.vue', '!../test/**'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

describe('抽屉内边距', () => {
  it('没有全局把 .el-drawer__body 内边距清零（必须挂在自己的 class 下）', () => {
    const offenders: string[] = []
    for (const [file, source] of Object.entries(SOURCES)) {
      for (const match of source.matchAll(/(^|\n)([^\n{}]*)\.el-drawer__body\s*\{([^}]*)\}/g)) {
        const [, , selectorPrefix, body] = match
        if (!/padding:\s*0/.test(body)) continue
        // 允许 .app-nav-drawer .el-drawer__body，禁止裸 .el-drawer__body
        if (!selectorPrefix.trim()) offenders.push(`${file}: .el-drawer__body { padding: 0 }`)
      }
    }
    expect(offenders, `全局清零抽屉内边距：\n${offenders.join('\n')}`).toEqual([])
    expect(Object.keys(SOURCES).length).toBeGreaterThan(40)
  })

  it('移动端导航抽屉自己声明满宽', () => {
    const layout = SOURCES['../layouts/AppLayout.vue']
    expect(layout).toContain('class="app-nav-drawer"')
    expect(layout).toContain('.app-nav-drawer .el-drawer__body')
  })
})
