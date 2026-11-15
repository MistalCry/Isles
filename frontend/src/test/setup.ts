import { config } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import i18n from '@/i18n'

// 组件里会用到 useRouter / useRoute 与 Pinia store，
// 这里给一个空路由（不真正导航）与全新的 pinia 实例，避免跨用例污染。
const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', name: 'root', component: { template: '<div />' } }],
})

/**
 * 组件测试的全局桩：
 *
 * - 注入 i18n，让组件里的 `useI18n()` / `t()` 正常工作；
 * - Element Plus 组件在业务代码里由 unplugin-vue-components 自动引入，
 *   测试环境没有这层转换，因此统一用轻量桩替代（只保留插槽与必要事件）；
 * - `RouterLink` 用 `<a>` 桩，避免为组件测试引入完整路由。
 */
// 测试基线固定中文：happy-dom 的 navigator.language 是 en-US，
// 不固定的话断言会随环境漂移（需要英文的用例自己调 setLocale）。
i18n.global.locale.value = 'zh-CN'

config.global.plugins = [i18n, createPinia(), router]

// v-loading 来自 Element Plus 指令，测试里给个空实现即可
config.global.directives = { loading: {} }

config.global.stubs = {
  RouterLink: {
    props: ['to'],
    template: '<a class="router-link-stub"><slot /></a>',
  },
  'el-icon': { template: '<i class="el-icon-stub"><slot /></i>' },
  'el-tag': { template: '<span class="el-tag-stub"><slot /></span>' },
  'el-button': {
    props: ['disabled', 'loading'],
    emits: ['click'],
    template:
      '<button class="el-button-stub" :disabled="disabled" @click="$emit(\'click\', $event)"><slot /></button>',
  },
  'el-input': {
    props: ['modelValue', 'placeholder'],
    emits: ['update:modelValue'],
    template:
      '<input class="el-input-stub" :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  'el-image': {
    props: ['src', 'alt'],
    template: '<img class="el-image-stub" :src="src" :alt="alt" />',
  },
  'el-alert': {
    props: ['title', 'description', 'type'],
    template: '<div class="el-alert-stub">{{ title }}{{ description }}</div>',
  },
  'el-switch': {
    props: ['modelValue', 'activeText'],
    template: '<span class="el-switch-stub">{{ activeText }}</span>',
  },
  'el-slider': { template: '<div class="el-slider-stub"></div>' },
  'el-popover': {
    props: ['visible', 'width', 'trigger', 'placement', 'disabled'],
    emits: ['update:visible'],
    template: `
      <div class="el-popover-stub">
        <div class="el-popover-stub__reference" @click="$emit('update:visible', true)">
          <slot name="reference" />
        </div>
        <div class="el-popover-stub__body"><slot /></div>
      </div>
    `,
  },
}
