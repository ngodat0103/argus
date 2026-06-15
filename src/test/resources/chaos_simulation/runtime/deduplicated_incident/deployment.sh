#!/bin/bash

# Kubernetes Deployment Rollout Simulator
# Simulates various deployment scenarios with multiple ReplicaSets

set -e

NAMESPACE="horizon"
DEPLOYMENT_NAME="nginx-demo"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
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

# Normal mode: Create a healthy deployment
normal_mode() {
    print_info "=== Running NORMAL mode ==="
    create_namespace

    print_info "Creating initial nginx deployment..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: $DEPLOYMENT_NAME
  namespace: $NAMESPACE
spec:
  replicas: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 1
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
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
          limits:
            memory: "128Mi"
            cpu: "200m"
EOF

    print_info "Waiting for initial deployment to be ready..."
    kubectl rollout status deployment/$DEPLOYMENT_NAME -n $NAMESPACE

    print_info "Performing rolling update to nginx:1.22..."
    kubectl set image deployment/$DEPLOYMENT_NAME nginx=nginx:1.22 -n $NAMESPACE

    print_info "Watching rollout progress..."
    kubectl rollout status deployment/$DEPLOYMENT_NAME -n $NAMESPACE

    print_info "\n=== Deployment Status ==="
    kubectl get deployment $DEPLOYMENT_NAME -n $NAMESPACE

    print_info "\n=== ReplicaSets (showing multiple versions) ==="
    kubectl get rs -n $NAMESPACE -l app=nginx-demo

    print_info "\n=== Pods ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo

    print_info "\n${GREEN}NORMAL mode completed successfully!${NC}"
    print_info "You now have multiple ReplicaSets (old and new versions)"
}

# Screw-up mode: Image failed - non-existent image
screw_up_image_failed() {
    print_info "=== Running SCREW-UP:IMAGE_FAILED mode ==="
    create_namespace

    print_info "Creating initial working nginx deployment..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: $DEPLOYMENT_NAME
  namespace: $NAMESPACE
spec:
  replicas: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 1
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
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
          limits:
            memory: "128Mi"
            cpu: "200m"
EOF

    print_info "Waiting for initial deployment to be ready..."
    kubectl rollout status deployment/$DEPLOYMENT_NAME -n $NAMESPACE --timeout=60s

    print_warning "Now deploying with BROKEN configuration (non-existent image)..."
    kubectl set image deployment/$DEPLOYMENT_NAME nginx=nginx:nonexistent-tag-9999 -n $NAMESPACE

    print_warning "Waiting 30 seconds for failed rollout to be visible..."
    sleep 30

    print_error "\n=== Deployment Status (ImagePullBackOff) ==="
    kubectl get deployment $DEPLOYMENT_NAME -n $NAMESPACE

    print_error "\n=== ReplicaSets (multiple versions - old working, new failing) ==="
    kubectl get rs -n $NAMESPACE -l app=nginx-demo -o wide

    print_error "\n=== Pods (showing ImagePullBackOff errors) ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_warning "\n=== Rollout History ==="
    kubectl rollout history deployment/$DEPLOYMENT_NAME -n $NAMESPACE

    print_error "\n${RED}SCREW-UP:IMAGE_FAILED mode completed!${NC}"
    print_info "The deployment is stuck with ImagePullBackOff:"
    print_info "  - Old ReplicaSet (working): Still running"
    print_info "  - New ReplicaSet (broken): ImagePullBackOff"
    print_info "\nTo fix this, run: ./$(basename $0) clean"
    print_info "Or manually rollback: kubectl rollout undo deployment/$DEPLOYMENT_NAME -n $NAMESPACE"
}

# Screw-up mode: Invalid ConfigMap - causes CrashLoopBackOff
screw_up_invalid_configmap() {
    print_info "=== Running SCREW-UP:INVALID_CONFIGMAP mode ==="
    create_namespace

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
          return 200 'Hello from Nginx!';
          add_header Content-Type text/plain;
        }
      }
    }
EOF

    print_info "Creating initial working nginx deployment with ConfigMap..."
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: $DEPLOYMENT_NAME
  namespace: $NAMESPACE
spec:
  replicas: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 1
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

    print_info "Waiting for initial deployment to be ready..."
    kubectl rollout status deployment/$DEPLOYMENT_NAME -n $NAMESPACE --timeout=60s

    print_info "Testing the working deployment..."
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

    print_warning "Forcing deployment restart to pick up new config..."
    kubectl rollout restart deployment/$DEPLOYMENT_NAME -n $NAMESPACE

    print_warning "Waiting 45 seconds for CrashLoopBackOff to appear..."
    sleep 45

    print_error "\n=== Deployment Status (CrashLoopBackOff) ==="
    kubectl get deployment $DEPLOYMENT_NAME -n $NAMESPACE

    print_error "\n=== ReplicaSets (multiple versions due to restart) ==="
    kubectl get rs -n $NAMESPACE -l app=nginx-demo -o wide

    print_error "\n=== Pods (showing CrashLoopBackOff) ==="
    kubectl get pods -n $NAMESPACE -l app=nginx-demo -o wide

    print_warning "\n=== Check pod logs for nginx config errors ==="
    POD=$(kubectl get pods -n $NAMESPACE -l app=nginx-demo --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
    if [ -n "$POD" ]; then
        print_info "Sample logs from pod: $POD"
        kubectl logs $POD -n $NAMESPACE --tail=20 || true
    else
        print_warning "No running pods found (all in CrashLoopBackOff)"
        POD=$(kubectl get pods -n $NAMESPACE -l app=nginx-demo -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
        if [ -n "$POD" ]; then
            print_info "Sample logs from crashing pod: $POD"
            kubectl logs $POD -n $NAMESPACE --tail=20 --previous 2>/dev/null || kubectl logs $POD -n $NAMESPACE --tail=20 || true
        fi
    fi

    print_error "\n${RED}SCREW-UP:INVALID_CONFIGMAP mode completed!${NC}"
    print_info "The deployment is crashing due to invalid nginx config:"
    print_info "  - Pods repeatedly crash with CrashLoopBackOff"
    print_info "  - Nginx fails to start due to malformed config"
    print_info "\nTo fix this:"
    print_info "  1. Fix the ConfigMap: kubectl edit configmap nginx-config -n $NAMESPACE"
    print_info "  2. Or run: ./$(basename $0) clean"
}

# Clean mode: Remove all resources
clean_mode() {
    print_info "=== Running CLEAN mode ==="

    if kubectl get namespace "$NAMESPACE" &> /dev/null; then
        print_info "Deleting deployment..."
        kubectl delete deployment $DEPLOYMENT_NAME -n $NAMESPACE --ignore-not-found=true

        print_info "Deleting configmap..."
        kubectl delete configmap nginx-config -n $NAMESPACE --ignore-not-found=true

        print_info "Waiting for pods to terminate..."
        kubectl wait --for=delete pod -l app=nginx-demo -n $NAMESPACE --timeout=60s 2>/dev/null || true

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

Kubernetes Deployment Rollout Simulator

MODES:
  normal                      - Create healthy deployment with rolling update
  screw-up:image_failed      - Simulate ImagePullBackOff (non-existent image)
  screw-up:invalid_configmap - Simulate CrashLoopBackOff (broken nginx config)
  clean                      - Remove all created resources

EXAMPLES:
  $0 normal                          # Healthy deployment with rollout
  $0 screw-up:image_failed          # Failed image pull scenario
  $0 screw-up:invalid_configmap     # Config error causing crashes
  $0 clean                          # Clean up everything

NAMESPACE: $NAMESPACE

TROUBLESHOOTING COMMANDS:
  # Check deployment status
  kubectl get deployment $DEPLOYMENT_NAME -n $NAMESPACE

  # View all ReplicaSets
  kubectl get rs -n $NAMESPACE -l app=nginx-demo

  # Check pod status
  kubectl get pods -n $NAMESPACE -l app=nginx-demo

  # View pod logs (replace POD_NAME)
  kubectl logs POD_NAME -n $NAMESPACE
  kubectl logs POD_NAME -n $NAMESPACE --previous

  # Rollback deployment
  kubectl rollout undo deployment/$DEPLOYMENT_NAME -n $NAMESPACE
  kubectl rollout status deployment/$DEPLOYMENT_NAME -n $NAMESPACE

  # Fix ConfigMap issue
  kubectl edit configmap nginx-config -n $NAMESPACE

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