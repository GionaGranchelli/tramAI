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
      <h2>Run #{{ run.id }}</h2>
      <p>Status: <strong>{{ run.status }}</strong></p>
      <div v-if="run.steps?.length">
        <h3>Steps</h3>
        <ul>
          <li v-for="step in run.steps" :key="step.name">
            {{ step.name }} — {{ step.status }}
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

interface Step {
  name: string
  status: string
}

interface Run {
  id: string
  status: string
  steps?: Step[]
}

const route = useRoute()
const name = route.params.name as string
const id = route.params.id as string

const run = ref<Run | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  const res = await apiGet<Run>(
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
