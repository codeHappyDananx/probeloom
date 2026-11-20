import { useEffect, useMemo, useState } from "react"
import { Controller, useForm } from "react-hook-form"
import { z } from "zod"
import { zodResolver } from "@hookform/resolvers/zod"
import { LoaderCircle, Play, Save, Upload } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { ScrollArea } from "@/components/ui/scroll-area"
import { GroupTreeSelect } from "@/features/cases/GroupTreeSelect"
import { ApplicationEnvironments, selectedEnvironments } from "@/features/cases/ApplicationEnvironments"
import { caseLabel, groupLabel } from "@/features/cases/case-utils"
import { descendantGroupIds, orderedGroups } from "@/features/cases/group-tree"
import { api } from "@/lib/platform-api"
import type { CaseGroup, Environment, Id, TestCase } from "@/types/platform"

const groupSchema = z.object({ id: z.number().optional(), name: z.string().trim().min(1, "请输入分组名称"), parentId: z.number().int().positive().optional(), caseIds: z.array(z.number()) })
type GroupForm = z.infer<typeof groupSchema>

export function GroupEditorDialog({ open, group, defaultParentId, groups, cases, onOpenChange, onSaved }: { open: boolean; group?: CaseGroup; defaultParentId?: Id; groups: CaseGroup[]; cases: TestCase[]; onOpenChange: (open: boolean) => void; onSaved: (group: CaseGroup) => Promise<void> }) {
  const form = useForm<GroupForm>({ resolver: zodResolver(groupSchema), defaultValues: { name: "", caseIds: [] } })
  const parentOptions = useMemo(() => {
    const excluded = group?.id === undefined ? new Set<Id>() : descendantGroupIds(groups, group.id)
    if (group?.id !== undefined) excluded.add(group.id)
    return orderedGroups(groups).filter((item) => item.id !== undefined && !excluded.has(item.id))
  }, [group, groups])
  useEffect(() => { if (open) form.reset(group ? { id: group.id, name: group.name, parentId: group.parentId, caseIds: group.caseIds } : { name: "", parentId: defaultParentId, caseIds: [] }) }, [defaultParentId, form, group, open])
  const save = () => void form.handleSubmit(async (value) => {
    try {
      const saved = await api<CaseGroup>("/api/groups", { method: "POST", body: JSON.stringify(value) })
      await onSaved(saved)
      toast.success("分组已保存")
      onOpenChange(false)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存失败")
    }
  })()
  return <Dialog open={open} onOpenChange={onOpenChange}><DialogContent className="sm:max-w-2xl overflow-hidden"><form id="group-editor-form" className="grid max-h-[calc(100dvh-2rem)] min-h-0 grid-rows-[auto_minmax(0,1fr)_auto] gap-5" onSubmit={(event) => { event.preventDefault(); save() }}><DialogHeader className="shrink-0"><DialogTitle>案例分组</DialogTitle><DialogDescription>每个案例只归属一个具体分组；父分组执行时会包含全部子分组案例。</DialogDescription></DialogHeader><ScrollArea className="min-h-0 pr-3"><FieldGroup><Controller name="name" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="group-name">分组名称</FieldLabel><Input {...field} id="group-name" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="parentId" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="group-parent">父分组</FieldLabel><GroupTreeSelect id="group-parent" className="w-full" groups={parentOptions} value={field.value} onValueChange={field.onChange} onBlur={field.onBlur} emptyLabel="根分组" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="caseIds" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel>直属案例</FieldLabel><div className="grid gap-3">{cases.filter((item) => item.id !== undefined).map((item) => { const selected = field.value.includes(item.id!); return <Label key={item.id} className="flex items-center gap-2 text-sm font-normal"><Checkbox checked={selected} onCheckedChange={(checked) => field.onChange(checked ? [...field.value, item.id!] : field.value.filter((id) => id !== item.id))} /><OverflowTooltip content={caseLabel(item)} className="block min-w-0 flex-1"><span className="block truncate">{caseLabel(item)}</span></OverflowTooltip></Label> })}</div>{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /></FieldGroup></ScrollArea><DialogFooter className="shrink-0 border-t pt-4"><Button type="button" variant="outline" disabled={form.formState.isSubmitting} onClick={() => onOpenChange(false)}>取消</Button><Button type="submit" form="group-editor-form" disabled={form.formState.isSubmitting}>{form.formState.isSubmitting ? <LoaderCircle className="animate-spin" /> : <Save />}{form.formState.isSubmitting ? "保存中" : "保存"}</Button></DialogFooter></form></DialogContent></Dialog>
}

export function GroupRunDialog({ open, group, caseIds, environments, maxParallelism, onOpenChange, onRun }: { open: boolean; group?: CaseGroup; caseIds: Id[]; environments: Environment[]; maxParallelism: number; onOpenChange: (open: boolean) => void; onRun: (environmentIds: Record<string, Id>, parallelism: number) => Promise<void> }) {
  const [applications, setApplications] = useState<string[]>()
  const [selected, setSelected] = useState<Record<string, Id>>({})
  const [busy, setBusy] = useState(false)
  const [parallelismInput, setParallelismInput] = useState(String(maxParallelism))
  const parallelism = Number(parallelismInput)
  const validParallelism = Number.isInteger(parallelism) && parallelism >= 2 && parallelism <= maxParallelism
  useEffect(() => {
    setApplications(undefined)
    if (!open) return
    let active = true
    void api<string[]>("/api/cases/applications", { method: "POST", body: JSON.stringify(caseIds) }).then((values) => { if (active) setApplications(values) }).catch((error) => { if (active) toast.error(error instanceof Error ? error.message : "加载分组案例失败") })
    return () => { active = false }
  }, [open, caseIds])
  const environmentIds = selectedEnvironments(applications || [], environments, selected)
  const canRun = applications !== undefined && applications.every((application) => environmentIds[application] !== undefined)
  const run = async (limit: number) => {
    setBusy(true)
    try {
      await onRun(environmentIds, limit)
      toast.success("分组已进入执行队列")
      onOpenChange(false)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "执行失败")
    } finally { setBusy(false) }
  }
  return <Dialog open={open} onOpenChange={onOpenChange}><DialogContent className="sm:max-w-xl"><DialogHeader><DialogTitle>执行分组</DialogTitle><DialogDescription>{group ? `${groupLabel(group)}，将执行 ${caseIds.length} 个案例。` : ""}</DialogDescription></DialogHeader><ApplicationEnvironments id="group-run-environment" applications={applications || []} environments={environments} selected={environmentIds} disabled={busy} onChange={(application, id) => setSelected((current) => ({ ...current, [application]: id }))} /><DialogFooter className="flex-wrap items-center gap-3"><Button type="button" variant="outline" disabled={busy} onClick={() => onOpenChange(false)}>取消</Button><Button variant="outline" disabled={busy || !canRun} onClick={() => void run(1)}><Play />串行执行</Button><div className="flex items-center gap-2 border-l pl-3"><label className="flex items-center gap-2 whitespace-nowrap text-xs">并发数<Input aria-label="分组批次并发数" className="h-9 w-16" type="number" min={2} max={maxParallelism} step={1} value={parallelismInput} disabled={busy || maxParallelism < 2} onChange={(event) => setParallelismInput(event.target.value)} /></label><Button disabled={busy || !canRun || !validParallelism} onClick={() => void run(parallelism)}><Play />并行执行</Button></div></DialogFooter></DialogContent></Dialog>
}

const workbookImportSchema = z.object({ files: z.array(z.instanceof(File)).min(1, "请选择案例工作簿"), groupId: z.number().int().positive().optional() })
type WorkbookImportForm = z.infer<typeof workbookImportSchema>

export function WorkbookImportDialog({ open, groups, onOpenChange, onImported }: { open: boolean; groups: CaseGroup[]; onOpenChange: (open: boolean) => void; onImported: (caseIds: Id[]) => Promise<void> }) {
  const [inputKey, setInputKey] = useState(0)
  const form = useForm<WorkbookImportForm>({ resolver: zodResolver(workbookImportSchema), defaultValues: { files: [], groupId: undefined } })
  const files = form.watch("files")
  useEffect(() => { if (!open) { form.reset({ files: [], groupId: undefined }); setInputKey((value) => value + 1) } }, [form, open])
  const submit = () => void form.handleSubmit(async ({ files, groupId }) => {
    let currentFileName = ""
    const importedIds: Id[] = []
    let importError: unknown
    try {
      for (const file of files) {
        currentFileName = file.name
        const body = new FormData()
        body.append("file", file)
        if (groupId) body.append("groupId", String(groupId))
        const imported = await api<TestCase>("/api/case-workbook/import", { method: "POST", body })
        if (imported.id !== undefined) importedIds.push(imported.id)
      }
    } catch (error) {
      importError = error
    }
    if (importedIds.length > 0) {
      try {
        await onImported(importedIds)
      } catch (error) {
        toast.error(error instanceof Error ? error.message : "刷新案例列表失败")
        return
      }
    }
    if (importError) {
      const message = importError instanceof Error ? importError.message : "导入失败"
      toast.error(`${currentFileName}：${message}`)
      if (importedIds.length > 0) onOpenChange(false)
      return
    }
    toast.success(`${files.length} 个案例工作簿已导入`)
    onOpenChange(false)
  })()
  return <Dialog open={open} onOpenChange={onOpenChange}><DialogContent className="sm:max-w-md"><form id="workbook-import-form" className="grid gap-6" onSubmit={(event) => { event.preventDefault(); submit() }}><DialogHeader><DialogTitle>导入案例工作簿</DialogTitle><DialogDescription>可一次选择多个工作簿，归入一个具体分组，也可直接导入为未分组案例。</DialogDescription></DialogHeader><FieldGroup><Controller name="groupId" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="workbook-group">所属分组</FieldLabel><GroupTreeSelect id="workbook-group" className="w-full" groups={groups} value={field.value} onValueChange={field.onChange} onBlur={field.onBlur} emptyLabel="未分组" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="files" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="workbook-file">案例工作簿</FieldLabel><Input key={inputKey} id="workbook-file" type="file" multiple accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" name={field.name} ref={field.ref} onBlur={field.onBlur} onChange={(event) => field.onChange(Array.from(event.target.files ?? []))} aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /></FieldGroup><DialogFooter><Button type="button" variant="outline" onClick={() => onOpenChange(false)}>取消</Button><Button type="submit" form="workbook-import-form" disabled={files.length === 0 || form.formState.isSubmitting}><Upload />{form.formState.isSubmitting ? "导入中" : "导入工作簿"}</Button></DialogFooter></form></DialogContent></Dialog>
}
