// Lightweight loader for AMap JS API v2.0
// Usage: await loadAmap(import.meta.env.VITE_AMAP_KEY)

let loading: Promise<any> | null = null

export function loadAmap(key?: string): Promise<any> {
  if ((window as any).AMap) return Promise.resolve((window as any).AMap)
  if (loading) return loading
  // Read key from multiple sources to ease dev:
  // 1) explicit param
  // 2) Vite env (VITE_AMAP_KEY)
  // 3) URL query ?amapKey=xxx
  // 4) localStorage.AMAP_KEY
  // 5) window.AMAP_KEY (manual injection from console)
  const fromQuery = typeof window !== 'undefined' ? new URLSearchParams(window.location.search).get('amapKey') : null
  const fromLocal = typeof window !== 'undefined' ? window.localStorage?.getItem('AMAP_KEY') : null
  const fromGlobal = (window as any)?.AMAP_KEY
  const amapKey = key || (import.meta as any).env.VITE_AMAP_KEY || fromQuery || fromLocal || fromGlobal
  if (!amapKey) {
    return Promise.reject(new Error('缺少高德地图 Key（VITE_AMAP_KEY）'))
  }
  loading = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.id = 'amap-sdk'
    script.async = true
    script.defer = true
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(amapKey)}&plugin=AMap.Scale,AMap.ToolBar,AMap.ControlBar`
    script.onload = () => resolve((window as any).AMap)
    script.onerror = () => reject(new Error('高德地图脚本加载失败'))
    document.head.appendChild(script)
  })
  return loading
}
