import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      name: 'workflows',
      component: () => import('@/views/WorkflowListView.vue'),
    },
    {
      path: '/workflows/:name/runs',
      name: 'run-history',
      component: () => import('@/views/RunHistoryView.vue'),
    },
    {
      path: '/workflows/:name/runs/:id',
      name: 'run-detail',
      component: () => import('@/views/RunDetailView.vue'),
    },
    {
      path: '/workers',
      name: 'workers',
      component: () => import('@/views/WorkerListView.vue'),
    },
    {
      path: '/schedules',
      name: 'schedules',
      component: () => import('@/views/ScheduleListView.vue'),
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
    },
    {
      path: '/audit',
      name: 'audit',
      component: () => import('@/views/AuditLogView.vue'),
    },
  ],
})

export default router
