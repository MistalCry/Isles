<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    description?: string
    eta?: string
    features?: string[]
  }>(),
  {
    description: '',
    eta: '',
    features: () => [],
  },
)
</script>

<template>
  <section class="coming-soon">
    <div class="coming-soon__visual" aria-hidden="true">
      <div class="coming-soon__sun" />
      <div class="coming-soon__island coming-soon__island--back" />
      <div class="coming-soon__island coming-soon__island--front" />
      <div class="coming-soon__sea" />
    </div>
    <el-tag v-if="eta" type="info" effect="plain" round class="coming-soon__eta">{{ eta }}</el-tag>
    <h1 class="coming-soon__title">{{ title }}</h1>
    <p v-if="description" class="coming-soon__description">{{ description }}</p>
    <ul v-if="features.length > 0" class="coming-soon__features">
      <li v-for="feature in features" :key="feature">{{ feature }}</li>
    </ul>
    <div v-if="$slots.default" class="coming-soon__extra">
      <slot />
    </div>
  </section>
</template>

<style scoped lang="scss">
.coming-soon {
  display: flex;
  flex-direction: column;
  align-items: center;
  max-width: 620px;
  margin: var(--isles-space-8) auto;
  padding: var(--isles-space-8) var(--isles-space-6);
  text-align: center;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
  box-shadow: var(--isles-shadow-sm);
}

.coming-soon__visual {
  position: relative;
  width: 180px;
  height: 120px;
  margin-bottom: var(--isles-space-5);
  overflow: hidden;
  border-radius: var(--isles-radius-lg);
  background: linear-gradient(180deg, #e8f3f6 0%, #f7fbfc 100%);
}

.coming-soon__sun {
  position: absolute;
  top: 18px;
  right: 30px;
  width: 26px;
  height: 26px;
  background: #f3b57f;
  border-radius: 50%;
}

.coming-soon__island {
  position: absolute;
  bottom: 30px;
  background: linear-gradient(160deg, #b9d7de, #7fb0bb);
  border-radius: 50% 50% 42% 42%;
}

.coming-soon__island--back {
  left: 28px;
  width: 70px;
  height: 34px;
  opacity: 0.65;
}

.coming-soon__island--front {
  right: 26px;
  width: 88px;
  height: 46px;
}

.coming-soon__sea {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 100%;
  height: 34px;
  background: repeating-linear-gradient(
    0deg,
    rgba(20, 101, 122, 0.16) 0 2px,
    transparent 2px 8px
  );
}

.coming-soon__eta {
  margin-bottom: var(--isles-space-3);
}

.coming-soon__title {
  font-size: var(--isles-font-xl);
  font-weight: 600;
  line-height: var(--isles-line-tight);
}

.coming-soon__description {
  max-width: 440px;
  margin-top: var(--isles-space-2);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
}

.coming-soon__features {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--isles-space-2);
  margin-top: var(--isles-space-5);

  li {
    padding: 4px 12px;
    color: var(--isles-text-secondary);
    font-size: var(--isles-font-xs);
    background: var(--isles-surface-muted);
    border: 1px solid var(--isles-border);
    border-radius: var(--isles-radius-pill);
  }
}

.coming-soon__extra {
  margin-top: var(--isles-space-5);
}
</style>
