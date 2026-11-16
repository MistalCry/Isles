// ESLint flat config（前端）：推荐集为主，历史告警不强制清零，但必须能跑通、不能阻断构建。
import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'
import prettier from 'eslint-config-prettier'

export default [
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'coverage/**',
      'src/components.d.ts',
      // 由 tools/i18n 生成，格式由生成器决定
      'src/i18n/*.ts',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue'],
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
  },
  {
    rules: {
      // 组件名多为单词（PostCard/PostComposer 等），与路由约定一致
      'vue/multi-word-component-names': 'off',
      // 内容渲染走 sanitize，v-html 是刻意为之
      'vue/no-v-html': 'off',
      // 属性换行/自闭合属于格式问题，交给 Prettier，不在 lint 里报错
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      'vue/html-indent': 'off',
      'vue/attributes-order': 'warn',
      'vue/require-default-prop': 'off',
      'vue/no-v-model-argument': 'off',
      // 历史代码里存在直接改 prop 的写法（ChannelSettingsDialog），先降级为告警，
      // 由后续专项修；新代码不要学。
      'vue/no-mutating-props': 'warn',
      'vue/first-attribute-linebreak': 'off',
      // 未使用变量与 any 降级为告警：历史代码较多，先保证能跑通
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' },
      ],
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-empty-object-type': 'warn',
      'no-empty': ['warn', { allowEmptyCatch: true }],
      // TS 负责未定义检查，避免与 DOM 全局冲突
      'no-undef': 'off',
    },
  },
  {
    files: ['**/*.spec.ts', 'src/test/**/*.ts'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-expressions': 'off',
    },
  },
  prettier,
]
