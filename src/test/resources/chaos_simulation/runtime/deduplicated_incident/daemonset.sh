#!/bin/bash

# Kubernetes DaemonSet Rollout Simulator
# Simulates various DaemonSet scenarios for deduplication testing
# DaemonSets run one pod per node - useful for testing node name qualifiers

set -e

NAMESPACE="horizon"
DAEMONSET_NAME="nginx-demo"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_highlight() {
    echo -e "${CYAN}[NOTE]${NC} $1"
}

# Check if kubectl is available
check_kubectl() {
    if ! command -v kubectl &> /dev/null; then
        print_error "kubectl not found. Please install kubectl first."
        exit 1
    fi
}

# Create namespace if it doesn't exist
create_namespace() {
    if ! kubectl get namespace "$NAMESPACE" &> /dev/null; then
        print_info "Creating namespace: $NAMESPACE"
        kubectl create namespace "$NAMESPACE"
    else
        print_info "Namespace $NAMESPACE already exists"
    fi
}

# Show node information
show_nodes() {
    print_info "\n=== Cluster Nodes (DaemonSet will run on each) ==="
    kubectl get nodes -o wide
    NODE_COUNT=$(kubectl get nodes --no-headers | wc -l)
    print_highlight "DaemonSet will create $NODE_COUNT pod(s) - one per node"
}

# Normal mode: Create a healthy DaemonSet
normal_mode() {
    print_info "=== Running NORMAL mode (DaemonSet) ==="
    create_namespace
    show_nodes

    print_info "\nCreating initial nginx DaemonSet..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: $DAEMONSET_NAME
  namespace: $NAMESPACE
  labels:
    app: nginx-demo
spec:
  selector:
    matchLabels:
      app: nginx-demo
  updateStrategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
  template:
    metadata:
      labels:
        app: nginx-demo
        version: v1
    spec:
      tolerations:
      # Allow scheduling on control-plane nodes for testing
      - key: node-role.kubernetes.io/control-plane
        operator: Exists
        effect: NoSchedule
      - key: node-role.kubernetes.io/master
        operator: Exists
        effect: NoSchedule
      containers:
      - name: nginx
        image: nginx:1.21
        ports:
        - containerPort: 80
          name: web
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
          limits:
            memory: "128Mi"
            cpu: "200m"
EOF

    print_info "Waiting for DaemonSet to be ready..."
    kubectl rollout status daemonset/$DAEMONSET_NAME -n $NAMESPACE

    print_info "Performing rolling update to nginx:1.22..."
    kubectl set image daemonset/$DAEMONSET_NAME nginx=nginx:1.22 -n $NAMESPACE

    print_info "Watching rollout progress..."
    kubectl rollout status daemonset/$DAEMONSET_NAME -n $NAMESPACE

    print_info "\n=== DaemonSet Status ==="
    kubectl get daemonset $DAEMONSET_NAME -n $NAMESPACE

    print_info "\n=== Pods (one per node, showing NODE assignment) ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_highlight "\nDaemonSet pods are scheduled on each node:"
    print_highlight "  - Pod names include random suffix, NOT stable like StatefulSet"
    print_highlight "  - Node name can be used as qualifier for deduplication"
    print_highlight "  - Fingerprint should include: workloadType=DAEMONSET"

    print_info "\n${GREEN}NORMAL mode (DaemonSet) completed successfully!${NC}"
}

# Screw-up mode: Image failed - non-existent image
screw_up_image_failed() {
    print_info "=== Running SCREW-UP:IMAGE_FAILED mode (DaemonSet) ==="
    create_namespace
    show_nodes

    print_info "\nCreating initial working nginx DaemonSet..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: $DAEMONSET_NAME
  namespace: $NAMESPACE
  labels:
    app: nginx-demo
spec:
  selector:
    matchLabels:
      app: nginx-demo
  updateStrategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
  template:
    metadata:
      labels:
        app: nginx-demo
        version: v1
    spec:
      tolerations:
      - key: node-role.kubernetes.io/control-plane
        operator: Exists
        effect: NoSchedule
      - key: node-role.kubernetes.io/master
        operator: Exists
        effect: NoSchedule
      containers:
      - name: nginx
        image: nginx:1.21
        ports:
        - containerPort: 80
          name: web
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
          limits:
            memory: "128Mi"
            cpu: "200m"
EOF

    print_info "Waiting for DaemonSet to be ready..."
    kubectl rollout status daemonset/$DAEMONSET_NAME -n $NAMESPACE --timeout=120s

    print_warning "Now updating with BROKEN image (non-existent tag)..."
    kubectl set image daemonset/$DAEMONSET_NAME nginx=nginx:nonexistent-tag-9999 -n $NAMESPACE

    print_warning "Waiting 30 seconds for failed rollout to be visible..."
    print_highlight "DaemonSet updates pods one node at a time (maxUnavailable: 1)"
    sleep 30

    print_error "\n=== DaemonSet Status (ImagePullBackOff) ==="
    kubectl get daemonset $DAEMONSET_NAME -n $NAMESPACE

    print_error "\n=== Pods (showing ImagePullBackOff with NODE names) ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_warning "\n=== Pod Events (showing image pull failures) ==="
    FAILING_POD=$(kubectl get pods -n $NAMESPACE -l app=nginx-demo --field-selector=status.phase!=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
    if [ -n "$FAILING_POD" ]; then
        kubectl describe pod $FAILING_POD -n $NAMESPACE | grep -A10 "Events:" || true
    fi

    print_highlight "\nDeduplication Test Points:"
    print_highlight "  - One pod per node will fail"
    print_highlight "  - Each pod runs on different node (different node qualifier)"
    print_highlight "  - Fingerprint: {daemonsetName}|{namespace}|ImagePullBackOff|DAEMONSET|{nodeName}|{timeWindow}"
    print_highlight "  - With node qualifier: separate incidents per node"
    print_highlight "  - Without node qualifier: single deduplicated incident"

    print_error "\n${RED}SCREW-UP:IMAGE_FAILED mode (DaemonSet) completed!${NC}"
    print_info "The DaemonSet is stuck with ImagePullBackOff"
    print_info "\nTo fix: ./$(basename $0) clean"
    print_info "Or rollback: kubectl rollout undo daemonset/$DAEMONSET_NAME -n $NAMESPACE"
}

# Screw-up mode: Invalid ConfigMap - causes CrashLoopBackOff
screw_up_invalid_configmap() {
    print_info "=== Running SCREW-UP:INVALID_CONFIGMAP mode (DaemonSet) ==="
    create_namespace
    show_nodes

    print_info "\nCreating working nginx ConfigMap..."
    cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: nginx-config
  namespace: $NAMESPACE
data:
  nginx.conf: |
    events {
      worker_connections 1024;
    }
    http {
      server {
        listen 80;
        location / {
          return 200 'Hello from DaemonSet Nginx!';
          add_header Content-Type text/plain;
        }
      }
    }
EOF

    print_info "Creating initial working nginx DaemonSet with ConfigMap..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: $DAEMONSET_NAME
  namespace: $NAMESPACE
  labels:
    app: nginx-demo
spec:
  selector:
    matchLabels:
      app: nginx-demo
  updateStrategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
  template:
    metadata:
      labels:
        app: nginx-demo
        version: v1
    spec:
      tolerations:
      - key: node-role.kubernetes.io/control-plane
        operator: Exists
        effect: NoSchedule
      - key: node-role.kubernetes.io/master
        operator: Exists
        effect: NoSchedule
      containers:
      - name: nginx
        image: nginx:1.21
        ports:
        - containerPort: 80
          name: web
        volumeMounts:
        - name: nginx-config
          mountPath: /etc/nginx/nginx.conf
          subPath: nginx.conf
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
          limits:
            memory: "128Mi"
            cpu: "200m"
      volumes:
      - name: nginx-config
        configMap:
          name: nginx-config
EOF

    print_info "Waiting for DaemonSet to be ready..."
    kubectl rollout status daemonset/$DAEMONSET_NAME -n $NAMESPACE --timeout=120s

    print_info "Testing the working DaemonSet..."
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_warning "\nNow updating ConfigMap with INVALID nginx configuration..."
    cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: nginx-config
  namespace: $NAMESPACE
data:
  nginx.conf: |
    events {
      worker_connections 1024;
    }
    http {
      server {
        listen 80;
        location / {
          # Missing closing brace - this will cause nginx to crash
          return 200 'Broken config!';
          add_header Content-Type text/plain;
        }
      # Missing closing brace for server block
    # Missing closing brace for http block
EOF

    print_warning "Forcing DaemonSet restart to pick up new config..."
    kubectl rollout restart daemonset/$DAEMONSET_NAME -n $NAMESPACE

    print_warning "Waiting 45 seconds for CrashLoopBackOff to appear..."
    print_highlight "All pods on all nodes will crash simultaneously"
    sleep 45

    print_error "\n=== DaemonSet Status (CrashLoopBackOff) ==="
    kubectl get daemonset $DAEMONSET_NAME -n $NAMESPACE

    print_error "\n=== Pods (showing CrashLoopBackOff across all nodes) ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_warning "\n=== Check pod logs for nginx config errors ==="
    PODS=$(kubectl get pods -n $NAMESPACE -l app=nginx-demo -o jsonpath='{.items[*].metadata.name}')
    for POD in $PODS; do
        print_info "Logs from $POD:"
        kubectl logs $POD -n $NAMESPACE --tail=5 --previous 2>/dev/null || kubectl logs $POD -n $NAMESPACE --tail=5 2>/dev/null || true
        echo ""
    done

    print_highlight "\nDeduplication Test Points:"
    print_highlight "  - All nodes experience same CrashLoopBackOff"
    print_highlight "  - Events generated from multiple nodes"
    print_highlight "  - Same failure reason across all pods"
    print_highlight "  - Tests dedup grouping for DaemonSet workload type"

    print_error "\n${RED}SCREW-UP:INVALID_CONFIGMAP mode (DaemonSet) completed!${NC}"
    print_info "\nTo fix:"
    print_info "  1. Fix ConfigMap: kubectl edit configmap nginx-config -n $NAMESPACE"
    print_info "  2. Or run: ./$(basename $0) clean"
}

# Screw-up mode: Node-specific failures (resource exhaustion simulation)
screw_up_node_pressure() {
    print_info "=== Running SCREW-UP:NODE_PRESSURE mode (DaemonSet) ==="
    print_highlight "This mode simulates pods failing due to resource requests"
    create_namespace
    show_nodes

    print_info "\nCreating DaemonSet with excessive resource requests..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: $DAEMONSET_NAME
  namespace: $NAMESPACE
  labels:
    app: nginx-demo
spec:
  selector:
    matchLabels:
      app: nginx-demo
  template:
    metadata:
      labels:
        app: nginx-demo
        version: v1
    spec:
      tolerations:
      - key: node-role.kubernetes.io/control-plane
        operator: Exists
        effect: NoSchedule
      - key: node-role.kubernetes.io/master
        operator: Exists
        effect: NoSchedule
      containers:
      - name: nginx
        image: nginx:1.21
        ports:
        - containerPort: 80
          name: web
        resources:
          requests:
            # Excessive memory request - likely to cause scheduling issues
            memory: "32Gi"
            cpu: "8"
          limits:
            memory: "64Gi"
            cpu: "16"
EOF

    print_warning "Waiting 30 seconds for scheduling failures..."
    sleep 30

    print_error "\n=== DaemonSet Status (Scheduling Failures) ==="
    kubectl get daemonset $DAEMONSET_NAME -n $NAMESPACE

    print_error "\n=== Pods (showing Pending/Unschedulable) ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_warning "\n=== Pod Events (showing scheduling failures) ==="
    kubectl get events -n $NAMESPACE --field-selector reason=FailedScheduling --sort-by='.lastTimestamp' | tail -10

    print_highlight "\nDeduplication Test Points:"
    print_highlight "  - Pods may fail to schedule on different nodes"
    print_highlight "  - Each node may have different resource availability"
    print_highlight "  - Node qualifier helps track per-node incidents"

    print_error "\n${RED}SCREW-UP:NODE_PRESSURE mode (DaemonSet) completed!${NC}"
    print_info "\nTo fix: ./$(basename $0) clean"
}

# Screw-up mode: Liveness probe failures across nodes
screw_up_liveness_failure() {
    print_info "=== Running SCREW-UP:LIVENESS_FAILURE mode (DaemonSet) ==="
    print_highlight "This mode simulates liveness probe failures across all nodes"
    create_namespace
    show_nodes

    print_info "\nCreating DaemonSet with failing liveness probe..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: $DAEMONSET_NAME
  namespace: $NAMESPACE
  labels:
    app: nginx-demo
spec:
  selector:
    matchLabels:
      app: nginx-demo
  template:
    metadata:
      labels:
        app: nginx-demo
        version: v1
    spec:
      tolerations:
      - key: node-role.kubernetes.io/control-plane
        operator: Exists
        effect: NoSchedule
      - key: node-role.kubernetes.io/master
        operator: Exists
        effect: NoSchedule
      containers:
      - name: nginx
        image: nginx:1.21
        ports:
        - containerPort: 80
          name: web
        livenessProbe:
          httpGet:
            path: /healthz-nonexistent
            port: 80
          initialDelaySeconds: 5
          periodSeconds: 3
          failureThreshold: 2
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
          limits:
            memory: "128Mi"
            cpu: "200m"
EOF

    print_warning "Waiting 60 seconds for liveness failures across all nodes..."
    sleep 60

    print_error "\n=== DaemonSet Status ==="
    kubectl get daemonset $DAEMONSET_NAME -n $NAMESPACE

    print_error "\n=== Pods (showing restart counts from liveness failures) ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_warning "\n=== Pod Events (Unhealthy/Killing) ==="
    kubectl get events -n $NAMESPACE --field-selector reason=Unhealthy --sort-by='.lastTimestamp' | tail -15

    print_highlight "\nDeduplication Test Points:"
    print_highlight "  - All nodes experience same liveness failure"
    print_highlight "  - Multiple Unhealthy events per pod per node"
    print_highlight "  - Tests high-frequency event deduplication"
    print_highlight "  - Node qualifier distinguishes per-node incidents"

    print_error "\n${RED}SCREW-UP:LIVENESS_FAILURE mode (DaemonSet) completed!${NC}"
    print_info "\nTo fix: ./$(basename $0) clean"
}

# Clean mode: Remove all resources
clean_mode() {
    print_info "=== Running CLEAN mode (DaemonSet) ==="

    if kubectl get namespace "$NAMESPACE" &> /dev/null; then
        print_info "Deleting DaemonSet..."
        kubectl delete daemonset $DAEMONSET_NAME -n $NAMESPACE --ignore-not-found=true

        print_info "Deleting configmap..."
        kubectl delete configmap nginx-config -n $NAMESPACE --ignore-not-found=true

        print_info "Waiting for pods to terminate..."
        kubectl wait --for=delete pod -l app=nginx-demo -n $NAMESPACE --timeout=60s 2>/dev/null || true
        kubectl delete events --all -n $NAMESPACE

        print_warning "Keeping namespace $NAMESPACE (delete manually if needed)"

        print_info "${GREEN}Cleanup completed!${NC}"
    else
        print_info "Namespace $NAMESPACE doesn't exist, nothing to clean"
    fi
}

# Show usage
usage() {
    cat <<EOF
Usage: $0 [MODE]

Kubernetes DaemonSet Rollout Simulator
For testing deduplicated incident feature with DaemonSet workloads

MODES:
  normal                      - Create healthy DaemonSet with rolling update
  screw-up:image_failed      - Simulate ImagePullBackOff (non-existent image)
  screw-up:invalid_configmap - Simulate CrashLoopBackOff (broken nginx config)
  screw-up:node_pressure     - Simulate scheduling failures (resource exhaustion)
  screw-up:liveness_failure  - Simulate liveness probe failures across nodes
  clean                      - Remove all created resources

EXAMPLES:
  $0 normal                          # Healthy DaemonSet with rollout
  $0 screw-up:image_failed          # Failed image pull scenario
  $0 screw-up:invalid_configmap     # Config error causing crashes
  $0 screw-up:node_pressure         # Resource scheduling failures
  $0 screw-up:liveness_failure      # Liveness probe cascade failures
  $0 clean                          # Clean up everything

DAEMONSET CHARACTERISTICS:
  - One pod per node in the cluster
  - Pod names: ${DAEMONSET_NAME}-xxxxx (random suffix, NOT stable)
  - Node name can be used as deduplication qualifier
  - Pods scheduled based on node selectors/tolerations
  - Useful for node-level monitoring, logging, networking

DEDUPLICATION FINGERPRINT:
  {ownerName}|{namespace}|{failureReason}|DAEMONSET|{nodeName}|{timeWindow}
  Example: nginx-demo|horizon|CrashLoopBackOff|DAEMONSET|worker-1|2025-12-10T10:00

NAMESPACE: $NAMESPACE

TROUBLESHOOTING COMMANDS:
  # Check DaemonSet status
  kubectl get daemonset $DAEMONSET_NAME -n $NAMESPACE

  # Check pod status with node info
  kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

  # View pods on specific node
  kubectl get pods -n $NAMESPACE -l app=nginx-demo --field-selector spec.nodeName=NODE_NAME

  # View pod logs
  kubectl logs -l app=nginx-demo -n $NAMESPACE --tail=20

  # Rollback DaemonSet
  kubectl rollout undo daemonset/$DAEMONSET_NAME -n $NAMESPACE

  # Check rollout history
  kubectl rollout history daemonset/$DAEMONSET_NAME -n $NAMESPACE

  # View node resources
  kubectl describe nodes | grep -A5 "Allocated resources"

EOF
}

# Main
main() {
    check_kubectl

    case "${1:-}" in
        normal)
            normal_mode
            ;;
        screw-up:image_failed)
            screw_up_image_failed
            ;;
        screw-up:invalid_configmap)
            screw_up_invalid_configmap
            ;;
        screw-up:node_pressure)
            screw_up_node_pressure
            ;;
        screw-up:liveness_failure)
            screw_up_liveness_failure
            ;;
        clean)
            clean_mode
            ;;
        -h|--help|help)
            usage
            ;;
        *)
            print_error "Invalid mode: ${1:-[none]}"
            echo ""
            usage
            exit 1
            ;;
    esac
}
main "$@"

