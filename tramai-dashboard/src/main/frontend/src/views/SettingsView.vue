<template>
  <div class="page">
    <h1>Settings</h1>
    <div class="settings-section">
      <h2>API Keys</h2>
      <p class="placeholder">API key management will be available in a future update.</p>
    </div>
    <div class="settings-section">
      <h2>Feature Flags</h2>
      <pre class="config-preview">{{ features }}</pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const features = computed(() =>
  JSON.stringify(window.__TRAMAI__?.features ?? {}, null, 2)
)
</script>

<style scoped>
.settings-section {
  margin-bottom: 24px;
}
.settings-section h2 {
  font-size: 1.1rem;
  margin-bottom: 8px;
}
.placeholder {
  color: #888;
  font-style: italic;
}
.config-preview {
  background: #f0f0f0;
  padding: 12px;
  border-radius: 4px;
  font-size: 0.9rem;
}
</style>
