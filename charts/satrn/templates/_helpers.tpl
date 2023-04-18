{{/*
Expand the name of the chart.
*/}}
{{- define "satrn.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "satrn.fullname" -}}
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
{{- define "satrn.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "satrn.labels" -}}
helm.sh/chart: {{ include "satrn.chart" . }}
{{ include "satrn.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "satrn.selectorLabels" -}}
app.kubernetes.io/name: {{ include "satrn.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "satrn.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "satrn.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}


{{/*
Define a container which regularly syncs a git-repo
EXAMPLE USAGE: {{ include "satrn.container.git_sync" (dict "Release" .Release "Values" .Values "sync_one_time" "true") }}
*/}}
{{- define "satrn.container.git_sync" }}
{{- if .sync_one_time }}
- name: config-git-clone
{{- else }}
- name: config-git-sync
{{- end }}
  image: {{ .Values.gitSync.image.repository }}:{{ .Values.gitSync.image.tag }}
  imagePullPolicy: {{ .Values.gitSync.image.pullPolicy }}
  securityContext:
    runAsUser: {{ .Values.gitSync.image.uid }}
    runAsGroup: {{ .Values.gitSync.image.gid }}
  resources:
    {{- toYaml .Values.gitSync.resources | nindent 4 }}
  env:
    {{- if .sync_one_time }}
    - name: GIT_SYNC_ONE_TIME
      value: "true"
    {{- end }}
    - name: GIT_SYNC_ROOT
      value: "/config"
    - name: GIT_SYNC_DEST
      value: "repo"
    - name: GIT_SYNC_REPO
      value: {{ .Values.gitSync.repo | quote }}
    - name: GIT_SYNC_BRANCH
      value: {{ .Values.gitSync.branch | quote }}
    - name: GIT_SYNC_REV
      value: {{ .Values.gitSync.revision | quote }}
    - name: GIT_SYNC_DEPTH
      value: {{ .Values.gitSync.depth | quote }}
    - name: GIT_SYNC_WAIT
      value: {{ .Values.gitSync.syncWait | quote }}
    - name: GIT_SYNC_TIMEOUT
      value: {{ .Values.gitSync.syncTimeout | quote }}
    - name: GIT_SYNC_ADD_USER
      value: "true"
    {{- if .Values.gitSync.sshSecret }}
    - name: GIT_SYNC_SSH
      value: "true"
    - name: GIT_SSH_KEY_FILE
      value: "/etc/git-secret/id_rsa"
    {{- end }}
    {{- if .Values.gitSync.sshKnownHosts }}
    - name: GIT_KNOWN_HOSTS
      value: "true"
    - name: GIT_SSH_KNOWN_HOSTS_FILE
      value: "/etc/git-secret/known_hosts"
    {{- else }}
    - name: GIT_KNOWN_HOSTS
      value: "false"
    {{- end }}
    {{- if and (.Values.gitSync.webhook.enabled) (not .sync_one_time) }}
    - name: GIT_SYNC_WEBHOOK_URL
      value: {{ .Values.gitSync.webhook.url }}
    {{- end }}
    {{- if .Values.gitSync.httpSecret }}
    - name: GIT_SYNC_USERNAME
      valueFrom:
        secretKeyRef:
          name: {{ .Values.gitSync.httpSecret }}
          key: {{ .Values.gitSync.httpSecretUsernameKey }}
    - name: GIT_SYNC_PASSWORD
      valueFrom:
        secretKeyRef:
          name: {{ .Values.gitSync.httpSecret }}
          key: {{ .Values.gitSync.httpSecretPasswordKey }}
    {{- end }}
  volumeMounts:
    - name: config-data
      mountPath: /config
    {{- if .Values.gitSync.sshSecret }}
    - name: git-secret
      mountPath: /etc/git-secret/id_rsa
      readOnly: true
      subPath: {{ .Values.gitSync.sshSecretKey }}
    {{- end }}
    {{- if .Values.gitSync.sshKnownHostsSecret }}
    - name: git-known-hosts
      mountPath: /etc/git-secret/known_hosts
      readOnly: true
      subPath: {{ .Values.gitSync.sshKnownHostsSecretKey }}
    {{- end }}
{{- end }}