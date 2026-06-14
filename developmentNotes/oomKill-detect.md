# SRE Agent — Feature Instruction Spec
## OOMKill Autonomous Remediation + Break-Glass Emergency Path

**Version:** 0.1.0  
**Framework:** Embabel 0.4.0 (Spring Boot, GOAP-based)  
**Status:** R&D / Prototype

---

## 1. Scope

This spec covers two features:

**Feature A — OOMKill Remediation (Normal Path)**  
Agent autonomously detects OOMKilled containers, proposes a resource increase, verifies
scheduler feasibility, requests TOTP approval from DevOps, then commits the manifest
change and triggers an ArgoCD sync.

**Feature B — Break-Glass Emergency Path**  
DevOps initiates from phone (no laptop). Agent patches the live Kubernetes manifest
directly, suspends ArgoCD reconciliation for the target Application, and tracks the
open incident until an official Git commit resolves it.

---

## 2. Invariants (Non-Negotiable)

These constraints apply to every action in both features. Any implementation that
violates them is incorrect regardless of other functionality.

| # | Invariant |
|---|-----------|
| I-1 | No mutating action executes without a valid, unexpired TOTP confirmation |
| I-2 | TOTP challenge IDs are single-use and expire after 5 minutes |
| I-3 | Every mutating action records a full `RemediationPlan` or `BreakGlassRecord` before executing |
| I-4 | The LLM makes bounded reasoning decisions only — all cluster arithmetic is deterministic Java |
| I-5 | ArgoCD sync is never triggered without an explicit DevOps approval tied to that specific plan |
| I-6 | Infeasibility blocks TOTP request — agent does not ask for approval on a plan that will fail |
| I-7 | Break-glass suspension has a maximum TTL (default 4h). Agent escalates on expiry — never auto-resumes |
| I-8 | Git-covers-live-patch check is mandatory before ArgoCD reconciliation is restored |

---

## 3. Domain Object Model

All types are immutable records unless noted. GOAP planner works exclusively on typed
domain objects — no raw Maps or untyped JSON in action signatures.

### 3.1 Shared

```
Signal (sealed interface)
  OomKillSignal
    pod: String
    container: String
    namespace: String
    timestamp: Instant
    oomFrequencyLast7d: int          // how many OOMs in 7-day window

ResourceSnapshot
  currentRequests: ResourceQuantity  // { cpu, memory }
  currentLimits: ResourceQuantity
  nodeHeadroom: ResourceQuantity     // max(allocatable - requested) across schedulable nodes
  namespaceQuotaRemaining: ResourceQuantity  // nullable if no quota
  limitRangeMax: ResourceQuantity    // nullable if no LimitRange
  hpaConfig: HpaConfig               // nullable if no HPA
    minReplicas: int
    maxReplicas: int
    currentReplicas: int

ResourceMultiplier (enum)
  X1_25(1.25), X2(2.0)

ProposedResourceChange
  multiplier: ResourceMultiplier
  proposedLimits: ResourceQuantity
  proposedRequests: ResourceQuantity
  rationale: String                  // LLM-generated, human-facing only, not used in logic
  feasible: boolean
  feasibilityDetail: String          // deterministic, audit trail
  worstCaseHpaMemory: ResourceQuantity  // nullable; proposedMemory * hpa.maxReplicas

RemediationPlan
  id: UUID
  signal: Signal
  resourceSnapshot: ResourceSnapshot  // Feature A only
  proposedChange: ProposedResourceChange  // Feature A only
  challengeId: UUID
  requestedAt: Instant
  expiresAt: Instant                 // requestedAt + 5min
  approvedBy: String                 // nullable until approved
  approvedAt: Instant                // nullable until approved
  status: PENDING | APPROVED | EXPIRED | EXECUTED | FAILED
```

### 3.2 Feature B — Break-Glass Specific

```
BreakGlassRequest
  appName: String                    // ArgoCD Application name
  targetResource: KubernetesResourceRef  // { apiVersion, kind, namespace, name }
  proposedPatch: JsonPatch           // RFC 6902
  requestedBy: String
  requestedVia: NotificationChannel  // SLACK | PAGERDUTY | SMS
  requestedAt: Instant

BreakGlassRecord  (immutable once written)
  id: UUID
  request: BreakGlassRequest
  preSuspensionSyncPolicy: ArgoCdSyncPolicy  // exact snapshot for restore
  preChangeLiveManifest: String      // raw YAML snapshot
  postChangeLiveManifest: String     // filled after patch
  challengeId: UUID
  approvedAt: Instant
  status: ACTIVE | RESOLVED

BreakGlassIncident  (mutable — live state)
  record: BreakGlassRecord
  suspendedAt: Instant
  suspensionTtl: Duration            // default 4h
  remindersSent: int
  divergedFields: List<JsonPointer>  // fields that differ from Git HEAD
  resolvedAt: Instant                // nullable until resolved
  resolvedBy: String                 // nullable until resolved
  resolvedVia: String                // nullable; Git commit SHA
```

---

## 4. Feature A — OOMKill Remediation

### 4.1 Flow

```
OBSERVE
  Source: K8s Event watch (filtered: reason=OOMKilling, type=Warning)
  On event: fetch enrichment (parallel)
    - Pod spec: current requests/limits
    - Node list: allocatable and requested per node
    - Namespace ResourceQuota (if exists)
    - Namespace LimitRange (if exists)
    - HPA targeting this Deployment (if exists)
    - OOM event count last 7 days (K8s Events API)
  Assemble: OomKillSignal + ResourceSnapshot

ORIENT  (LLM call — bounded)
  Input:  ResourceSnapshot + OomKillSignal (structured, typed)
  Prompt: decide multiplier — 1.25x vs 2x based on OOM frequency,
          proximity to limit, and recurrence pattern
  Output: ResourceMultiplier + rationale string
  Constraint: output schema is ResourceMultiplier enum only — no free-form numbers

DECIDE  (deterministic — no LLM)
  Compute ProposedResourceChange from ResourceSnapshot + ResourceMultiplier
  Run FeasibilityChecker (see 4.3):
    All three checks must pass — fail any = feasible: false
  If infeasible:
    notify DevOps with specific blocking constraint
    halt — do not request TOTP
  If feasible:
    Build RemediationPlan (status=PENDING)
    Notify DevOps: full plan summary + blast radius (see 4.4 Notification Contract)
    Issue TOTP challenge

ACT  (gated — otpConfirmed @Condition)
  Precondition: RemediationPlan.status == APPROVED && Instant.now() < plan.expiresAt
  Step 1: Commit resource patch to Git (see 4.5)
  Step 2: POST /api/v1/applications/{name}/sync (ArgoCD REST API)
          body: { revision: explicit_commit_sha, dryRun: false, prune: false }
  Step 3: Poll operationState.phase until terminal (Succeeded|Failed|Error)
          timeout: 10 minutes
  Step 4: Verify no new OomKillSignal for target container within 10 min post-rollout
  Step 5: Update RemediationPlan status → EXECUTED or FAILED
  Notify DevOps with outcome
```

### 4.2 Embabel Action / Condition Decomposition

```
@Goal
  ResolveOomKill(signal: OomKillSignal)
    produces: RemediationOutcome

@Actions
  fetchResourceContext(signal: OomKillSignal) → ResourceSnapshot
    type: READ, autonomous
    source: K8s API (pods, nodes, resourcequotas, limitranges, HPA)

  proposeResourceChange(snapshot: ResourceSnapshot, signal: OomKillSignal)
      → ProposedResourceChange(feasible=unknown)
    type: LLM-BOUNDED
    llm role: decide ResourceMultiplier only
    post-process: compute exact quantities in Java, set feasible=unknown

  verifySchedulerFeasibility(proposed: ProposedResourceChange, snapshot: ResourceSnapshot)
      → ProposedResourceChange(feasible=true|false)
    type: DETERMINISTIC
    see: FeasibilityChecker (4.3)

  notifyAndRequestOtp(proposed: ProposedResourceChange, signal: OomKillSignal)
      → RemediationPlan(status=PENDING)
    type: NOTIFY + GATE REQUEST
    side-effect: issues TOTP challenge, sends notification

  commitResourcePatch(plan: RemediationPlan) → PatchCommit
    type: WRITE — gated
    mechanism: Git commit via JGit or GitHub API

  triggerArgoCdSync(commit: PatchCommit, plan: RemediationPlan) → SyncOperation
    type: WRITE — gated
    mechanism: POST /api/v1/applications/{name}/sync

  watchRollout(op: SyncOperation) → RolloutResult
    type: POLL — autonomous
    mechanism: poll operationState + Deployment rollout status

@Conditions
  isFeasible(proposed: ProposedResourceChange)
    → proposed.feasible == true

  otpConfirmed(plan: RemediationPlan)
    → plan.status == APPROVED && Instant.now().isBefore(plan.expiresAt)

  rolloutHealthy(result: RolloutResult)
    → result.phase == SUCCEEDED && result.noNewOomWithinTtl
```

### 4.3 FeasibilityChecker — All Three Must Pass

```
Check 1: Node headroom
  max over schedulable nodes of (node.allocatable.memory - node.requested.memory)
    >= proposed.proposedLimits.memory
  Failure message: "No schedulable node has sufficient memory headroom.
    Largest available: {X}Gi, required: {Y}Gi"

Check 2: Namespace ResourceQuota
  SKIP if no ResourceQuota exists in namespace
  quota.hard["limits.memory"] - quota.used["limits.memory"]
    >= delta(proposed.proposedLimits.memory - current.limits.memory)
  Failure message: "Namespace quota exhausted.
    Remaining: {X}Gi, required delta: {Y}Gi"

Check 3: LimitRange
  SKIP if no LimitRange exists in namespace
  proposed.proposedLimits.memory <= limitRange.max["memory"]
  Failure message: "Proposed memory {X}Gi exceeds LimitRange max {Y}Gi.
    Manifest will be rejected by apiserver."

Check 4: HPA worst-case (if HPA exists)
  worstCaseMemory = proposed.proposedLimits.memory * hpa.maxReplicas
  clusterHeadroom = sum over all nodes of (allocatable.memory - requested.memory)
  worstCaseMemory <= clusterHeadroom * 0.8  (20% safety margin)
  This check is WARNING only — does not block, but is always included in notification
  Warning message: "HPA worst-case: {X}Gi across {N} max replicas.
    Cluster headroom: {Y}Gi. Safety margin: {Z}%"
```

### 4.4 Notification Contract (Before TOTP Request)

The DevOps notification must include all of the following before a TOTP is issued.
Never send a TOTP challenge without this context.

```
Subject: [SRE Agent] OOMKill Remediation Approval Required — {namespace}/{pod}

Container:    {namespace}/{pod}/{container}
OOM Count:    {N} times in last 7 days (latest: {timestamp})

Current Limits:   CPU {X}m  |  Memory {Y}Mi
Proposed Limits:  CPU {X}m  |  Memory {Z}Mi  ({multiplier}x increase)

Feasibility:
  ✓ Node headroom:     {largest_node_headroom}Gi available
  ✓ Namespace quota:   {remaining}Gi remaining
  ✓ LimitRange:        within max {max}Gi
  ⚠ HPA worst-case:   {worst}Gi across {maxReplicas} replicas
                       (cluster headroom: {headroom}Gi)

Rationale: {llm_rationale}

Plan ID:   {plan_id}
Expires:   {expiresAt} (5 minutes)

Reply with TOTP code to approve.
```

### 4.5 Git Commit Strategy

Resource limit changes use Git commit, not live manifest patch.

```
Mechanism:
  JGit or GitHub REST API (prefer GitHub API to avoid SSH key management in-cluster)
  
Commit target:
  Branch: main (if GitOps policy allows agent commits)
  OR: feature/sre-agent/{plan_id} branch + auto-merge if CI passes

Commit message format:
  fix(resources): increase {container} limits {multiplier}x in {namespace}

  OOMKill detected {N} times in 7d.
  Plan-ID: {plan_id}
  Approved-By: {approvedBy}
  Approved-At: {approvedAt}

ArgoCD sync call:
  POST /api/v1/applications/{name}/sync
  {
    "revision": "{explicit_commit_sha}",  // never HEAD — use the exact commit SHA
    "prune": false,
    "strategy": { "apply": { "force": false } }
  }
```

---

## 5. Feature B — Break-Glass Emergency Path

### 5.1 Trigger

DevOps initiates from phone via a supported notification channel (Slack, PagerDuty, SMS).
The agent never initiates break-glass autonomously.

Accepted command format (parsed by LLM with bounded output):
```
break-glass {app-name} {natural language description of change}
```

Examples:
```
break-glass payment-service increase memory limit to 4Gi
break-glass auth-service set replicas to 1
```

LLM output schema (bounded):
```
BreakGlassIntent {
  appName: String
  targetResource: KubernetesResourceRef
  proposedPatch: JsonPatch               // RFC 6902 patch ops
  parsedFromMessage: String              // echo back for confirmation
}
```

If intent cannot be confidently parsed → agent requests clarification, does not proceed.

### 5.2 Flow

```
PARSE
  Receive message on notification channel
  LLM parses → BreakGlassIntent (bounded output)
  If confidence < threshold → request clarification

PREPARE  (all read — autonomous)
  Fetch live manifest from K8s API (ground truth, not Git)
  Fetch ArgoCD Application spec (current syncPolicy — snapshot for restore)
  Check: is ArgoCD operation in-flight?
    If yes → include "will abort in-flight operation" in notification
  Compute diff: current live → proposed patch (field-level)
  Build BreakGlassRecord(status=PENDING)

CONFIRM  (before TOTP)
  Notify DevOps with full BreakGlass notification (see 5.4)
  Issue TOTP challenge

ACT  (gated — breakGlassOtpConfirmed @Condition)
  Precondition: BreakGlassRecord confirmed && not expired

  Step 1: Suspend ArgoCD (must be first — see 5.3)
  Step 2: Abort in-flight ArgoCD operation if any
          DELETE /api/v1/applications/{name}/operation
  Step 3: PATCH live resource via K8s API
  Step 4: Re-fetch live resource, verify patch applied
  Step 5: Record postChangeLiveManifest in BreakGlassRecord
  Step 6: Set BreakGlassRecord.status = ACTIVE
  Step 7: Create BreakGlassIncident (suspendedAt = now, ttl = 4h)
  Step 8: Notify DevOps — execution summary + suspension expiry

POST-ACT (ongoing until resolved)
  Schedule reminder job: every {ttl/2} until RESOLVED
    "App {name} still suspended. Live manifest diverges from Git.
     Commit official fix + send 'resolve break-glass {name}' to resume."
  On suspensionTtl expiry without resolution → escalate (different channel/pager)
  Do NOT auto-resume ArgoCD
```

### 5.3 ArgoCD Suspension — Correct Mechanism

Order of operations is strict. Suspend before patch — a race between patch and
ArgoCD reconcile loop (even without autoSync, a manual refresh can trigger apply)
is eliminated by suspending first.

```
Mechanism:
  PATCH Application CR via K8s API (CRD write):
    spec.syncPolicy → null  (removes automated block entirely)

  Then clear in-flight operation:
    DELETE /api/v1/applications/{name}/operation  (ArgoCD REST API)
    (no-op if no operation running — safe to always call)

Restore (used in Resolution path only):
  Re-apply spec.syncPolicy from BreakGlassRecord.preSuspensionSyncPolicy
  This is the exact snapshot taken before suspension — not a default config
```

### 5.4 Break-Glass Notification Contract

```
Subject: [SRE Agent] ⚠ BREAK-GLASS Approval Required — {app-name}

Requested by:  {requestedBy} via {requestedVia}
Target app:    {appName}
Resource:      {kind}/{namespace}/{name}

Proposed change (diff):
  {field_path}: {old_value} → {new_value}
  ...

ArgoCD impact:
  ✗ Reconciliation will be SUSPENDED for {appName}
  ✗ App will show OutOfSync until official commit + resume
  {if in-flight operation: "✗ In-flight operation will be aborted"}

Suspension TTL: {suspendedAt + 4h}

This is a live manifest change. Git is not updated.
You must commit an official fix and send 'resolve break-glass {app-name}' to restore.

Plan ID:   {plan_id}
Expires:   {expiresAt} (5 minutes)

Reply with TOTP code to approve.
```

### 5.5 Embabel Action / Condition Decomposition

```
@Goal
  ExecuteBreakGlass(request: BreakGlassRequest)
    produces: BreakGlassIncident

@Actions
  parseBreakGlassIntent(message: String, channel: NotificationChannel)
      → BreakGlassRequest
    type: LLM-BOUNDED
    output schema: BreakGlassIntent only

  fetchBreakGlassContext(request: BreakGlassRequest) → BreakGlassContext
    type: READ — autonomous
    fetches: live manifest, ArgoCD app spec, in-flight operation status

  buildBreakGlassDiff(ctx: BreakGlassContext) → BreakGlassRecord(status=PENDING)
    type: DETERMINISTIC
    computes: field-level diff, records preSuspensionSyncPolicy

  notifyAndRequestBreakGlassOtp(record: BreakGlassRecord) → ChallengeIssued
    type: NOTIFY + GATE REQUEST

  suspendArgoCdReconciliation(record: BreakGlassRecord) → ArgoCdSuspended
    type: WRITE — gated
    mechanism: K8s API PATCH on Application CR (syncPolicy → null)
               DELETE /api/v1/applications/{name}/operation

  patchLiveManifest(suspended: ArgoCdSuspended, record: BreakGlassRecord)
      → LivePatchApplied
    type: WRITE — gated
    mechanism: K8s API PATCH on target resource

  recordAndActivate(patch: LivePatchApplied) → BreakGlassIncident(status=ACTIVE)
    type: STATE — autonomous post-act

@Goal
  ResolveBreakGlass(incident: BreakGlassIncident)
    produces: BreakGlassIncident(status=RESOLVED)

@Actions
  checkGitCoversLivePatch(incident: BreakGlassIncident) → GitCoverageResult
    type: DETERMINISTIC
    see: Git Coverage Check (5.6)

  restoreArgoCdSyncPolicy(incident: BreakGlassIncident) → ArgoCdResumed
    type: WRITE
    mechanism: K8s API PATCH — restore exact preSuspensionSyncPolicy snapshot

  triggerSyncAndWatchResolution(resumed: ArgoCdResumed) → RolloutResult
    type: WRITE + POLL

@Conditions
  breakGlassOtpConfirmed(record: BreakGlassRecord)
    → record confirmed && Instant.now().isBefore(record.challengeExpiry)

  argoCdIsSuspended(appName: String)
    → Application CR spec.syncPolicy is null

  gitCoversLivePatch(incident: BreakGlassIncident)
    → GitCoverageResult.allDivergedFieldsCovered == true
```

### 5.6 Git Coverage Check — Mandatory Before Resume

```
Algorithm:
  1. Fetch Git HEAD manifest for target resource (from ArgoCD repo-server or direct Git)
  2. Fetch current live manifest from K8s API
  3. For each field in incident.divergedFields (recorded at break-glass time):
       gitValue = get(gitHeadManifest, fieldPath)
       liveValue = get(liveManifest, fieldPath)
       covered = (gitValue == liveValue)
  4. allDivergedFieldsCovered = covered for all fields

Failure case:
  If NOT covered:
    notify: "Git HEAD does not yet reflect live change.
             Field {fieldPath}: live={liveValue}, git={gitValue}.
             Syncing now will REVERT the emergency fix and cause a second incident.
             Commit the official fix first."
    Block resume — do not proceed

Note: This check is deterministic field comparison — no LLM involvement.
```

---

## 6. TOTP Gate — Implementation Contract

Both features use the same gate. Consistency is mandatory.

### 6.1 TOTP Verification

```
Production: Delegate to Azure AD / Entra ID MFA
  - Agent triggers MFA challenge on operator's registered identity
  - Operator approves in Microsoft Authenticator
  - Agent receives confirmation callback or polls for result
  - Tie challenge to plan ID in the MFA request context

R&D fallback: Standalone RFC 6238 TOTP
  Library: dev.samstevens.totp:totp
  Shared secret: per-operator, stored in K8s Secret (never in config file)
  Verification: submitted code + operator secret + current time window
  Window: ±1 step (30s) tolerance

Never implement: custom TOTP algorithm, SMS codes without MFA enrollment,
  shared team codes
```

### 6.2 Challenge Lifecycle

```
Issue:
  challengeId = UUID.randomUUID()
  store: { challengeId, planId, operatorId, issuedAt, expiresAt = issuedAt + 5min }
  persist: in-memory is insufficient — use Redis or K8s Secret (short-lived)

Confirm:
  validate TOTP code against operatorId
  mark challengeId as CONSUMED (not just expired)
  update RemediationPlan or BreakGlassRecord status → APPROVED
  record approvedBy, approvedAt

Rules:
  One challenge per plan — cannot reuse a challenge for a different plan
  One use — CONSUMED challenges cannot be re-submitted
  Expiry — expired challenges cannot be confirmed even with correct TOTP
  Replan — GOAP replan after new signal does not inherit prior approval
```

---

## 7. ArgoCD Client — Interface Contract

Two separate clients. Never conflate.

### 7.1 ArgoCdRestClient

```
Interface:
  ApplicationStatus getApplicationStatus(String appName)
  ResourceTree getResourceTree(String appName)
  SyncOperation triggerSync(String appName, ArgoCdSyncRequest request)
  OperationState getOperationState(String appName)
  void abortOperation(String appName)
  void suspendApplication(String appName)   // patches syncPolicy → null via REST
  ApplicationSpec getApplicationSpec(String appName)

Config:
  base-url: ${ARGOCD_URL}
  token: ${ARGOCD_TOKEN}  // from K8s Secret mount
  tls-insecure-skip-verify: false  // always verify in prod

Implementation:
  WebClient (non-blocking) — required for async sync polling
  Default timeout: 30s per request
  Retry: 3 attempts with exponential backoff on 5xx (not on 4xx)
  Auth: Authorization: Bearer {token} header on all requests
```

### 7.2 KubernetesApiClient

```
Interface:
  <T> T getResource(KubernetesResourceRef ref, Class<T> type)
  <T> T patchResource(KubernetesResourceRef ref, JsonPatch patch, Class<T> type)
  List<Node> listSchedulableNodes()
  Optional<ResourceQuota> getNamespaceQuota(String namespace)
  Optional<LimitRange> getNamespaceLimitRange(String namespace)
  Optional<HorizontalPodAutoscaler> getHpa(String namespace, String deploymentName)
  List<CoreV1Event> getOomEvents(String namespace, String podName, Instant since)
  Deployment getDeployment(String namespace, String name)

Implementation:
  fabric8 Kubernetes client or official Java client
  ServiceAccount token: auto-mounted at /var/run/secrets/kubernetes.io/serviceaccount/
  RBAC: see Section 8
```

---

## 8. RBAC Requirements

### 8.1 Kubernetes ServiceAccount (sre-agent-sa)

```yaml
# Read-only ClusterRole — used by all diagnosis actions
rules:
  - apiGroups: [""]
    resources: [pods, nodes, events, resourcequotas, limitranges, namespaces]
    verbs: [get, list, watch]
  - apiGroups: [apps]
    resources: [deployments, replicasets, statefulsets]
    verbs: [get, list, watch]
  - apiGroups: [autoscaling]
    resources: [horizontalpodautoscalers]
    verbs: [get, list, watch]
  - apiGroups: [argoproj.io]
    resources: [applications]
    verbs: [get, list, watch]

# Write Role — scoped to specific namespaces only, not cluster-wide
# Applied as RoleBinding, not ClusterRoleBinding
rules:
  - apiGroups: [apps]
    resources: [deployments]
    verbs: [patch]                    # break-glass live patch only
  - apiGroups: [argoproj.io]
    resources: [applications]
    verbs: [patch]                    # suspend syncPolicy only
```

### 8.2 ArgoCD Local Account (sre-agent)

```yaml
# argocd-cm ConfigMap
accounts.sre-agent: apiKey

# argocd-rbac-cm ConfigMap
p, role:sre-agent-read, applications, get,    */*, allow
p, role:sre-agent-read, applications, list,   */*, allow

# Sync role — scope to specific project, not */*
p, role:sre-agent-sync, applications, sync,   {project}/*, allow
p, role:sre-agent-sync, applications, get,    {project}/*, allow

# Operation abort
p, role:sre-agent-sync, applications, delete, {project}/*, allow

g, sre-agent, role:sre-agent-read
g, sre-agent, role:sre-agent-sync
```

---

## 9. Audit Trail Requirements

Every execution (normal or break-glass) must produce an immutable audit record.

```
AuditRecord
  id: UUID
  type: OOMKILL_REMEDIATION | BREAK_GLASS
  triggeredBy: AGENT (for Feature A) | {operator_id} (for Feature B)
  triggeredAt: Instant
  planId: UUID
  challengeId: UUID
  approvedBy: String
  approvedAt: Instant
  actionsExecuted: List<AuditAction>
    { actionName, startedAt, completedAt, outcome, details }
  outcome: SUCCEEDED | FAILED | EXPIRED | ROLLED_BACK
  completedAt: Instant
```

Persistence: append-only. Records are never updated after write.
Storage: K8s ConfigMap (R&D) → external audit log service (production).

---

## 10. Notification Channels

```
Supported: Slack (primary), PagerDuty (escalation), SMS (break-glass receive only)

Channel usage:
  OOMKill detected:            Slack #sre-alerts
  TOTP request:                Slack DM to on-call operator + PagerDuty
  Break-glass receive:         Slack DM or PagerDuty alert message
  Suspension expiry warning:   PagerDuty (escalate if Slack ignored)
  Resolution confirmed:        Slack #sre-alerts

All notifications include Plan ID for correlation.
```

---

## 11. Out of Scope (This Spec)

- Multi-container pods (single container OOM only for now)
- VPA (Vertical Pod Autoscaler) integration — future iteration
- Rollback of resource increase if new OOM occurs post-remediation
- Multi-cluster ArgoCD (single cluster assumed)
- Break-glass on StatefulSet or DaemonSet (Deployment only)
- Automatic Git PR creation (direct commit to main assumed with appropriate GitOps policy)

---

## 12. Open Questions

| # | Question | Default Assumption |
|---|----------|--------------------|
| Q-1 | TOTP provider: Azure AD/Entra or standalone RFC 6238? | Standalone for R&D, Entra for prod |
| Q-2 | Git write mechanism: JGit in-cluster or GitHub API? | GitHub REST API (avoids SSH key management) |
| Q-3 | Suspension TTL: configurable per-app or global? | Global default 4h, per-app override via annotation |
| Q-4 | Reminder frequency during suspension: every 2h or adaptive? | Every ttl/2 (2h for 4h TTL) |
| Q-5 | Should infeasibility trigger a pager or Slack-only? | Slack-only (not actionable without human decision) |
| Q-6 | HPA worst-case check: hard block or warning? | Warning only — operator sees it in notification |