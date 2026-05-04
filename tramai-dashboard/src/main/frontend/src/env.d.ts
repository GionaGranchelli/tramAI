/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface TramaiRuntime {
  apiBaseUrl: string
  features: {
    auditLog: boolean
    workerManagement: boolean
    scheduleManagement: boolean
  }
  auth: {
    required: boolean
    provider: string
  }
}

interface Window {
  __TRAMAI__: TramaiRuntime
}
