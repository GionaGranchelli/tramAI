<template>
  <div class="page">
    <h1>Workers</h1>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="loading">Loading workers...</div>
    <ul v-if="workers.length" class="list">
      <li v-for="w in workers" :key="w.id" class="list-item">
        <span class="worker-name">{{ w.id }}</span>
        <span :class="['status-badge', w.status]">{{ w.status }}</span>
      </li>
    </ul>
    <p v-if="!loading && !error && !workers.length">No workers registered.</p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { apiGet } from '@/composables/useApi'

interface Worker {
  id: string
  status: string
}

const workers = ref<Worker[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  const res = await apiGet<Worker[]>('/workers')
  if (res.error) {
    error.value = res.error
  } else {
    workers.value = res.data ?? []
  }
  loading.value = false
})
</script>

<style scoped>
.worker-name {
  font-weight: 500;
  margin-right: 12px;
}
.status-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 0.8rem;
  background: #e0e0e0;
}
.status-badge.running { background: #c8e6c9; color: #2e7d32; }
.status-badge.idle { background: #e0e0e0; color: #555; }
.status-badge.error { background: #ffcdd2; color: #c62828; }
</style>
