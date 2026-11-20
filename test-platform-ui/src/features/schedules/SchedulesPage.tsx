import { useMemo, useState } from "react"
import { Controller, useForm } from "react-hook-form"
import type { ColumnDef } from "@tanstack/react-table"
import { zodResolver } from "@hookform/resolvers/zod"
import { z } from "zod"
import { LoaderCircle, Pencil, Plus, Save, Search } from "lucide-react"
import { toast } from "sonner"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/ui/data-table"
import { DataTableColumnHeader } from "@/components/ui/data-table-column-header"
import type { DataTableFeatures } from "@/components/ui/data-table-features"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldContent, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { groupLabel } from "@/features/cases/case-utils"
import { GroupTreeSelect } from "@/features/cases/GroupTreeSelect"
import { descendantGroupIds } from "@/features/cases/group-tree"
import { api } from "@/lib/platform-api"
import type { Bootstrap, Schedule } from "@/types/platform"

const scheduleSchema = z.object({
  id: z.number().optional(),
  groupId: z.number().int().positive("请选择案例分组"),
  environmentId: z.number().int().positive("请选择运行环境").optional(),
  name: z.string().trim().min(1, "请输入计划名称"),
  cronExpression: z.string().trim().min(1, "请输入 Cron 表达式"),
  enabled: z.boolean(),
})

type ScheduleForm = z.infer<typeof scheduleSchema>

export function SchedulesPage({ data, reload }: { data: Bootstrap; reload: () => void }) {
  const [open, setOpen] = useState(false)
  const [keyword, setKeyword] = useState("")
  const [groupId, setGroupId] = useState("ALL")
  const [environmentId, setEnvironmentId] = useState("ALL")
  const [enabled, setEnabled] = useState("ALL")
  const form = useForm<ScheduleForm>({ resolver: zodResolver(scheduleSchema), defaultValues: { name: "", cronExpression: "0 0 2 * * *", enabled: true, groupId: data.groups[0]?.id, environmentId: undefined } })
  const selectedGroupIds = useMemo(() => {
    if (groupId === "ALL") return undefined
    const values = descendantGroupIds(data.groups, Number(groupId))
    values.add(Number(groupId))
    return values
  }, [data.groups, groupId])
  const schedules = useMemo(() => {
    const query = keyword.trim().toLocaleLowerCase()
    return data.schedules.filter((item) => {
      const matchesKeyword = !query || [item.name, item.cronExpression].join(" ").toLocaleLowerCase().includes(query)
      return matchesKeyword && (!selectedGroupIds || (item.groupId !== undefined && selectedGroupIds.has(item.groupId))) && (environmentId === "ALL" || item.environmentId === Number(environmentId)) && (enabled === "ALL" || item.enabled === (enabled === "ENABLED"))
    })
  }, [data.schedules, enabled, environmentId, keyword, selectedGroupIds])
  const create = () => {
    form.reset({ groupId: data.groups[0]?.id, environmentId: undefined, name: "", cronExpression: "0 0 2 * * *", enabled: true })
    setOpen(true)
  }
  const edit = (item: Schedule) => {
    form.reset({ id: item.id, groupId: item.groupId, environmentId: item.environmentId ?? undefined, name: item.name, cronExpression: item.cronExpression, enabled: item.enabled })
    setOpen(true)
  }
  const save = () => void form.handleSubmit(async (value) => {
    try {
      await api<Schedule>("/api/schedules", { method: "POST", body: JSON.stringify(value) })
      toast.success("定时计划已保存")
      setOpen(false)
      reload()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存失败")
    }
  })()
  const columns: ColumnDef<DataTableFeatures, Schedule>[] = [
    { accessorKey: "name", meta: { label: "计划" }, header: ({ column }) => <DataTableColumnHeader column={column} title="计划" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { id: "group", accessorFn: (row) => data.groups.find((group) => group.id === row.groupId)?.name || "", meta: { label: "案例分组" }, header: ({ column }) => <DataTableColumnHeader column={column} title="案例分组" />, cell: ({ row }) => { const group = data.groups.find((item) => item.id === row.original.groupId); return group ? groupLabel(group) : "-" } },
    { id: "environment", accessorFn: (row) => data.environments.find((environment) => environment.id === row.environmentId)?.name || "", meta: { label: "运行环境" }, header: ({ column }) => <DataTableColumnHeader column={column} title="运行环境" />, cell: ({ row }) => data.environments.find((environment) => environment.id === row.original.environmentId)?.name || "应用默认" },
    { accessorKey: "cronExpression", meta: { label: "Cron" }, header: ({ column }) => <DataTableColumnHeader column={column} title="Cron" />, cell: ({ row }) => <span className="font-mono text-xs">{row.original.cronExpression}</span> },
    { accessorKey: "enabled", meta: { label: "状态" }, header: ({ column }) => <DataTableColumnHeader column={column} title="状态" />, cell: ({ row }) => <Badge variant={row.original.enabled ? "secondary" : "outline"}>{row.original.enabled ? "启用" : "停用"}</Badge> },
    { id: "actions", enableSorting: false, enableHiding: false, header: "", cell: ({ row }) => <div className="text-right"><Button variant="ghost" size="icon-sm" title="编辑计划" onClick={() => edit(row.original)}><Pencil /></Button></div> },
  ]

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col gap-4">
      <div className="flex shrink-0 flex-wrap items-end justify-between gap-3">
        <div><h1 className="text-lg font-semibold">定时计划</h1><p className="mt-1 text-sm text-muted-foreground">可指定运行环境或使用应用默认环境，纯数据库案例无需环境。使用 Asia/Shanghai 六位 Cron。</p></div>
        <Button disabled={!data.groups.length} onClick={create}><Plus />新建计划</Button>
      </div>
      <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        <DataTable columns={columns} data={schedules} emptyMessage="还没有匹配的定时计划" toolbar={() => <div className="flex min-w-0 flex-1 flex-wrap gap-2"><InputGroup className="min-w-56 flex-1"><InputGroupInput aria-label="检索定时计划" placeholder="搜索计划名称或 Cron" value={keyword} onChange={(event) => setKeyword(event.target.value)} /><InputGroupAddon><Search /></InputGroupAddon></InputGroup><GroupTreeSelect className="w-52" groups={data.groups} value={groupId === "ALL" ? undefined : Number(groupId)} onValueChange={(value) => setGroupId(value === undefined ? "ALL" : String(value))} /><Select value={environmentId} onValueChange={setEnvironmentId}><SelectTrigger className="w-40"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="ALL">全部环境</SelectItem>{data.environments.map((item) => <SelectItem key={item.id} value={String(item.id)}>{item.name}</SelectItem>)}</SelectContent></Select><Select value={enabled} onValueChange={setEnabled}><SelectTrigger className="w-28"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="ALL">全部状态</SelectItem><SelectItem value="ENABLED">已启用</SelectItem><SelectItem value="DISABLED">已停用</SelectItem></SelectContent></Select></div>} />
      </div>
      <Dialog open={open} onOpenChange={setOpen}><DialogContent className="sm:max-w-2xl overflow-hidden"><form id="schedule-form" className="grid max-h-[calc(100dvh-2rem)] min-h-0 grid-rows-[auto_minmax(0,1fr)_auto] gap-5" onSubmit={(event) => { event.preventDefault(); save() }}><DialogHeader className="shrink-0"><DialogTitle>定时计划</DialogTitle><DialogDescription>保存后立即重载调度器；选择父分组时会执行整个子树。</DialogDescription></DialogHeader><ScrollArea className="min-h-0 pr-3"><FieldGroup><Controller name="name" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="schedule-name">计划名称</FieldLabel><Input {...field} id="schedule-name" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="groupId" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="schedule-group">案例分组</FieldLabel><GroupTreeSelect id="schedule-group" className="w-full" groups={data.groups} value={field.value} onValueChange={field.onChange} onBlur={field.onBlur} required aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="environmentId" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="schedule-environment">运行环境</FieldLabel><Select value={field.value === undefined ? "NONE" : String(field.value)} onValueChange={(value) => field.onChange(value === "NONE" ? undefined : Number(value))}><SelectTrigger id="schedule-environment" aria-invalid={fieldState.invalid}><SelectValue placeholder="请选择环境" /></SelectTrigger><SelectContent><SelectItem value="NONE">使用应用默认环境</SelectItem>{data.environments.map((item) => <SelectItem key={item.id} value={String(item.id)}>{item.name}</SelectItem>)}</SelectContent></Select>{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="cronExpression" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="schedule-cron">Cron 表达式</FieldLabel><Input {...field} id="schedule-cron" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="enabled" control={form.control} render={({ field }) => <Field orientation="horizontal"><FieldContent><FieldLabel htmlFor="schedule-enabled">计划状态</FieldLabel><FieldDescription>{field.value ? "已启用" : "已停用"}</FieldDescription></FieldContent><Switch id="schedule-enabled" checked={field.value} onCheckedChange={field.onChange} /></Field>} /></FieldGroup></ScrollArea><DialogFooter className="shrink-0 border-t pt-4"><Button type="button" variant="outline" disabled={form.formState.isSubmitting} onClick={() => setOpen(false)}>取消</Button><Button type="submit" form="schedule-form" disabled={form.formState.isSubmitting}>{form.formState.isSubmitting ? <LoaderCircle className="animate-spin" /> : <Save />}{form.formState.isSubmitting ? "保存中" : "保存"}</Button></DialogFooter></form></DialogContent></Dialog>
    </div>
  )
}
