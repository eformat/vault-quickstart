# Vault Config Operator

The [Vault Config Operator](https://github.com/redhat-cop/vault-config-operator) helps set up Vault Configurations. For an advanced guide i highly recommend this [blog post](https://cloud.redhat.com/blog/how-to-secure-cloud-native-applications-with-hashicorp-vault-and-cert-manager).

All of the `vault` commands we have been running so far can be turned into YAML configuration that we can get the vault config operator to apply to our OpenShift Clusters. This is pretty handy when you combine it with a GitOps tool like [ArgoCD](https://argo-cd.readthedocs.io/en/stable/).

## Admin

### Install the Operator

Login to OpenShift from the CLI using a `cluster-admin` user.

```bash
oc login --server=https://api.${BASE_DOMAIN}:6443 -u <admin>
```

Create a namespace for the operator.

```bash
cat <<EOF | oc apply -f-
kind: Namespace
apiVersion: v1
metadata:
  name: vault-config-operator
EOF
```

Create the operator group.

```bash
cat <<EOF | oc create -f-
apiVersion: operators.coreos.com/v1
kind: OperatorGroup
metadata:
  annotations:
    olm.providedAPIs: AuthEngineMount.v1alpha1.redhatcop.redhat.io,DatabaseSecretEngineConfig.v1alpha1.redhatcop.redhat.io,DatabaseSecretEngineRole.v1alpha1.redhatcop.redhat.io,GitHubSecretEngineConfig.v1alpha1.redhatcop.redhat.io,GitHubSecretEngineRole.v1alpha1.redhatcop.redhat.io,KubernetesAuthEngineConfig.v1alpha1.redhatcop.redhat.io,KubernetesAuthEngineRole.v1alpha1.redhatcop.redhat.io,LDAPAuthEngineConfig.v1alpha1.redhatcop.redhat.io,PKISecretEngineConfig.v1alpha1.redhatcop.redhat.io,PKISecretEngineRole.v1alpha1.redhatcop.redhat.io,PasswordPolicy.v1alpha1.redhatcop.redhat.io,Policy.v1alpha1.redhatcop.redhat.io,QuaySecretEngineConfig.v1alpha1.redhatcop.redhat.io,QuaySecretEngineRole.v1alpha1.redhatcop.redhat.io,QuaySecretEngineStaticRole.v1alpha1.redhatcop.redhat.io,RabbitMQSecretEngineConfig.v1alpha1.redhatcop.redhat.io,RabbitMQSecretEngineRole.v1alpha1.redhatcop.redhat.io,RandomSecret.v1alpha1.redhatcop.redhat.io,SecretEngineMount.v1alpha1.redhatcop.redhat.io,VaultSecret.v1alpha1.redhatcop.redhat.io
  generateName: vault-config-operator-
  namespace: vault-config-operator
spec: {}
EOF
```

Create the subscription at cluster scope.

```bash
cat <<EOF | oc apply -f-
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  labels:
    operators.coreos.com/vault-config-operator.vault-config-operator: ""
  name: vault-config-operator
  namespace: vault-config-operator
spec:
  channel: alpha
  installPlanApproval: Automatic
  name: vault-config-operator
  source: community-operators
  sourceNamespace: openshift-marketplace
  startingCSV: vault-config-operator.v0.5.0
EOF
```

We should see.

```bash
oc get pods -n vault-config-operator
```

### Create configs

**FIXME** - YAML/Kustomize configs for this `How-to Guide` - TBD.
