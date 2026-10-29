#!/bin/sh
set -eu

: "${ARGO_APP:?}"
: "${ARGO_NAMESPACE:=argocd}"
: "${GITOPS_REVISION:?}"
: "${K8S_NAMESPACE:?}"
: "${K8S_DEPLOYMENT:?}"
: "${EXPECTED_IMAGE:?}"
: "${TIMEOUT_SECONDS:=600}"

case "$GITOPS_REVISION" in *[!0-9a-f]*|'') echo "Invalid GitOps revision" >&2; exit 1;; esac
[ "${#GITOPS_REVISION}" -eq 40 ] || { echo "GitOps revision must be a full SHA" >&2; exit 1; }
now() { date +%s; }
deadline=$(( $(now) + TIMEOUT_SECONDS ))

sync_app() {
  for attempt in 1 2 3; do
    state=$(kubectl --request-timeout=20s -n "$ARGO_NAMESPACE" get application "$ARGO_APP" -o 'jsonpath={.metadata.resourceVersion}|{.operation.sync.revision}') || {
      if [ "$attempt" -lt 3 ]; then sleep 2; continue; fi
      return 1
    }
    rv=${state%%|*}; active=${state#*|}
    if [ "$active" = "$GITOPS_REVISION" ]; then return 0; fi
    if [ -n "$active" ]; then sleep 2; continue; fi
    if kubectl --request-timeout=20s -n "$ARGO_NAMESPACE" patch application "$ARGO_APP" --type=merge -p "$(cat <<JSON
{"metadata":{"resourceVersion":"$rv","annotations":{"argocd.argoproj.io/refresh":"hard"}},"operation":{"sync":{"revision":"$GITOPS_REVISION","prune":false}}}
JSON
)"; then
      return 0
    fi
    if [ "$attempt" -lt 3 ]; then sleep 2; fi
  done
  echo "Failed to request ArgoCD sync for $ARGO_APP" >&2
  exit 1
}

command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required in Jenkins agent" >&2; exit 1; }
sync_app
while [ "$(now)" -lt "$deadline" ]; do
  phase=$(kubectl -n "$ARGO_NAMESPACE" get application "$ARGO_APP" -o jsonpath='{.status.operationState.phase}' 2>/dev/null || true)
  revision=$(kubectl -n "$ARGO_NAMESPACE" get application "$ARGO_APP" -o jsonpath='{.status.sync.revision}' 2>/dev/null || true)
  sync=$(kubectl -n "$ARGO_NAMESPACE" get application "$ARGO_APP" -o jsonpath='{.status.sync.status}' 2>/dev/null || true)
  health=$(kubectl -n "$ARGO_NAMESPACE" get application "$ARGO_APP" -o jsonpath='{.status.health.status}' 2>/dev/null || true)
  image=$(kubectl -n "$K8S_NAMESPACE" get deployment "$K8S_DEPLOYMENT" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)
  replicas=$(kubectl -n "$K8S_NAMESPACE" get deployment "$K8S_DEPLOYMENT" -o jsonpath='{.spec.replicas}' 2>/dev/null || true)
  ready=$(kubectl -n "$K8S_NAMESPACE" get deployment "$K8S_DEPLOYMENT" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
  observed=$(kubectl -n "$K8S_NAMESPACE" get deployment "$K8S_DEPLOYMENT" -o jsonpath='{.status.observedGeneration}' 2>/dev/null || true)
  generation=$(kubectl -n "$K8S_NAMESPACE" get deployment "$K8S_DEPLOYMENT" -o jsonpath='{.metadata.generation}' 2>/dev/null || true)
  replicas=${replicas:-0}; ready=${ready:-0}
  echo "Argo phase=$phase sync=$sync health=$health revision=$revision image=$image ready=$ready/$replicas generation=$observed/$generation"

  if [ "$phase" = "Failed" ] || [ "$phase" = "Error" ]; then
    kubectl -n "$ARGO_NAMESPACE" get application "$ARGO_APP" -o yaml | tail -80
    exit 1
  fi
  if [ "$phase" = "Succeeded" ] && [ "$revision" = "$GITOPS_REVISION" ] && [ "$sync" = "Synced" ] && [ "$health" = "Healthy" ] && [ "$image" = "$EXPECTED_IMAGE" ] && [ "$ready" = "$replicas" ] && [ "$observed" = "$generation" ]; then
    exit 0
  fi
  sleep 10
done

echo "Timed out waiting for ArgoCD sync and live image"
kubectl -n "$ARGO_NAMESPACE" get application "$ARGO_APP" -o yaml | tail -120
kubectl -n "$K8S_NAMESPACE" get deployment "$K8S_DEPLOYMENT" -o wide
exit 1
