<template>
  <div class="page">
    <h1>Workers</h1>
    <div class="connection-status">
      SSE: <span :class="sseConnected ? 'connected' : 'disconnected'">{{ sseConnected ? '● Live' : '○ Disconnected' }}</span>
    </div>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="loading">Loading workers...</div>
    <ul v-if="workers.length" class="list">
      <li v-for="w in workers" :key="w.workerId" class="list-item">
        <span class="worker-name">{{ w.workerId }}</span>
        <span :class="['status-badge', w.status]">{{ w.status }}</span>
      </li>
    </ul>
    <p v-if="!loading && !error && !workers.length">No workers registered.</p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { apiGet } from '@/composables/useApi'
import { useSSE } from '@/composables/useSSE'

interface Worker {
  workerId: string
  status: string
}

const workers = ref<Worker[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

// Load initial worker list
onMounted(async () => {
  const res = await apiGet<Worker[]>('/workers')
  if (res.error) {
    error.value = res.error
  } else {
    workers.value = res.data ?? []
  }
  loading.value = false
})

// SSE for live worker updates
const { data: sseData, lastEvent, connected: sseConnected } = useSSE('/workers/events')

watch(sseData, (event) => {
  if (!event) return
  if (lastEvent.value === 'workerList') {
    // Full list update
    if (Array.isArray(event)) {
      workers.value = event
    }
  } else if (lastEvent.value === 'workerOnline') {
    const idx = workers.value.findIndex((w) => w.workerId === event.workerId)
    if (idx >= 0) {
      workers.value[idx] = event
    } else {
      workers.value.push(event)
    }
  } else if (lastEvent.value === 'workerOffline') {
    workers.value = workers.value.filter((w) => w.workerId !== event.workerId)
  }
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
.status-badge.online { background: #c8e6c9; color: #2e7d32; }
.status-badge.stale { background: #fff3e0; color: #e65100; }
.status-badge.offline { background: #ffcdd2; color: #c62828; }
.connection-status {
  font-size: 0.85rem;
  margin-bottom: 8px;
}
.connected { color: #2e7d32; }
.disconnected { color: #c62828; }
</style>
