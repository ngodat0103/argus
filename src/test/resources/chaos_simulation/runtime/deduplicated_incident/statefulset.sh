#!/bin/bash

# Kubernetes StatefulSet Rollout Simulator
# Simulates various StatefulSet scenarios for deduplication testing
# StatefulSets have stable pod names (nginx-demo-0, nginx-demo-1, etc.)
# and stable network identities - useful for testing pod ordinal qualifiers

set -e

NAMESPACE="horizon"
STATEFULSET_NAME="nginx-demo"
SERVICE_NAME="nginx-demo-headless"

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

# Create headless service required for StatefulSet
create_headless_service() {
    print_info "Creating headless service for StatefulSet..."
    cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Service
metadata:
  name: $SERVICE_NAME
  namespace: $NAMESPACE
  labels:
    app: nginx-demo
spec:
  ports:
  - port: 80
    name: web
  clusterIP: None
  selector:
    app: nginx-demo
EOF
}

# Normal mode: Create a healthy StatefulSet
normal_mode() {
    print_info "=== Running NORMAL mode (StatefulSet) ==="
    create_namespace
    create_headless_service

    print_info "Creating initial nginx StatefulSet..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: $STATEFULSET_NAME
  namespace: $NAMESPACE
spec:
  serviceName: "$SERVICE_NAME"
  replicas: 3
  updateStrategy:
    type: RollingUpdate
  selector:
    matchLabels:
      app: nginx-demo
  template:
    metadata:
      labels:
        app: nginx-demo
        version: v1
    spec:
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

    print_info "Waiting for StatefulSet to be ready..."
    kubectl rollout status statefulset/$STATEFULSET_NAME -n $NAMESPACE

    print_info "Performing rolling update to nginx:1.22..."
    kubectl set image statefulset/$STATEFULSET_NAME nginx=nginx:1.22 -n $NAMESPACE

    print_info "Watching rollout progress..."
    kubectl rollout status statefulset/$STATEFULSET_NAME -n $NAMESPACE

    print_info "\n=== StatefulSet Status ==="
    kubectl get statefulset $STATEFULSET_NAME -n $NAMESPACE

    print_info "\n=== Pods (showing stable ordinal names: nginx-demo-0, nginx-demo-1, etc.) ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_highlight "\nStatefulSet pods have STABLE identities:"
    print_highlight "  - ${STATEFULSET_NAME}-0, ${STATEFULSET_NAME}-1, ${STATEFULSET_NAME}-2"
    print_highlight "  - Each pod ordinal can be used as a qualifier for deduplication"

    print_info "\n${GREEN}NORMAL mode (StatefulSet) completed successfully!${NC}"
}

# Screw-up mode: Image failed - non-existent image
screw_up_image_failed() {
    print_info "=== Running SCREW-UP:IMAGE_FAILED mode (StatefulSet) ==="
    create_namespace
    create_headless_service

    print_info "Creating initial working nginx StatefulSet..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: $STATEFULSET_NAME
  namespace: $NAMESPACE
spec:
  serviceName: "$SERVICE_NAME"
  replicas: 3
  updateStrategy:
    type: RollingUpdate
  selector:
    matchLabels:
      app: nginx-demo
  template:
    metadata:
      labels:
        app: nginx-demo
        version: v1
    spec:
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

    print_info "Waiting for StatefulSet to be ready..."
    kubectl rollout status statefulset/$STATEFULSET_NAME -n $NAMESPACE --timeout=120s

    print_warning "Now updating with BROKEN image (non-existent tag)..."
    kubectl set image statefulset/$STATEFULSET_NAME nginx=nginx:nonexistent-tag-9999 -n $NAMESPACE

    print_warning "Waiting 30 seconds for failed rollout to be visible..."
    print_highlight "StatefulSet updates pods in REVERSE ordinal order (2, 1, 0)"
    sleep 30

    print_error "\n=== StatefulSet Status (ImagePullBackOff) ==="
    kubectl get statefulset $STATEFULSET_NAME -n $NAMESPACE

    print_error "\n=== Pods (showing ImagePullBackOff on highest ordinal first) ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_warning "\n=== Pod Events (showing image pull failures) ==="
    kubectl describe pod ${STATEFULSET_NAME}-2 -n $NAMESPACE 2>/dev/null | grep -A5 "Events:" || true

    print_highlight "\nDeduplication Test Points:"
    print_highlight "  - Multiple pods (${STATEFULSET_NAME}-0, -1, -2) may fail"
    print_highlight "  - Each pod has a unique ordinal qualifier"
    print_highlight "  - Fingerprint should include: workloadType=STATEFULSET"

    print_error "\n${RED}SCREW-UP:IMAGE_FAILED mode (StatefulSet) completed!${NC}"
    print_info "The StatefulSet is stuck with ImagePullBackOff"
    print_info "\nTo fix: ./$(basename $0) clean"
    print_info "Or rollback: kubectl rollout undo statefulset/$STATEFULSET_NAME -n $NAMESPACE"
}

# Screw-up mode: Invalid ConfigMap - causes CrashLoopBackOff
screw_up_invalid_configmap() {
    print_info "=== Running SCREW-UP:INVALID_CONFIGMAP mode (StatefulSet) ==="
    create_namespace
    create_headless_service

    print_info "Creating working nginx ConfigMap..."
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
          return 200 'Hello from StatefulSet Nginx!';
          add_header Content-Type text/plain;
        }
      }
    }
EOF

    print_info "Creating initial working nginx StatefulSet with ConfigMap..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: $STATEFULSET_NAME
  namespace: $NAMESPACE
spec:
  serviceName: "$SERVICE_NAME"
  replicas: 3
  updateStrategy:
    type: RollingUpdate
  selector:
    matchLabels:
      app: nginx-demo
  template:
    metadata:
      labels:
        app: nginx-demo
        version: v1
    spec:
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

    print_info "Waiting for StatefulSet to be ready..."
    kubectl rollout status statefulset/$STATEFULSET_NAME -n $NAMESPACE --timeout=120s

    print_info "Testing the working StatefulSet..."
    kubectl get pods -n $NAMESPACE -l app=nginx-demo

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

    print_warning "Forcing StatefulSet restart to pick up new config..."
    kubectl rollout restart statefulset/$STATEFULSET_NAME -n $NAMESPACE

    print_warning "Waiting 45 seconds for CrashLoopBackOff to appear..."
    print_highlight "StatefulSet pods restart in ordinal order during restart"
    sleep 45

    print_error "\n=== StatefulSet Status (CrashLoopBackOff) ==="
    kubectl get statefulset $STATEFULSET_NAME -n $NAMESPACE

    print_error "\n=== Pods (showing CrashLoopBackOff with stable names) ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_warning "\n=== Check pod logs for nginx config errors ==="
    for i in 0 1 2; do
        POD="${STATEFULSET_NAME}-${i}"
        if kubectl get pod $POD -n $NAMESPACE &>/dev/null; then
            print_info "Logs from $POD:"
            kubectl logs $POD -n $NAMESPACE --tail=10 --previous 2>/dev/null || kubectl logs $POD -n $NAMESPACE --tail=10 2>/dev/null || true
            echo ""
        fi
    done

    print_highlight "\nDeduplication Test Points:"
    print_highlight "  - All 3 pods (${STATEFULSET_NAME}-0, -1, -2) will crash"
    print_highlight "  - Same failure reason: CrashLoopBackOff"
    print_highlight "  - Should deduplicate to single incident per time window"
    print_highlight "  - Pod ordinal qualifiers may create separate fingerprints"

    print_error "\n${RED}SCREW-UP:INVALID_CONFIGMAP mode (StatefulSet) completed!${NC}"
    print_info "\nTo fix:"
    print_info "  1. Fix ConfigMap: kubectl edit configmap nginx-config -n $NAMESPACE"
    print_info "  2. Or run: ./$(basename $0) clean"
}

# Screw-up mode: Multiple ordinals failing independently
screw_up_cascade_failure() {
    print_info "=== Running SCREW-UP:CASCADE_FAILURE mode (StatefulSet) ==="
    print_highlight "This mode simulates staggered failures across ordinals"
    create_namespace
    create_headless_service

    print_info "Creating StatefulSet with liveness probe..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: $STATEFULSET_NAME
  namespace: $NAMESPACE
spec:
  serviceName: "$SERVICE_NAME"
  replicas: 3
  updateStrategy:
    type: RollingUpdate
  selector:
    matchLabels:
      app: nginx-demo
  template:
    metadata:
      labels:
        app: nginx-demo
        version: v1
    spec:
      containers:
      - name: nginx
        image: nginx:1.21
        ports:
        - containerPort: 80
          name: web
        livenessProbe:
          httpGet:
            path: /healthz
            port: 80
          initialDelaySeconds: 5
          periodSeconds: 5
          failureThreshold: 2
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
          limits:
            memory: "128Mi"
            cpu: "200m"
EOF

    print_info "Waiting for StatefulSet to be ready (pods will fail liveness)..."
    sleep 10

    print_warning "Pods will start failing liveness probes (no /healthz endpoint)..."
    print_warning "Waiting 60 seconds for cascade failures..."
    sleep 60

    print_error "\n=== StatefulSet Status (Liveness Failures) ==="
    kubectl get statefulset $STATEFULSET_NAME -n $NAMESPACE

    print_error "\n=== Pods (showing restart counts from liveness failures) ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_warning "\n=== Pod Events ==="
    kubectl get events -n $NAMESPACE --field-selector reason=Unhealthy --sort-by='.lastTimestamp' | tail -20

    print_highlight "\nDeduplication Test Points:"
    print_highlight "  - Pods fail at different times (staggered)"
    print_highlight "  - Multiple events within time window"
    print_highlight "  - Tests dedup window grouping for StatefulSet"

    print_error "\n${RED}SCREW-UP:CASCADE_FAILURE mode (StatefulSet) completed!${NC}"
    print_info "\nTo fix: ./$(basename $0) clean"
}

# Clean mode: Remove all resources
clean_mode() {
    print_info "=== Running CLEAN mode (StatefulSet) ==="

    if kubectl get namespace "$NAMESPACE" &> /dev/null; then
        print_info "Deleting StatefulSet..."
        kubectl delete statefulset $STATEFULSET_NAME -n $NAMESPACE --ignore-not-found=true

        print_info "Deleting headless service..."
        kubectl delete service $SERVICE_NAME -n $NAMESPACE --ignore-not-found=true

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

Kubernetes StatefulSet Rollout Simulator
For testing deduplicated incident feature with StatefulSet workloads

MODES:
  normal                      - Create healthy StatefulSet with rolling update
  screw-up:image_failed      - Simulate ImagePullBackOff (non-existent image)
  screw-up:invalid_configmap - Simulate CrashLoopBackOff (broken nginx config)
  screw-up:cascade_failure   - Simulate staggered liveness probe failures
  clean                      - Remove all created resources

EXAMPLES:
  $0 normal                          # Healthy StatefulSet with rollout
  $0 screw-up:image_failed          # Failed image pull scenario
  $0 screw-up:invalid_configmap     # Config error causing crashes
  $0 screw-up:cascade_failure       # Liveness probe cascade failures
  $0 clean                          # Clean up everything

STATEFULSET CHARACTERISTICS:
  - Stable pod names: ${STATEFULSET_NAME}-0, ${STATEFULSET_NAME}-1, ${STATEFULSET_NAME}-2
  - Ordered deployment/scaling (0 → 1 → 2)
  - Reverse order updates (2 → 1 → 0)
  - Pod ordinal can be used as deduplication qualifier

NAMESPACE: $NAMESPACE

TROUBLESHOOTING COMMANDS:
  # Check StatefulSet status
  kubectl get statefulset $STATEFULSET_NAME -n $NAMESPACE

  # Check pod status (note stable names)
  kubectl get pods -n $NAMESPACE -l app=nginx-demo

  # View specific pod logs
  kubectl logs ${STATEFULSET_NAME}-0 -n $NAMESPACE
  kubectl logs ${STATEFULSET_NAME}-0 -n $NAMESPACE --previous

  # Rollback StatefulSet
  kubectl rollout undo statefulset/$STATEFULSET_NAME -n $NAMESPACE

  # Check rollout history
  kubectl rollout history statefulset/$STATEFULSET_NAME -n $NAMESPACE

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
        screw-up:cascade_failure)
            screw_up_cascade_failure
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

