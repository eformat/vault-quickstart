# External Secrets Operator

The [External Secrets Operator](https://external-secrets.io/v0.5.3/provider-hashicorp-vault) lets you mount secrets from vault (and many other secret sources) into your k8s cluster and creates k8s secrets for your pods to mount.

## Admin

### Install the Operator

Login to OpenShift from the CLI using a `cluster-admin` user.

```bash
oc login --server=https://api.${BASE_DOMAIN}:6443 -u <admin>
```

Create the operator subscription at cluster scope.

```bash
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
```

Deploy the basic default operator configuration (non HA) into a new project.

```bash
oc new-project external-secrets
```

```bash
cat <<EOF | oc apply -n external-secrets -f -
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
```

### Create a Cluster Secret Store

There are both cluster and namespaced scoped connections to the secret store aka vault in out case. We are going to setup a `ClusterSecretStore` for demonstration purposes. 

If you want finer grained security you can narrow this scope down to project based auth (ldap, k8s auth for example). See the `Authentication` section in the [vault external secret documentation](https://external-secrets.io/v0.5.3/provider-hashicorp-vault/#authentication).

We already should have the CA set in our environment, else run.

```bash
export CA_BUNDLE=$(oc get secret vault-certs -n hashicorp -o json | jq -r '.data."ca.crt"')
```

We create our `root` token in `external-secrets` namespace and Point our `ClusterSecretStore` at it.

```bash
cat <<EOF | oc apply -f-
apiVersion: external-secrets.io/v1beta1
kind: ClusterSecretStore
metadata:
  name: vault-backend
  namespace: external-secrets
spec:
  provider:
    vault:
      caBundle: $CA_BUNDLE
      server: "https://$VAULT_SERVICE:8200"
      version: "v2"
      path: "kv"
      auth:
        # points to a secret that contains a vault token
        # https://www.vaultproject.io/docs/auth/token
        tokenSecretRef:
          name: "vault-token"
          namespace: external-secrets
          key: "token"
---
apiVersion: v1
kind: Secret
metadata:
  name: vault-token
  namespace: external-secrets
data:
  token: "$(echo -n $ROOT_TOKEN | base64)"
EOF
```

Check this reconciled OK.

```bash
oc describe clustersecretstore.external-secrets.io/vault-backend
```
<pre>
Events:
  Type     Reason                 Age                From                  Message
  ----     ------                 ----               ----                  -------
  Normal   Valid                  7s (x2 over 7s)    cluster-secret-store  store validated
</pre>

## Non-Admin

### Create an External Secret

Login using our team user `mike` and change context to our app project.

```bash
oc login --server=https://api.${BASE_DOMAIN}:6443 -u mike
```
```bash
vault login -method=ldap username=mike
```
```asciidoc
oc project $PROJECT_NAME
```

Create a new vault secret for testing.

```bash
APP_NAME=external-secret

vault kv put kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME \
  app=$APP_NAME \
  username=mickey \
  password=mouse
```

Create the external secret. We must use the full `kv/data` path here. The target k8s secret is called `example-secret`.

```bash
cat <<EOF | oc -n ${PROJECT_NAME} apply -f-
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: external-secret
spec:
  refreshInterval: "30s"
  secretStoreRef:
    name: vault-backend
    kind: ClusterSecretStore
  target:
    name: example-secret
  data:
  - secretKey: username
    remoteRef:
      key: kv/data/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME
      property: username
  - secretKey: password
    remoteRef:
      key: kv/data/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME
      property: password
EOF
```

All working well, we should see.

```bash
oc describe externalsecret.external-secrets.io/external-secret
```
<pre>
Events:
  Type    Reason   Age   From              Message
  ----    ------   ----  ----              -------
  Normal  Updated  6s    external-secrets  Updated Secret
</pre>

And we can extract the data from our newly created k8s secret. 

```bash
oc get secret example-secret -o go-template="{{index .data \"password\" | base64decode}}"
```
<pre>
mouse
</pre>

Try changing the vault secret.

```bash
vault kv put kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME \
  app=$APP_NAME \
  username=donald \
  password=duck
```

And extracting the data from k8s secret - it should automatically update.

```bash
oc get secret example-secret -o go-template="{{index .data \"password\" | base64decode}}"
```
<pre>
duck
</pre>

And as a team user we can of course remove the k8s secrets altogether (it is still in vault though).

```bash
oc delete externalsecret.external-secrets.io/vault-example
```
