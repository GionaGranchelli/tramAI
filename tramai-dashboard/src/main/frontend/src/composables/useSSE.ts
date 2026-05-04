import { ref, onUnmounted } from 'vue'

/**
 * EventSource wrapper with named event support and automatic reconnection.
 *
 * Connects to a server-sent events endpoint and exposes reactive refs
 * for each named event type. Reconnects on error after a brief delay.
 */
export function useSSE(url: string) {
  const data = ref<any>(null)
  const lastEvent = ref<string | null>(null)
  const connected = ref(false)
  const error = ref<string | null>(null)

  let eventSource: EventSource | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null

  function connect() {
    if (eventSource) {
      eventSource.close()
    }

    const fullUrl = `${window.__TRAMAI__?.apiBaseUrl ?? ''}${url}`
    eventSource = new EventSource(fullUrl)

    eventSource.onopen = () => {
      connected.value = true
      error.value = null
    }

    // Default handler for unnamed events
    eventSource.onmessage = (event) => {
      lastEvent.value = null
      try {
        data.value = JSON.parse(event.data)
      } catch {
        data.value = event.data
      }
    }

    // Listen for common named events from the backend
    const namedEvents = [
      'workerOnline', 'workerOffline', 'workerList',
      'scheduleTick', 'scheduleMisfire',
    ]
    namedEvents.forEach((eventName) => {
      eventSource!.addEventListener(eventName, (event: MessageEvent) => {
        lastEvent.value = eventName
        try {
          data.value = JSON.parse(event.data)
        } catch {
          data.value = event.data
        }
      })
    })

    eventSource.onerror = () => {
      connected.value = false
      error.value = 'SSE connection error'
      eventSource?.close()
      eventSource = null
      // Auto-reconnect after 3 seconds
      reconnectTimer = setTimeout(connect, 3000)
    }
  }

  connect()

  onUnmounted(() => {
    if (reconnectTimer) clearTimeout(reconnectTimer)
    eventSource?.close()
    eventSource = null
  })

  return { data, lastEvent, connected, error }
}
