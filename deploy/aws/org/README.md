# Organization guardrails (service control policies)

Spending limits for the member accounts (`playground`, `personal-apps`). AWS has no hard spending
cap and billing data lags by hours, so the main protection is preventing expensive resources from
being created at all. SCPs never apply to the management account: run nothing there.

| File | Attach to | What it does |
|---|---|---|
| `guardrails.json` | both OUs, permanently | Mumbai (`ap-south-1`) only; instances up to `small` (t4g/t3/t3a); disks up to 30 GiB, no io1/io2; RDS micro only; denies NAT gateways, Elastic IPs, load balancers, reservations/savings plans, domain registration, Marketplace subscriptions and the usual expensive services (SageMaker, EKS, OpenSearch, Redshift, ElastiCache, MSK, EMR, …); members can't leave the organization or close themselves |
| `budget-breach-deny.json` | nothing; a budget action attaches it | Blocks new spend (launch/start instances, Lambda, ECS, RDS, Bedrock calls, …). Running resources keep running; the JavaClaw stack stops its own instance |

## Apply (console, management account)

1. **AWS Organizations → Policies → Service control policies → Enable.**
2. **Create policy** `guardrails`, paste `guardrails.json`. Attach it to the `Sandbox` and
   `Workloads` OUs, not to Root, so the management account stays clearly separate.
3. **Create policy** `budget-breach-deny`, paste `budget-breach-deny.json`. Don't attach it.
4. **Org-wide kill switch** (catches runaway spend in any member account):
   1. IAM → Roles → Create role → *AWS service* → **Budgets** → attach
      `AWSBudgetsActionsWithAWSResourceControlAccess` → name `budgets-kill-switch`.
   2. Budgets → Create budget → Customize → Cost budget `kill-switch`, monthly, a little above
      normal spend (e.g. $10), one *Actual* 80% email alert.
   3. In the same wizard, **Attach actions** → role `budgets-kill-switch` → *Service Control
      Policy* → `budget-breach-deny` → targets `Workloads` and `Sandbox` → 100% *Actual* →
      **Automatically run**.

   It blocks new spend but leaves running resources alone. Stopping the JavaClaw instance is done
   by the stack's own kill switch (`KillSwitchUsd`, `AlertEmail` in `javaclaw.yaml`), because a
   budget action can stop instances only in its own account. After it fires, detach the SCP by
   hand (Policies → `budget-breach-deny` → Targets → Detach) once you've looked at the bill;
   the scheduler can't start the instance while it's attached.

To relax a rule for an experiment (say, a load balancer for a day), edit the policy in the
console, then put it back.
