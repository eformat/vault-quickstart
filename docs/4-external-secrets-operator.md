# External Secrets Operator

```bash
-- External Secrets Operator

cat <<EOF | oc apply -f-
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  labels:
    operators.coreos.com/external-secrets-operator.openshift-operators: ""
  name: external-secrets-operator
  namespace: openshift-operators
spec:
  channel: alpha
  installPlanApproval: Automatic
  name: external-secrets-operator
  source: community-operators
  sourceNamespace: openshift-marketplace
  startingCSV: external-secrets-operator.v0.5.3
EOF

cat <<EOF | oc apply -n openshift-operators -f -
apiVersion: operator.external-secrets.io/v1alpha1
kind: OperatorConfig
metadata:
  name: cluster
spec:
  nodeSelector: {}
  imagePullSecrets: []
  podLabels: {}
  resources: {}
  leaderElect: false
  fullnameOverride: ''
  affinity: {}
  prometheus:
    enabled: false
    service:
      port: 8080
  podSecurityContext: {}
  scopedNamespace: ''
  extraArgs: {}
  securityContext: {}
  rbac:
    create: true
  replicaCount: 1
  nameOverride: ''
  serviceAccount:
    annotations: {}
    create: true
    name: ''
  installCRDs: false
  image:
    pullPolicy: IfNotPresent
    repository: ghcr.io/external-secrets/external-secrets
    tag: ''
  tolerations: []
  extraEnv: []
  priorityClassName: ''
  podAnnotations: {}
EOF


-- https://external-secrets.io/v0.5.3/provider-hashicorp-vault/

oc new-project foo

export CA_BUNDLE=$(oc get secret vault-certs -n hashicorp -o json | jq -r '.data."ca.crt"')

cat <<EOF | oc -n foo apply -f-
apiVersion: external-secrets.io/v1beta1
kind: ClusterSecretStore
metadata:
  name: vault-backend
spec:
  provider:
    vault:
      caBundle: $CA_BUNDLE
      server: "https://vault-active.hashicorp.svc:8200"
      version: "v2"
      path: "secret"
      auth:
        # points to a secret that contains a vault token
        # https://www.vaultproject.io/docs/auth/token
        tokenSecretRef:
          name: "vault-token"
          namespace: "foo"
          key: "token"
---
apiVersion: v1
kind: Secret
metadata:
  name: vault-token
data:
  token: <base64-this-is-not-my-token>
EOF
 
vault write systems/example username=systems@foo.net password=foobarbaz
vault read systems/example

cat <<EOF | oc -n foo apply -f-
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: vault-example
spec:
  refreshInterval: "30s"
  secretStoreRef:
    name: vault-backend
    kind: ClusterSecretStore
  target:
    name: example-sync
  data:
  - secretKey: username
    remoteRef:
      key: systems/example
      property: username
  - secretKey: password
    remoteRef:
      key: systems/example
      property: password
EOF

oc describe externalsecret.external-secrets.io/vault-example
oc delete externalsecret.external-secrets.io/vault-example
```
