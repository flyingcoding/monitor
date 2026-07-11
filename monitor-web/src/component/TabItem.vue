<script setup>
defineProps({
  name: String,
  active: Boolean,
  icon: Object
})

defineEmits(['click'])
</script>

<template>
  <button
    type="button"
    class="nav-item"
    :class="{ active }"
    :aria-current="active ? 'page' : undefined"
    @click="$emit('click')"
  >
    <el-icon class="nav-item-icon" aria-hidden="true">
      <component :is="icon" />
    </el-icon>
    <span>{{ name }}</span>
  </button>
</template>

<style scoped>
.nav-item {
  position: relative;
  width: 100%;
  min-height: 48px;
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 0 15px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--app-shell-text);
  cursor: pointer;
  font-size: 15px;
  font-weight: 600;
  text-align: left;
  transition:
    color var(--app-transition),
    background-color var(--app-transition),
    transform var(--app-transition);
}

.nav-item:hover {
  background: var(--app-shell-hover);
  color: #ffffff;
}

.nav-item:active {
  transform: translateY(1px);
}

.nav-item.active {
  background: var(--app-shell-active);
  color: #ffffff;
  box-shadow: 0 8px 20px rgba(11, 107, 238, 0.24);
}

.nav-item:focus-visible {
  outline-color: #ffffff;
  outline-offset: 3px;
}

.nav-item-icon {
  flex: 0 0 auto;
  font-size: 20px;
}
</style>
