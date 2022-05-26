# Vault Config Operator

```bash
cat <<EOF | oc apply -f-
kind: Namespace
apiVersion: v1
metadata:
  name: vault-config-operator
EOF

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
