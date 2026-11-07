/**
 * 动态加载 demo 的 lucide 图标脚本（正式 App 只在需要的页面用）。
 */
export async function ensureLucide(): Promise<void> {
  if ((window as any).lucide) return
  await new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = '/lucide-1.17.0.js'
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('icon script failed'))
    document.head.appendChild(script)
  })
}

export function renderIcons(width = 16): void {
  ;(window as any).lucide?.createIcons({ attrs: { width, height: width } })
}
