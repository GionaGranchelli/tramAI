<template>
  <div class="dashboard-layout">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <h2>Tramai</h2>
      </div>
      <nav class="sidebar-nav">
        <router-link to="/">Workflows</router-link>
        <router-link to="/workers">Workers</router-link>
        <router-link to="/schedules">Schedules</router-link>
        <router-link to="/audit">Audit Log</router-link>
        <router-link to="/settings">Settings</router-link>
      </nav>
    </aside>
    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #f5f5f5;
  color: #333;
}

.dashboard-layout {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  width: 220px;
  background: #1a1a2e;
  color: #eee;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.sidebar-brand {
  padding: 20px 16px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
}

.sidebar-brand h2 {
  font-size: 1.3rem;
  font-weight: 600;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  padding: 8px 0;
}

.sidebar-nav a {
  color: #ccc;
  text-decoration: none;
  padding: 10px 16px;
  font-size: 0.95rem;
  transition: background 0.15s, color 0.15s;
}

.sidebar-nav a:hover,
.sidebar-nav a.router-link-active {
  background: rgba(255,255,255,0.08);
  color: #fff;
}

.main-content {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
}
</style>
