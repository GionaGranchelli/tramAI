<template>
  <div class="page">
    <h1>Schedules</h1>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="loading">Loading schedules...</div>
    <ul v-if="schedules.length" class="list">
      <li v-for="s in schedules" :key="s.scheduleId" class="list-item">
        <div>
          <strong>{{ s.workflowName }}</strong>
          <span class="cron">{{ s.cronExpression }}</span>
        </div>
        <span :class="['status-badge', s.enabled ? 'running' : 'idle']">
          {{ s.enabled ? 'Enabled' : 'Disabled' }}
        </span>
      </li>
    </ul>
    <p v-if="!loading && !error && !schedules.length">No schedules configured.</p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { apiGet } from '@/composables/useApi'

interface Schedule {
  scheduleId: string
  workflowName: string
  cronExpression: string
  enabled: boolean
}

const schedules = ref<Schedule[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  const res = await apiGet<Schedule[]>('/schedules')
  if (res.error) {
    error.value = res.error
  } else {
    schedules.value = res.data ?? []
  }
  loading.value = false
})
</script>

<style scoped>
.cron {
  color: #888;
  margin-left: 8px;
  font-family: monospace;
  font-size: 0.85rem;
}
.status-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 0.8rem;
  background: #e0e0e0;
}
.status-badge.running { background: #c8e6c9; color: #2e7d32; }
.status-badge.idle { background: #fff3e0; color: #e65100; }
</style>
