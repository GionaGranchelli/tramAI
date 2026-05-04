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
        <div>
          <span class="worker-name">{{ w.workerId }}</span>
          <span class="worker-host">{{ w.host }}</span>
        </div>
        <div class="worker-meta">
          <span :class="['status-badge', w.status]">{{ w.status }}</span>
          <span class="heartbeat">{{ formatHeartbeat(w.lastHeartbeat) }}</span>
        </div>
      </li>
    </ul>
    <p v-if="!loading && !error && !workers.length">No workers registered.</p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { apiGet } from '@/composables/useApi'
import { useSSE } from '@/composables/useSSE'

interface Worker {
  workerId: string
  status: string
  host: string
  lastHeartbeat: string
}

const workers = ref<Worker[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null

async function fetchWorkers() {
  const res = await apiGet<Worker[]>('/workers')
  if (res.error) {
    error.value = res.error
  } else {
    workers.value = res.data ?? []
  }
}

function formatHeartbeat(iso: string): string {
  try {
    const ms = Date.now() - new Date(iso).getTime()
    const sec = Math.floor(ms / 1000)
    if (sec < 15) return 'just now'
    if (sec < 60) return `${sec}s ago`
    if (sec < 3600) return `${Math.floor(sec / 60)}m ago`
    return `${Math.floor(sec / 3600)}h ago`
  } catch {
    return iso
  }
}

onMounted(async () => {
  await fetchWorkers()
  loading.value = false
  // Poll every 15s for status freshness (heartbeat aging)
  pollTimer = setInterval(fetchWorkers, 15_000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
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
  margin-right: 8px;
}
.worker-host {
  color: #888;
  font-size: 0.85rem;
}
.worker-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
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
.heartbeat {
  color: #aaa;
  font-size: 0.78rem;
}
.connection-status {
  font-size: 0.85rem;
  margin-bottom: 8px;
}
.connected { color: #2e7d32; }
.disconnected { color: #c62828; }
</style>
