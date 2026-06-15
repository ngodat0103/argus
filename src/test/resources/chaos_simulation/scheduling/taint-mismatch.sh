#!/usr/bin/env bash
set -eo pipefail

NAMESPACE="${NAMESPACE:-dev}"
POD_NAME="${POD_NAME:-taint-mismatch-pod}"
TAINT_KEY="${TAINT_KEY:-env}"
TAINT_VALUE="${TAINT_VALUE:-prod}"
TAINT_EFFECT="${TAINT_EFFECT:-NoSchedule}"

color() { printf "\033[%sm%s\033[0m\n" "$1" "$2"; }
info()  { color "1;34" "[INFO] $1"; }
warn()  { color "1;33" "[WARN] $1"; }
err()   { color "1;31" "[ERROR] $1"; }
success() { color "1;32" "[SUCCESS] $1"; }

usage() {
  cat <<EOF
Usage:
  $0 up        # taint ALL nodes, create namespace, pod -> trigger FailedScheduling
  $0 events    # show events for the test pod
  $0 recover   # remove taints, ensure pod is scheduled and running
  $0 down      # cleanup taints and resources

Environment overrides:
  NAMESPACE   (default: ${NAMESPACE})
  POD_NAME    (default: ${POD_NAME})
  TAINT_KEY   (default: ${TAINT_KEY})
  TAINT_VALUE (default: ${TAINT_VALUE})
  TAINT_EFFECT(default: ${TAINT_EFFECT})
EOF
}

check_prereqs() {
  if ! command -v kubectl >/dev/null 2>&1; then
    err "kubectl not found in PATH"
    exit 1
  fi
}

create_namespace() {
  if kubectl get ns "${NAMESPACE}" >/dev/null 2>&1; then
    info "Namespace ${NAMESPACE} already exists"
  else
    info "Creating namespace ${NAMESPACE}"
    kubectl create namespace "${NAMESPACE}"
  fi
}

apply_taint_to_all_nodes() {
  info "Getting all schedulable nodes..."
  local nodes
  nodes=$(kubectl get nodes --no-headers -o custom-columns=NAME:.metadata.name,TAINTS:.spec.taints | \
    grep -v "NoSchedule" || kubectl get nodes --no-headers -o custom-columns=NAME:.metadata.name)

  if [ -z "$nodes" ]; then
    err "No nodes found in cluster"
    exit 1
  fi

  info "Tainting ALL nodes with ${TAINT_KEY}=${TAINT_VALUE}:${TAINT_EFFECT}"
  while IFS= read -r node; do
    node_name=$(echo "$node" | awk '{print $1}')
    info "  → Tainting node: ${node_name}"
    kubectl taint nodes "${node_name}" \
      "${TAINT_KEY}=${TAINT_VALUE}:${TAINT_EFFECT}" \
      --overwrite
  done <<< "$nodes"
}

create_pod_without_toleration() {
  info "Creating pod ${POD_NAME} *without* tolerations in namespace ${NAMESPACE}"
  cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: ${POD_NAME}
  namespace: ${NAMESPACE}
spec:
  restartPolicy: Never
  containers:
    - name: nginx
      image: nginx:1.27-alpine
      resources:
        requests:
          cpu: "50m"
          memory: "64Mi"
EOF
}

show_failed_scheduling_events() {
  info "Fetching events for pod ${POD_NAME} in namespace ${NAMESPACE}"
  echo
  kubectl get events \
    --namespace "${NAMESPACE}" \
    --field-selector "involvedObject.kind=Pod,involvedObject.name=${POD_NAME}" \
    --sort-by=.lastTimestamp \
    -o wide || warn "No events found (yet?)"
  echo

  info "Pod status:"
  kubectl get pod "${POD_NAME}" -n "${NAMESPACE}" -o wide || true
  echo
}

remove_taints_from_all_nodes() {
  info "Removing taint ${TAINT_KEY}=${TAINT_VALUE}:${TAINT_EFFECT} from ALL nodes"
  local nodes
  nodes=$(kubectl get nodes --no-headers -o custom-columns=NAME:.metadata.name)

  while IFS= read -r node; do
    info "  → Removing taint from node: ${node}"
    kubectl taint nodes "${node}" "${TAINT_KEY}-" 2>/dev/null || warn "Taint not found on ${node}"
  done <<< "$nodes"
}

wait_for_pod_running() {
  local timeout=120
  local elapsed=0

  info "Waiting for pod ${POD_NAME} to become Running (timeout: ${timeout}s)..."

  while [ $elapsed -lt $timeout ]; do
    local status
    status=$(kubectl get pod "${POD_NAME}" -n "${NAMESPACE}" -o jsonpath='{.status.phase}' 2>/dev/null || echo "NotFound")

    if [ "$status" = "Running" ]; then
      success "Pod ${POD_NAME} is now Running!"
      return 0
    elif [ "$status" = "Failed" ] || [ "$status" = "Unknown" ]; then
      err "Pod entered ${status} state"
      return 1
    fi

    echo -n "."
    sleep 2
    elapsed=$((elapsed + 2))
  done

  echo
  err "Timeout waiting for pod to become Running"
  return 1
}

verify_pod_scheduled() {
  info "Verifying pod scheduling..."
  local node_name
  node_name=$(kubectl get pod "${POD_NAME}" -n "${NAMESPACE}" -o jsonpath='{.spec.nodeName}' 2>/dev/null || echo "")

  if [ -n "$node_name" ]; then
    success "Pod is scheduled on node: ${node_name}"
    return 0
  else
    warn "Pod is not yet scheduled to any node"
    return 1
  fi
}

cleanup() {
  info "Deleting pod ${POD_NAME} (if exists)"
  kubectl delete pod "${POD_NAME}" -n "${NAMESPACE}" --ignore-not-found

  remove_taints_from_all_nodes

  info "Namespace ${NAMESPACE} left in place. To delete it:"
  echo "  kubectl delete ns ${NAMESPACE}"
}

cmd_up() {
  check_prereqs
  create_namespace
  apply_taint_to_all_nodes
  create_pod_without_toleration

  info "Waiting a few seconds for scheduler to emit FailedScheduling events..."
  sleep 8

  show_failed_scheduling_events
  info "You should see a reason=FailedScheduling with a message about taints not tolerated."
}

cmd_events() {
  check_prereqs
  show_failed_scheduling_events
}

cmd_recover() {
  check_prereqs

  info "=== RECOVERY MODE ==="
  info "This will remove taints and ensure the pod is scheduled"
  echo

  # Check if pod exists
  if ! kubectl get pod "${POD_NAME}" -n "${NAMESPACE}" >/dev/null 2>&1; then
    warn "Pod ${POD_NAME} does not exist in namespace ${NAMESPACE}"
    info "Creating the pod first..."
    create_namespace
    create_pod_without_toleration
    sleep 2
  fi

  # Show current state
  info "Current pod state:"
  kubectl get pod "${POD_NAME}" -n "${NAMESPACE}" -o wide || true
  echo

  # Remove taints
  remove_taints_from_all_nodes
  echo

  # Wait for pod to be scheduled
  info "Waiting for Kubernetes scheduler to reschedule the pod..."
  sleep 5

  # Verify scheduling
  if verify_pod_scheduled; then
    echo
    # Wait for pod to be running
    if wait_for_pod_running; then
      echo
      success "=== RECOVERY COMPLETE ==="
      info "Final pod status:"
      kubectl get pod "${POD_NAME}" -n "${NAMESPACE}" -o wide
      echo
      info "Recent events:"
      kubectl get events \
        --namespace "${NAMESPACE}" \
        --field-selector "involvedObject.kind=Pod,involvedObject.name=${POD_NAME}" \
        --sort-by=.lastTimestamp \
        | tail -10
    else
      err "Recovery failed: Pod did not reach Running state"
      show_failed_scheduling_events
      exit 1
    fi
  else
    err "Recovery failed: Pod was not scheduled"
    show_failed_scheduling_events
    exit 1
  fi
}

cmd_down() {
  check_prereqs
  cleanup
}

main() {
  case "${1:-}" in
    up)      cmd_up ;;
    events)  cmd_events ;;
    recover) cmd_recover ;;
    down)    cmd_down ;;
    *)       usage; exit 1 ;;
  esac
}

main "$@"