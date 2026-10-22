export * from './services/api.js'

// OpsWorkbench /gw/* 挂载 App（§8.7 域目录清退）
export { default as GwApp } from './pages/GwApp.jsx'
// 组件文档页（lead 008 §7）：主壳按统一名字取，各仓一致
export { default as ComponentDocPage } from './pages/ComponentDoc.jsx'
