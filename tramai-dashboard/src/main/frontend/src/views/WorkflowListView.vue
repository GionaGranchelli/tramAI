<template>
  <div class="page">
    <h1>Workflows</h1>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="loading">Loading workflows...</div>
    <ul v-if="workflows.length" class="list">
      <li v-for="wf in workflows" :key="wf.name" class="list-item">
        <router-link :to="`/workflows/${encodeURIComponent(wf.name)}/runs`">
          {{ wf.name }}
        </router-link>
      </li>
    </ul>
    <p v-if="!loading && !error && !workflows.length">No workflows found.</p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { apiGet } from '@/composables/useApi'

interface Workflow {
  name: string
}

const workflows = ref<Workflow[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  const res = await apiGet<Workflow[]>('/workflows')
  if (res.error) {
    error.value = res.error
  } else {
    workflows.value = res.data ?? []
  }
  loading.value = false
})
</script>
