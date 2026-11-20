export const tokenKey = "probeloom-token"

export const configuredPlatformServiceUrl = import.meta.env.VITE_PLATFORM_SERVICE_URL
export const platformServiceDisplay = configuredPlatformServiceUrl || "当前站点"

export const asNumber = (value: string) => Number(value)

export const formatTime = (value?: string) => value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "-"

export const statusTone = (status: string) => status === "PASSED" || status === "SUCCESS" ? "default" : status === "RUNNING" || status === "QUEUED" || status === "WARN" ? "secondary" : status === "SKIPPED" || status === "NOT_SELECTED" ? "outline" : status === "PENDING" ? "secondary" : "destructive"

const statusLabels: Record<string, string> = { PASSED: "成功", SUCCESS: "成功", FAILED: "失败", INTERRUPTED: "已中断", RUNNING: "执行中", QUEUED: "排队中", WARN: "警告", SKIPPED: "已跳过", PENDING: "待执行", NOT_SELECTED: "未勾选" }

// 获取状态提示信息
export const statusHint = (status: string) => {
  switch (status) {
    case "SKIPPED":
      return "因前面步骤失败而正常跳过"
    case "PENDING":
      return "等待执行"
    case "NOT_SELECTED":
      return "本次未勾选"
    default:
      return null
  }
}

export const statusLabel = (status: string) => statusLabels[status] || status

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const token = localStorage.getItem(tokenKey)
  const headers = new Headers(init?.headers)
  if (!(init?.body instanceof FormData) && !headers.has("Content-Type")) headers.set("Content-Type", "application/json")
  if (token) headers.set("X-Platform-Token", token)
  const response = await fetch(path, { ...init, headers })
  const payload = await response.json()
  if (!response.ok || !payload.success) {
    if (response.status === 401) localStorage.removeItem(tokenKey)
    throw new Error(payload.message || "请求失败")
  }
  return payload.data as T
}
