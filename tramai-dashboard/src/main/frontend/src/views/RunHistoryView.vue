<template>
  <div class="page">
    <h1>Run History: {{ name }}</h1>
    <router-link to="/" class="back-link">&larr; Back to workflows</router-link>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="loading">Loading runs...</div>
    <ul v-if="runs.length" class="list">
      <li v-for="run in runs" :key="run.id" class="list-item">
        <router-link :to="`/workflows/${encodeURIComponent(name)}/runs/${run.id}`">
          Run #{{ run.id }} — {{ run.status }}
        </router-link>
      </li>
    </ul>
    <p v-if="!loading && !error && !runs.length">No runs found for this workflow.</p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { apiGet } from '@/composables/useApi'

interface Run {
  id: string
  status: string
}

const route = useRoute()
const name = route.params.name as string

const runs = ref<Run[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  const res = await apiGet<Run[]>(`/workflows/${encodeURIComponent(name)}/runs`)
  if (res.error) {
    error.value = res.error
  } else {
    runs.value = res.data ?? []
  }
  loading.value = false
})
</script>
