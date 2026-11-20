import { useCallback, useEffect, useState } from "react"
import { Archive, LoaderCircle, RefreshCw, Save } from "lucide-react"
import { toast } from "sonner"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { api, formatTime } from "@/lib/platform-api"

type BackupConfig = {
  enabled: boolean; frequency: "DAILY" | "WEEKLY"; time: string; dayOfWeek?: number | null
  repositoryType: "SVN" | "GIT"; repositoryUrl: string; branch: string; username: string
  password?: string; passwordConfigured: boolean; certificateSha256: string
}
type BackupRun = { id: number; triggerType: string; status: string; startedAt: string; finishedAt?: string; caseCount: number; revision?: string; errorMessage?: string; repositoryUrl: string }
type BackupStatus = { running: boolean; nextRunAt?: string; runs: BackupRun[] }
const runLabels: Record<string, string> = { RUNNING: "备份中", SUCCESS: "成功", NO_CHANGES: "无变更", FAILED: "失败" }
const weekdays = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

export function BackupPanel() {
  const [config, setConfig] = useState<BackupConfig>()
  const [status, setStatus] = useState<BackupStatus>()
  const [saving, setSaving] = useState(false)
  const [starting, setStarting] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [error, setError] = useState("")
  const refresh = useCallback(async () => setStatus(await api<BackupStatus>("/api/case-backup/status")), [])

  useEffect(() => {
    let active = true
    void Promise.all([api<BackupConfig>("/api/case-backup/config"), api<BackupStatus>("/api/case-backup/status")])
      .then(([value, state]) => { if (active) { setConfig({ ...value, password: "" }); setStatus(state) } })
      .catch((reason: Error) => { if (active) setError(reason.message) })
    const timer = window.setInterval(() => {
      void api<BackupStatus>("/api/case-backup/status").then((value) => { if (active) setStatus(value) }).catch((reason: Error) => { if (active) setError(reason.message) })
    }, 5000)
    return () => { active = false; window.clearInterval(timer) }
  }, [])

  const change = (value: Partial<BackupConfig>) => { setConfig((current) => current && { ...current, ...value }); setDirty(true) }
  const save = async () => {
    setSaving(true)
    try {
      const value = await api<BackupConfig>("/api/case-backup/config", { method: "POST", body: JSON.stringify(config) })
      setConfig({ ...value, password: "" }); setDirty(false); setError("")
      await refresh()
      toast.success("备份配置已保存")
    } catch (reason) { toast.error(reason instanceof Error ? reason.message : "保存失败") }
    finally { setSaving(false) }
  }
  const run = async () => {
    setStarting(true)
    try {
      await api<BackupRun>("/api/case-backup/run", { method: "POST" })
      await refresh(); setError(""); toast.success("已开始备份")
    } catch (reason) { toast.error(reason instanceof Error ? reason.message : "提交备份失败") }
    finally { setStarting(false) }
  }

  if (!config) return <div className="p-4 text-sm text-muted-foreground">{error || "正在加载备份配置…"}</div>
  const busy = saving || starting || status?.running
  return <div className="min-h-0 flex-1 overflow-auto space-y-6 pb-4">
    <form className="space-y-5" onSubmit={(event) => { event.preventDefault(); void save() }}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div><h2 className="font-semibold">案例备份</h2><p className="mt-1 text-sm text-muted-foreground">导出全部未删除案例（含停用案例与附件）。仓库 cases/ 下保持平台分组名称和完整层级，覆盖更新。</p></div>
        <div className="flex gap-2"><Button type="button" variant="outline" disabled={busy || dirty || !config.repositoryUrl} onClick={() => void run()}>{starting || status?.running ? <LoaderCircle className="animate-spin" /> : <Archive />}立即备份</Button><Button type="submit" disabled={busy}>{saving ? <LoaderCircle className="animate-spin" /> : <Save />}保存配置</Button></div>
      </div>
      <fieldset disabled={busy} className="grid gap-5 rounded-lg border p-5 md:grid-cols-2 xl:grid-cols-3">
        <Field><FieldLabel htmlFor="backup-enabled">自动备份</FieldLabel><div className="flex h-9 items-center gap-3"><Switch id="backup-enabled" checked={config.enabled} onCheckedChange={(enabled) => change({ enabled })} /><span className="text-sm">{config.enabled ? "已启用" : "未启用"}</span></div><FieldDescription>按北京时间执行，同一时刻只运行一轮备份。</FieldDescription></Field>
        <Field><FieldLabel htmlFor="backup-frequency">频率</FieldLabel><Select value={config.frequency} onValueChange={(frequency: BackupConfig["frequency"]) => change({ frequency, dayOfWeek: frequency === "WEEKLY" ? config.dayOfWeek ?? 1 : null })}><SelectTrigger id="backup-frequency"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="DAILY">每天</SelectItem><SelectItem value="WEEKLY">每周</SelectItem></SelectContent></Select></Field>
        <Field><FieldLabel htmlFor="backup-time">备份时间（北京时间）</FieldLabel><Input id="backup-time" type="time" step={60} required value={config.time} onChange={(event) => change({ time: event.target.value })} /></Field>
        {config.frequency === "WEEKLY" && <Field><FieldLabel htmlFor="backup-weekday">星期</FieldLabel><Select value={String(config.dayOfWeek)} onValueChange={(value) => change({ dayOfWeek: Number(value) })}><SelectTrigger id="backup-weekday"><SelectValue /></SelectTrigger><SelectContent>{weekdays.map((name, index) => <SelectItem key={name} value={String(index + 1)}>{name}</SelectItem>)}</SelectContent></Select></Field>}
        <Field><FieldLabel htmlFor="backup-format">导出形式</FieldLabel><Input id="backup-format" value="Excel（每个案例一个 .xlsx 文件）" readOnly /></Field>
        <Field><FieldLabel htmlFor="backup-type">仓库类型</FieldLabel><Select value={config.repositoryType} onValueChange={(repositoryType: BackupConfig["repositoryType"]) => change({ repositoryType, certificateSha256: "", password: "", passwordConfigured: false })}><SelectTrigger id="backup-type"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="SVN">SVN</SelectItem><SelectItem value="GIT">Git</SelectItem></SelectContent></Select></Field>
        {config.repositoryType === "GIT" && <Field><FieldLabel htmlFor="backup-branch">Git 分支</FieldLabel><Input id="backup-branch" required placeholder="main" value={config.branch} onChange={(event) => change({ branch: event.target.value })} /></Field>}
        <Field className="md:col-span-2 xl:col-span-3"><FieldLabel htmlFor="backup-url">仓库地址（HTTP / HTTPS）</FieldLabel><Input id="backup-url" type="url" required placeholder="https://svn.example.com/project/testCase" value={config.repositoryUrl} onChange={(event) => change({ repositoryUrl: event.target.value })} /><FieldDescription>填写备份专用目录地址；Git 填仓库地址。平台只同步 cases/ 目录，案例改名、换组或删除会同步到仓库。</FieldDescription></Field>
        <Field><FieldLabel htmlFor="backup-user">仓库用户名</FieldLabel><Input id="backup-user" autoComplete="off" value={config.username} onChange={(event) => change({ username: event.target.value })} /></Field>
        <Field><FieldLabel htmlFor="backup-password">{config.repositoryType === "GIT" ? "仓库密码 / 访问令牌" : "仓库密码"}</FieldLabel><Input id="backup-password" type="password" autoComplete="new-password" value={config.password || ""} placeholder={config.passwordConfigured ? "已配置，留空保持不变" : "私有仓库需填写"} onChange={(event) => change({ password: event.target.value })} /><FieldDescription>加密保存；留空仅在仓库类型、地址和用户名不变时保留原口令。</FieldDescription></Field>
        {config.repositoryType === "SVN" && <Field className="md:col-span-2 xl:col-span-3"><FieldLabel htmlFor="backup-certificate">SVN 证书 SHA-256 指纹</FieldLabel><Input id="backup-certificate" className="font-mono text-xs" pattern="[0-9a-fA-F]{64}" placeholder="自签名证书填写 64 位十六进制指纹，公共可信证书留空" value={config.certificateSha256} onChange={(event) => change({ certificateSha256: event.target.value })} /><FieldDescription>仅信任匹配指纹的服务器证书。</FieldDescription></Field>}
      </fieldset>
      {dirty && <p className="text-sm text-muted-foreground">配置尚未保存。保存后可立即备份。</p>}
    </form>
    <section className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3"><div className="flex flex-wrap items-center gap-3"><h2 className="font-semibold">备份记录</h2><Badge variant="outline">{status?.running ? "备份中" : "空闲"}</Badge><span className="text-sm text-muted-foreground">下次执行：{status?.nextRunAt ? formatTime(status.nextRunAt) : "未启用"}</span></div><Button type="button" size="sm" variant="outline" onClick={() => void refresh().then(() => setError("")).catch((reason: Error) => setError(reason.message))}><RefreshCw />刷新</Button></div>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      <div className="rounded-lg border"><Table><TableHeader><TableRow><TableHead>开始时间</TableHead><TableHead>触发方式</TableHead><TableHead>结果</TableHead><TableHead>案例数</TableHead><TableHead>提交版本</TableHead><TableHead>完成时间 / 失败原因</TableHead></TableRow></TableHeader><TableBody>
        {status?.runs.map((run) => <TableRow key={run.id}><TableCell className="whitespace-nowrap">{formatTime(run.startedAt)}</TableCell><TableCell>{run.triggerType === "SCHEDULE" ? "定时" : "手工"}</TableCell><TableCell><Badge variant={run.status === "FAILED" ? "destructive" : "secondary"}>{runLabels[run.status]}</Badge></TableCell><TableCell>{run.caseCount}</TableCell><TableCell className="max-w-44 truncate font-mono" title={run.revision}>{run.revision || "—"}</TableCell><TableCell className="max-w-96 whitespace-normal break-all"><div>{formatTime(run.finishedAt)}</div>{run.errorMessage && <p className="mt-1 text-destructive">{run.errorMessage}</p>}</TableCell></TableRow>)}
        {!status?.runs.length && <TableRow><TableCell colSpan={6} className="h-24 text-center text-muted-foreground">尚无备份记录</TableCell></TableRow>}
      </TableBody></Table></div>
    </section>
  </div>
}
