<template>
  <div class="page">
    <h1>Audit Log</h1>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="loading">Loading audit entries...</div>
    <ul v-if="entries.length" class="list">
      <li v-for="entry in entries" :key="entry.id" class="list-item">
        <span class="timestamp">{{ formatTime(entry.timestamp) }}</span>
        <span class="action">{{ entry.action }}</span>
        <span class="actor">{{ entry.actor }}</span>
      </li>
    </ul>
    <p v-if="!loading && !error && !entries.length">No audit entries.</p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { apiGet } from '@/composables/useApi'

interface AuditEntry {
  id: string
  timestamp: string
  action: string
  actor: string
}

const entries = ref<AuditEntry[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

function formatTime(ts: string): string {
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return ts
  }
}

onMounted(async () => {
  const res = await apiGet<AuditEntry[]>('/audit')
  if (res.error) {
    error.value = res.error
  } else {
    entries.value = res.data ?? []
  }
  loading.value = false
})
</script>

<style scoped>
.timestamp {
  color: #888;
  font-size: 0.85rem;
  margin-right: 12px;
}
.action {
  font-weight: 500;
  margin-right: 12px;
}
.actor {
  color: #555;
}
</style>
