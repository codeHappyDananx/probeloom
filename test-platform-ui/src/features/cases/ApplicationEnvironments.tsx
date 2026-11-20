import { Field, FieldLabel } from "@/components/ui/field"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import type { CaseAction, Environment, Id } from "@/types/platform"

export const targetApplication = (application?: string) => application || "default"

export function actionApplications(actions: CaseAction[]) {
  const calls = actions.filter((action) => ["HTTP", "UPLOAD"].includes(action.actionType))
  return [...new Set(calls.map((action) => targetApplication(action.applicationName)))]
}

export function selectedEnvironments(applications: string[], environments: Environment[], selected: Record<string, Id>) {
  return Object.fromEntries(applications.flatMap((application) => {
    const options = environments.filter((item) => targetApplication(item.refapp) === application)
    const environment = options.find((item) => item.id === selected[application]) || options.find((item) => item.defaultEnvironment) || options[0]
    return environment?.id === undefined ? [] : [[application, environment.id]]
  }))
}

export function ApplicationEnvironments({ id, applications, environments, selected, disabled, onChange }: {
  id: string; applications: string[]; environments: Environment[]; selected: Record<string, Id>; disabled?: boolean;
  onChange: (application: string, environmentId: Id) => void;
}) {
  return <div className="flex flex-wrap items-end gap-2">{applications.map((application) => {
    const options = environments.filter((item) => targetApplication(item.refapp) === application)
    const fieldId = `${id}-${application}`
    return <Field key={application} className="w-auto gap-1">
      <FieldLabel htmlFor={fieldId} className="text-xs text-muted-foreground">{application}</FieldLabel>
      <Select value={selected[application] === undefined ? "" : String(selected[application])} onValueChange={(value) => onChange(application, Number(value))} disabled={disabled}>
        <SelectTrigger id={fieldId} className="w-48"><SelectValue placeholder={options.length ? "选择运行环境" : "请先配置该应用环境"} /></SelectTrigger>
        <SelectContent>{options.map((item) => <SelectItem key={item.id} value={String(item.id)}>{item.name}{item.defaultEnvironment ? "（默认）" : ""}</SelectItem>)}</SelectContent>
      </Select>
    </Field>
  })}</div>
}
