<template>
  <div class="page">
    <h1>Run Detail</h1>
    <p>
      <router-link :to="`/workflows/${encodeURIComponent(name)}/runs`" class="back-link">
        &larr; Back to runs
      </router-link>
    </p>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="loading">Loading run details...</div>
    <div v-if="run" class="detail">
      <h2>Run #{{ run.workflowId }}</h2>
      <p>Status: <strong>{{ run.status }}</strong></p>
      <p v-if="run.currentStep">Current step: {{ run.currentStep }}</p>
      <p v-if="run.error" class="error-message">Error: {{ run.error }}</p>
      <div v-if="run.history?.length">
        <h3>History</h3>
        <ul>
          <li v-for="event in run.history" :key="event.sequence">
            <span class="event-name">{{ event.name }}</span>
            <span v-if="event.stepName" class="step-name">({{ event.stepName }})</span>
            <span class="event-time">{{ formatTime(event.timestamp) }}</span>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { apiGet } from '@/composables/useApi'

interface RunEvent {
  sequence: number
  name: string
  stepName: string | null
  timestamp: string
}

interface RunDetail {
  workflowId: string
  status: string
  definitionVersion: string
  currentStep: string | null
  history: RunEvent[]
  result: unknown
  error: string | null
}

const route = useRoute()
const name = route.params.name as string
const id = route.params.id as string

const run = ref<RunDetail | null>(null)
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
  const res = await apiGet<RunDetail>(
    `/workflows/${encodeURIComponent(name)}/runs/${encodeURIComponent(id)}`
  )
  if (res.error) {
    error.value = res.error
  } else {
    run.value = res.data
  }
  loading.value = false
})
</script>

<style scoped>
.error-message {
  color: #c62828;
  background: #ffcdd2;
  padding: 8px 12px;
  border-radius: 4px;
}
.event-name {
  font-weight: 500;
  margin-right: 8px;
}
.step-name {
  color: #666;
  margin-right: 8px;
}
.event-time {
  color: #888;
  font-size: 0.85rem;
}
</style>
