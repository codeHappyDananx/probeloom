import { useCallback, useEffect, useState } from "react"
import { zodResolver } from "@hookform/resolvers/zod"
import { FlaskConical } from "lucide-react"
import { Controller, useForm } from "react-hook-form"
import { toast, Toaster } from "sonner"
import { z } from "zod"
import { AppShell } from "@/app/AppShell"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Field, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { CaseExecutionWorkspace } from "@/features/cases/CaseExecutionWorkspace"
import { CaseRecycleBinPage } from "@/features/cases/CaseRecycleBinPage"
import { GroupManagementPage } from "@/features/cases/GroupManagementPage"
import { ConfigPage } from "@/features/config/ConfigPage"
import { ExecutionsPage } from "@/features/executions/ExecutionsPage"
import { StatisticsPage } from "@/features/statistics/StatisticsPage"
import { SchedulesPage } from "@/features/schedules/SchedulesPage"
import { api, tokenKey } from "@/lib/platform-api"
import type { Bootstrap, TestCase, View } from "@/types/platform"
import { TooltipProvider } from "@/components/ui/tooltip"

function Login({ onLogin }: { onLogin: () => void }) {
  const form = useForm({ resolver: zodResolver(z.object({ username: z.string().trim().min(1, "请输入用户名"), password: z.string().min(1, "请输入密码") })), defaultValues: { username: "admin", password: "" } })
  const submit = () => void form.handleSubmit(async (value) => {
    try {
      const result = await api<{ token: string }>("/api/auth/login", { method: "POST", body: JSON.stringify(value) })
      localStorage.setItem(tokenKey, result.token)
      onLogin()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "登录失败")
    }
  })()
  return <main className="grid min-h-[100dvh] place-items-center bg-muted p-4"><Card className="w-full max-w-sm"><CardHeader><div className="mb-2 flex size-10 items-center justify-center rounded-md bg-primary text-primary-foreground"><FlaskConical size={20} /></div><CardTitle>ProbeLoom</CardTitle><CardDescription>本地管理员登录</CardDescription></CardHeader><CardContent><form id="login-form" onSubmit={(event) => { event.preventDefault(); submit() }}><FieldGroup><Controller name="username" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="login-username">用户名</FieldLabel><Input {...field} id="login-username" autoComplete="username" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="password" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="login-password">密码</FieldLabel><Input {...field} id="login-password" type="password" autoComplete="current-password" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Button type="submit" form="login-form" disabled={form.formState.isSubmitting}>{form.formState.isSubmitting ? "登录中" : "登录"}</Button></FieldGroup></form></CardContent></Card></main>
}

function InitializeAdmin({ onLogin, refreshStatus }: { onLogin: () => void; refreshStatus: () => Promise<void> }) {
  const form = useForm({
    resolver: zodResolver(z.object({ password: z.string().min(1, "请设置密码"), confirmPassword: z.string().min(1, "请再次输入密码") }).refine((value) => value.password === value.confirmPassword, { message: "两次输入的密码不一致", path: ["confirmPassword"] })),
    defaultValues: { password: "", confirmPassword: "" },
  })
  const submit = () => void form.handleSubmit(async (value) => {
    try {
      const result = await api<{ token: string }>("/api/auth/initialize", { method: "POST", body: JSON.stringify({ password: value.password }) })
      localStorage.setItem(tokenKey, result.token)
      onLogin()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设置失败")
      await refreshStatus()
    }
  })()
  return <main className="grid min-h-[100dvh] place-items-center bg-muted p-4"><Card className="w-full max-w-sm"><CardHeader><div className="mb-2 flex size-10 items-center justify-center rounded-md bg-primary text-primary-foreground"><FlaskConical size={20} /></div><CardTitle>ProbeLoom</CardTitle><CardDescription>首次使用，请设置管理员密码</CardDescription></CardHeader><CardContent><form id="initialize-form" onSubmit={(event) => { event.preventDefault(); submit() }}><FieldGroup><Field><FieldLabel htmlFor="initialize-username">用户名</FieldLabel><Input id="initialize-username" value="admin" readOnly autoComplete="username" /></Field><Controller name="password" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="initialize-password">设置密码</FieldLabel><Input {...field} id="initialize-password" type="password" autoComplete="new-password" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="confirmPassword" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="initialize-confirm-password">确认密码</FieldLabel><Input {...field} id="initialize-confirm-password" type="password" autoComplete="new-password" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Button type="submit" form="initialize-form" disabled={form.formState.isSubmitting}>{form.formState.isSubmitting ? "设置中" : "设置并进入"}</Button></FieldGroup></form></CardContent></Card></main>
}

function Authentication({ onLogin }: { onLogin: () => void }) {
  const [status, setStatus] = useState<{ initializationRequired: boolean }>()
  const [error, setError] = useState<string>()
  const refreshStatus = useCallback(async () => {
    setError(undefined)
    try {
      setStatus(await api<{ initializationRequired: boolean }>("/api/auth/status"))
    } catch (error) {
      setError(error instanceof Error ? error.message : "无法读取初始化状态")
    }
  }, [])
  useEffect(() => { void refreshStatus() }, [refreshStatus])
  if (error) return <main className="grid min-h-[100dvh] place-items-center bg-muted p-4"><Card className="w-full max-w-sm"><CardHeader><CardTitle>ProbeLoom</CardTitle><CardDescription>{error}</CardDescription></CardHeader><CardContent><Button onClick={() => void refreshStatus()}>重试</Button></CardContent></Card></main>
  if (!status) return <main className="grid min-h-[100dvh] place-items-center"><div className="grid w-80 gap-3"><Skeleton className="h-8 w-40" /><Skeleton className="h-36 w-full" /></div></main>
  return status.initializationRequired ? <InitializeAdmin onLogin={onLogin} refreshStatus={refreshStatus} /> : <Login onLogin={onLogin} />
}

function CasesPage({ data, reload }: { data: Bootstrap; reload: () => Promise<void> }) {
  return <CaseExecutionWorkspace data={data} reload={reload} />
}

function App() {
  useEffect(() => {
    if (!import.meta.env.PROD) return
    let active = true
    let checking = false
    let notified = false
    const checkVersion = async () => {
      if (document.hidden || checking || notified) return
      checking = true
      try {
        const response = await fetch(`${import.meta.env.BASE_URL}version.json`, { cache: "no-store" })
        if (!response.ok) return
        const version: { buildId: string } = await response.json()
        if (active && version.buildId !== import.meta.env.VITE_PLATFORM_BUILD_ID) {
          notified = true
          toast.info("平台已更新，请刷新页面使用新版本", {
            id: "platform-update",
            duration: Infinity,
            action: { label: "刷新页面", onClick: () => window.location.reload() },
          })
        }
      } catch {
        // 部署或网络暂不可用时，留待下次检查。
      } finally {
        checking = false
      }
    }
    void checkVersion()
    const timer = window.setInterval(() => void checkVersion(), 30000)
    document.addEventListener("visibilitychange", checkVersion)
    return () => {
      active = false
      window.clearInterval(timer)
      document.removeEventListener("visibilitychange", checkVersion)
      toast.dismiss("platform-update")
    }
  }, [])
  const [tokenVersion, setTokenVersion] = useState(0)
  const [data, setData] = useState<Bootstrap>()
  const [view, setView] = useState<View>("statistics")
  const [loading, setLoading] = useState(false)
  const [caseSummariesLoaded, setCaseSummariesLoaded] = useState(false)
  const reload = useCallback(async () => {
    setLoading(true)
    setCaseSummariesLoaded(false)
    try {
      const bootstrap = await api<Bootstrap>("/api/bootstrap")
      setData(bootstrap)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "加载失败")
      if (!localStorage.getItem(tokenKey)) setTokenVersion((value) => value + 1)
    } finally {
      setLoading(false)
    }
  }, [])
  const loadCaseSummaries = useCallback(async () => {
    try {
      const cases = await api<TestCase[]>("/api/cases/summaries")
      setData((current) => current ? { ...current, cases } : current)
      setCaseSummariesLoaded(true)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "加载案例目录失败")
    }
  }, [])
  const loggedIn = Boolean(localStorage.getItem(tokenKey))
  useEffect(() => { if (loggedIn) void reload() }, [loggedIn, tokenVersion, reload])
  useEffect(() => { if (loggedIn && (view === "executions" || view === "statistics") && !caseSummariesLoaded) void loadCaseSummaries() }, [caseSummariesLoaded, loadCaseSummaries, loggedIn, view])
  if (!loggedIn) return <TooltipProvider><Authentication onLogin={() => setTokenVersion((value) => value + 1)} /><Toaster position="top-right" /></TooltipProvider>
  if (!data) return <TooltipProvider><main className="grid min-h-[100dvh] place-items-center"><div className="grid w-80 gap-3"><Skeleton className="h-8 w-40" /><Skeleton className="h-36 w-full" /></div><Toaster position="top-right" /></main></TooltipProvider>
  const content = view === "statistics" ? <StatisticsPage data={data} /> : view === "groups" ? <GroupManagementPage data={data} reload={reload} /> : view === "config" ? <ConfigPage data={data} reload={reload} /> : view === "cases" ? <CasesPage data={data} reload={reload} /> : view === "recycle" ? <CaseRecycleBinPage onRestored={reload} /> : view === "schedules" ? <SchedulesPage data={data} reload={reload} /> : <ExecutionsPage data={data} reload={reload} />
  return <TooltipProvider><AppShell view={view} setView={setView} data={data} loading={loading} onLogout={() => { localStorage.removeItem(tokenKey); setData(undefined); setView("statistics"); setTokenVersion((value) => value + 1) }}>{content}</AppShell><Toaster position="top-right" /></TooltipProvider>
}

export default App
