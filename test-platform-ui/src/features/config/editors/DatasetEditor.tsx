import { zodResolver } from "@hookform/resolvers/zod"
import { LoaderCircle, Save } from "lucide-react"
import { Controller, useForm } from "react-hook-form"
import { z } from "zod"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import type { Dataset } from "@/types/platform"

const schema = z.object({ id: z.number().optional(), name: z.string().trim().min(1, "请输入数据集名称"), datasourceKey: z.string().trim().min(1, "请选择数据源标识"), variablesJson: z.string().optional(), prepareSql: z.string().optional(), cleanupSql: z.string().trim().min(1, "请输入后置清理 SQL") })
type DatasetForm = z.infer<typeof schema>

function DatasetEditor({ open, initialValue, resourceKeys, onOpenChange, onSave }: { open: boolean; initialValue: Dataset; resourceKeys: string[]; onOpenChange: (open: boolean) => void; onSave: (value: Dataset) => Promise<void> }) {
  const form = useForm<DatasetForm>({ resolver: zodResolver(schema), defaultValues: { ...initialValue, variablesJson: initialValue.variablesJson || "", prepareSql: initialValue.prepareSql || "" } })
  const submit = () => void form.handleSubmit(async (value) => onSave(value))()

  return <Dialog open={open} onOpenChange={onOpenChange}><DialogContent className="sm:max-w-2xl overflow-hidden"><form id="dataset-form" className="grid max-h-[calc(100dvh-2rem)] min-h-0 grid-rows-[auto_minmax(0,1fr)_auto] gap-5" onSubmit={(event) => { event.preventDefault(); submit() }}><DialogHeader className="shrink-0"><DialogTitle>固定数据集</DialogTitle><DialogDescription>只允许写入数据源执行前置和清理 SQL。</DialogDescription></DialogHeader><ScrollArea className="min-h-0 pr-3"><FieldGroup><div className="grid gap-4 md:grid-cols-2"><Controller name="name" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="dataset-name">数据集名称</FieldLabel><Input {...field} id="dataset-name" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="datasourceKey" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="dataset-datasource">数据源标识</FieldLabel><Select value={field.value} onValueChange={field.onChange}><SelectTrigger id="dataset-datasource" aria-invalid={fieldState.invalid}><SelectValue placeholder="请选择数据源" /></SelectTrigger><SelectContent>{resourceKeys.map((key) => <SelectItem key={key} value={key}>{key}</SelectItem>)}</SelectContent></Select>{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /></div><Controller name="variablesJson" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="dataset-variables">变量 JSON</FieldLabel><Textarea {...field} id="dataset-variables" className="font-mono text-xs" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="prepareSql" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="dataset-prepare-sql">前置 SQL</FieldLabel><Textarea {...field} id="dataset-prepare-sql" className="min-h-32 font-mono text-xs" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /><Controller name="cleanupSql" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="dataset-cleanup-sql">后置清理 SQL</FieldLabel><Textarea {...field} id="dataset-cleanup-sql" className="min-h-32 font-mono text-xs" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} /></FieldGroup></ScrollArea><DialogFooter className="shrink-0 border-t pt-4"><Button type="button" variant="outline" disabled={form.formState.isSubmitting} onClick={() => onOpenChange(false)}>取消</Button><Button type="submit" form="dataset-form" disabled={form.formState.isSubmitting}>{form.formState.isSubmitting ? <LoaderCircle className="animate-spin" /> : <Save />}{form.formState.isSubmitting ? "保存中" : "保存"}</Button></DialogFooter></form></DialogContent></Dialog>
}

export { DatasetEditor }
