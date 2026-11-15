<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { APP_NAME, APP_NAME_EN, appSlogan } from '@/constants/app'

const { t } = useI18n()

const highlights = [
  t('auth.layout.tagline1'),
  t('auth.layout.tagline2'),
  t('auth.layout.tagline3'),
]
</script>

<template>
  <div class="auth-layout">
    <section class="auth-layout__brand" :aria-label="t('auth.productIntro')">
      <RouterLink to="/feed" class="auth-layout__logo">
        <svg viewBox="0 0 64 64" aria-hidden="true">
          <rect width="64" height="64" rx="16" fill="#ffffff" opacity="0.16" />
          <path d="M8 44c8-6 15-6 23-1 9 6 16 6 25 0v13H8z" fill="#fff" opacity="0.24" />
          <path d="M14 34c4-8 10-12 17-12 8 0 14 4 19 12-7 4-13 4-18 1-6-4-12-4-18-1z" fill="#fff" />
          <circle cx="46" cy="18" r="6" fill="#f0a06b" />
        </svg>
        <span>
          <strong>{{ APP_NAME }}</strong>
          <small>{{ APP_NAME_EN }}</small>
        </span>
      </RouterLink>

      <div class="auth-layout__pitch">
        <h1>{{ appSlogan() }}</h1>
        <ul>
          <li v-for="item in highlights" :key="item">{{ item }}</li>
        </ul>
      </div>

      <div class="auth-layout__islands" aria-hidden="true">
        <span class="island island--back" />
        <span class="island island--mid" />
        <span class="island island--front" />
      </div>
    </section>

    <section class="auth-layout__panel">
      <slot />
    </section>
  </div>
</template>

<style scoped lang="scss">
.auth-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr);
  min-height: 100vh;
  background: var(--isles-bg);
}

.auth-layout__brand {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: var(--isles-space-10) var(--isles-space-8);
  overflow: hidden;
  color: #fff;
  background:
    radial-gradient(circle at 82% 16%, rgba(240, 160, 107, 0.55) 0 42px, transparent 43px),
    linear-gradient(150deg, #1b7489 0%, #12586b 55%, #0c3f4e 100%);
}

.auth-layout__logo {
  display: inline-flex;
  gap: var(--isles-space-3);
  align-items: center;
  width: fit-content;

  svg {
    width: 44px;
    height: 44px;
  }

  span {
    display: flex;
    flex-direction: column;
    line-height: 1.2;
  }

  strong {
    font-size: var(--isles-font-xl);
    letter-spacing: 0.08em;
  }

  small {
    font-size: 10px;
    letter-spacing: 0.24em;
    opacity: 0.75;
  }
}

.auth-layout__pitch {
  position: relative;
  z-index: 1;
  max-width: 460px;

  h1 {
    font-size: var(--isles-font-display);
    font-weight: 600;
    line-height: 1.4;
    letter-spacing: 0.02em;
  }

  ul {
    display: flex;
    flex-direction: column;
    gap: var(--isles-space-2);
    margin-top: var(--isles-space-6);
  }

  li {
    position: relative;
    padding-left: var(--isles-space-4);
    color: rgba(255, 255, 255, 0.82);
    font-size: var(--isles-font-sm);

    &::before {
      content: '';
      position: absolute;
      top: 9px;
      left: 0;
      width: 6px;
      height: 6px;
      background: #f0a06b;
      border-radius: 50%;
    }
  }
}

.auth-layout__islands {
  position: absolute;
  right: -40px;
  bottom: 40px;
  left: -40px;
  height: 160px;
  pointer-events: none;
}

.island {
  position: absolute;
  bottom: 0;
  background: rgba(255, 255, 255, 0.14);
  border-radius: 50% 50% 30% 30%;
}

.island--back {
  left: 6%;
  width: 180px;
  height: 60px;
  opacity: 0.6;
}

.island--mid {
  left: 32%;
  width: 150px;
  height: 44px;
  opacity: 0.8;
}

.island--front {
  right: 4%;
  width: 240px;
  height: 84px;
}

.auth-layout__panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--isles-space-8) var(--isles-space-6);
}

@media (max-width: 1023px) {
  .auth-layout {
    grid-template-columns: 1fr;
  }

  .auth-layout__brand {
    display: none;
  }
}
</style>
