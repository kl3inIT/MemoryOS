{{/*
Expand the name of the chart.
*/}}
{{- define "memoryos-interpreter.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "memoryos-interpreter.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "memoryos-interpreter.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "memoryos-interpreter.labels" -}}
helm.sh/chart: {{ include "memoryos-interpreter.chart" . }}
{{ include "memoryos-interpreter.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "memoryos-interpreter.selectorLabels" -}}
app.kubernetes.io/name: {{ include "memoryos-interpreter.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "memoryos-interpreter.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "memoryos-interpreter.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Get the namespace for Kubernetes executor
*/}}
{{- define "memoryos-interpreter.kubernetesNamespace" -}}
{{- if ne .Values.codeInterpreter.kubernetesExecutor.namespace "" }}
{{- .Values.codeInterpreter.kubernetesExecutor.namespace }}
{{- else }}
{{- .Release.Namespace }}
{{- end }}
{{- end }}