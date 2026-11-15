<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import AppLayout from '@/layouts/AppLayout.vue'
import { useSiteStore } from '@/stores/site'

const route = useRoute()
const site = useSiteStore()
const { locale } = useI18n()
const isAuthLayout = computed(() => route.meta.authLayout === true)
/** Element Plus 组件内部文案（分页、日期选择等）跟随当前语言 */
const elementLocale = computed(() => (locale.value === 'en-US' ? en : zhCn))

onMounted(() => {
  void site.load()
})
</script>

<template>
  <el-config-provider :locale="elementLocale">
    <RouterView v-if="isAuthLayout" />
    <AppLayout v-else />
  </el-config-provider>
</template>
