# Vault Zero to Hero 

This is a `How-to Guide`. It addresses how to get started with Hashicorp Vault on OpenShift from scratch.

## Prerequisites

- OpenShift Cluster (4.10+) with OLM.
- cluster-admin login
- helm3
- openssl

## Vault Install

### Environment Variables

Let's set up some env.vars

```bash
# OpenShift Cluster Base Domain
export BASE_DOMAIN=$(oc get dns cluster -o jsonpath='{.spec.baseDomain}')
# Internal k8s service in-cluster
export KUBERNETES_HOST=https://kubernetes.default.svc:443
# Vault Service and Route base name
export VAULT_HELM_RELEASE=vault
# Vault Route FQDN
export VAULT_ROUTE=${VAULT_HELM_RELEASE}.apps.$BASE_DOMAIN
export VAULT_ADDR=https://${VAULT_ROUTE}
# Vault service in-cluster
export VAULT_SERVICE=${VAULT_HELM_RELEASE}-active.hashicorp.svc
# This makes it easier to use the CLI, else need to trust the CA
export VAULT_SKIP_VERIFY=true
```

### Login to OpenShift

Login to OpenShift from the CLI using a `cluster-admin` user.

```bash
oc login --server=https://api.${BASE_DOMAIN}:6443 -u <user>
```

### Install Cert Manager

We need to make use of [Cert Manager](https://cert-manager.io/docs/) Operator to help us setup PKI for vault in the next step. Install the Operator as follows.

Create a namespace.

```bash
cat <<EOF | oc apply -f-
kind: Namespace
apiVersion: v1
metadata:
  name: openshift-cert-manager-operator
EOF
```

Create an Operator Group.

```bash
cat <<EOF | oc create -f-
apiVersion: operators.coreos.com/v1
kind: OperatorGroup
metadata:
  annotations:
    olm.providedAPIs: CertManager.v1alpha1.config.openshift.io,CertManager.v1alpha1.operator.openshift.io,Certificate.v1.cert-manager.io,CertificateRequest.v1.cert-manager.io,Challenge.v1.acme.cert-manager.io,ClusterIssuer.v1.cert-manager.io,Issuer.v1.cert-manager.io,Order.v1.acme.cert-manager.io
  generateName: openshift-cert-manager-operator-
  namespace: openshift-cert-manager-operator
spec: {}
EOF
```

Create the Subscription.

```bash
cat <<EOF | oc apply -f-
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  labels:
    operators.coreos.com/openshift-cert-manager-operator.openshift-cert-manager-operator: ''
  name: openshift-cert-manager-operator
  namespace: openshift-cert-manager-operator
spec:
  channel: tech-preview
  installPlanApproval: Automatic
  name: openshift-cert-manager-operator
  source: redhat-operators
  sourceNamespace: openshift-marketplace
  startingCSV: openshift-cert-manager.v1.7.1
EOF
```

When OK, you should see.

```bash
$ oc get pods -n openshift-cert-manager-operator
NAME                                     READY   STATUS    RESTARTS   AGE
cert-manager-operator-6cc4d48f84-8bcdc   1/1     Running   0          51s
```

### Setup PKI

We need to initialize vault with a CA certificate. We are going to create a root CA and an intermediate code signing cert. Change the cert details to suit your setup.

```bash
mkdir ~/tmp/vault-certs && cd ~/tmp/vault-certs
export CERT_ROOT=$(pwd)
mkdir -p ${CERT_ROOT}/{root,intermediate}

cd ${CERT_ROOT}/root/
openssl genrsa -out ca.key 2048
touch index.txt
echo 1000 > serial
mkdir -p newcerts

cat <<EOF > openssl.cnf
[ ca ]
default_ca = CA_default

[ CA_default ]
# Directory and file locations.
dir               = ${CERT_ROOT}/root
certs             = \$dir/certs
crl_dir           = \$dir/crl
new_certs_dir     = \$dir/newcerts
database          = \$dir/index.txt
serial            = \$dir/serial
RANDFILE          = \$dir/private/.rand

# The root key and root certificate.
private_key       = \$dir/ca.key
certificate       = \$dir/ca.crt

# For certificate revocation lists.
crlnumber         = \$dir/crlnumber
crl               = \$dir/crl/ca.crl
crl_extensions    = crl_ext
default_crl_days  = 30

# SHA-1 is deprecated, so use SHA-2 instead.
default_md        = sha256

name_opt          = ca_default
cert_opt          = ca_default
default_days      = 375
preserve          = no

policy            = policy_strict

[ policy_strict ]
# The root CA should only sign intermediate certificates that match.
countryName               = match
stateOrProvinceName       = optional
organizationName          = optional
organizationalUnitName    = optional
commonName                = supplied
emailAddress              = optional

[ v3_intermediate_ca ]
# Extensions for a typical intermediate CA.
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
basicConstraints = critical, CA:true, pathlen:1
keyUsage = critical, digitalSignature, cRLSign, keyCertSign

[req_distinguished_name]
countryName = AU
countryName = Country Name
countryName_default = AU
stateOrProvinceName = State or Province Name
stateOrProvinceName_default = QLD
localityName= Locality Name
localityName_default = Brisbane
organizationName= Organization Name
organizationName_default = Acme Corp
commonName= Company Name
commonName_default = acme.corp
commonName_max = 64

[req]
distinguished_name = req_distinguished_name
[ v3_ca ]
basicConstraints = critical,CA:TRUE
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer:always
EOF

openssl req -x509 -new -nodes -key ca.key -sha256 -days 1024 -out ca.crt -extensions v3_ca -config openssl.cnf

cd ../intermediate
openssl genrsa -out ca.key 2048
openssl req -new -sha256 -key ca.key -out ca.csr -subj "/C=AU/ST=QLD/L=Brisbane/O=Acme Corp/OU=AC/CN=int.acme.corp"
openssl ca -config ../root/openssl.cnf -extensions v3_intermediate_ca -days 365 -notext -md sha256 -in ca.csr -out ca.crt
```

Load the intermediate cert as a secret into the OpenShift `hashicorp` project (we will install vault here in the proceeding steps).

```bash
oc new-project hashicorp
oc create secret tls intermediate --cert=${CERT_ROOT}/intermediate/ca.crt --key=${CERT_ROOT}/intermediate/ca.key -n hashicorp
```

Create a Cert Manager `Issuer` and `Certificate`.

```bash
cat <<EOF | oc apply -f-
apiVersion: cert-manager.io/v1
kind: Issuer
metadata:
  name: int-ca-issuer
spec:
  ca:
    secretName: intermediate
EOF

cat <<EOF | oc apply -f-
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: vault-certs
spec:
  secretName: vault-certs
  issuerRef:
    name: int-ca-issuer
    kind: Issuer
  dnsNames: 
  - ${VAULT_ROUTE}
  # Service Active FQDN
  - ${VAULT_SERVICE}
  organization:
  - acme.corp
EOF
```

You can inspect the `data:` section of the cert which contains the generated certificate details.

```bash
$ oc get secret vault-certs -o yaml
...
```

### Install Vault

Install Vault using helm. Add the helm repo:

```bash
helm repo add hashicorp https://helm.releases.hashicorp.com
helm repo update
```

Create a default values file:
- HA 3 node vault cluster
- Uses the Hashicorp UBI images (you can get enterprise support from Hashicorp - use `vault-enterprise`) - check [here](https://catalog.redhat.com/software/containers) for latest versions
- audit storage configured

```bash
mkdir -p ${CERT_ROOT}/vault && cd ${CERT_ROOT}/vault
cat <<EOF > values.yaml
global:
  tlsDisable: false
  openshift: true
injector:
  image:
    repository: "registry.connect.redhat.com/hashicorp/vault-k8s"
    tag: "0.16.0-ubi"
  agentImage:
    repository: "registry.connect.redhat.com/hashicorp/vault"
    tag: "1.10.3-ubi"
ui:
  enabled: true
server:
  image:
    repository: "registry.connect.redhat.com/hashicorp/vault"
    tag: "1.10.3-ubi"
  route:
    enabled: true
    host:
  extraEnvironmentVars:
    VAULT_CACERT: "/etc/vault-tls/vault-certs/ca.crt"
    VAULT_TLS_SERVER_NAME:
  standalone:
    enabled: false
  auditStorage:
    enabled: true
    size: 15Gi
  extraVolumes:
    - type: "secret"
      name: "vault-certs"
      path: "/etc/vault-tls"
  ha:
    enabled: true
    raft:
      enabled: true
      setNodeId: true
      config: |
        ui = true
        listener "tcp" {
          address = "[::]:8200"
          cluster_address = "[::]:8201"
          tls_cert_file = "/etc/vault-tls/vault-certs/tls.crt"
          tls_key_file = "/etc/vault-tls/vault-certs/tls.key"
          tls_client_ca_file = "/etc/vault-tls/vault-certs/ca.crt"
        }
        storage "raft" {
          path = "/vault/data"
          retry_join {
            leader_api_addr = "https://vault-active.hashicorp.svc:8200"
            leader_ca_cert_file = "/etc/vault-tls/vault-certs/ca.crt"
          }
        }
        log_level = "debug"
        service_registration "kubernetes" {}
  service:
    enabled: true
EOF
```

OK, lets install into `hashicorp` namespace.

<p class="tip">
⛷️ <b>TIP</b> ⛷️ - We override the tolerations for the vault server since we only have 2 worker nodes in our cluster and an HA deployment needs 3. This was we can run on all nodes incl. master
</p>  
 
Run the installer.

```bash
helm install vault hashicorp/vault -f values.yaml \
    --set server.route.host=$VAULT_ROUTE \
    --set server.extraEnvironmentVars.VAULT_TLS_SERVER_NAME=$VAULT_ROUTE \
    --set server.tolerations[0].operator=Exists,server.tolerations[0].effect=NoSchedule \
    --namespace hashicorp \
    --wait
```

When successful you should get.

```bash
NAME: vault
LAST DEPLOYED: Wed May 25 16:44:35 2022
NAMESPACE: hashicorp
STATUS: deployed
REVISION: 1
NOTES:
Thank you for installing HashiCorp Vault!
```

And all pods are running but not ready (this is ok).

```bash
$ oc get pods
NAME                                   READY   STATUS    RESTARTS   AGE
vault-0                                0/1     Running   0          20s
vault-1                                0/1     Running   0          20s
vault-2                                0/1     Running   0          20s
vault-agent-injector-68df5cdbc-4jnrr   1/1     Running   0          20s
```

### Unseal the vault

On first install, we need to initialize and unseal the vault. First step is to initialize and get the `root` token.  

```bash
oc -n hashicorp exec -ti vault-0 -- vault operator init -key-threshold=1 -key-shares=1
```

You should see the `root` token and unseal key printed out. Save these.

```bash
# use the root token to unseal
Unseal Key 1: this-is-not-my-key
Initial Root Token: this-is-not-my-token
```

Export these in our environment for now for ease of use.

```bash
export ROOT_TOKEN=this-is-not-my-token
export UNSEAL_KEY=this-is-not-my-key
``

```bash
oc -n hashicorp exec -ti vault-0 -- vault operator unseal $UNSEAL_KEY
oc -n hashicorp exec -ti vault-1 -- vault operator unseal $UNSEAL_KEY
oc -n hashicorp exec -ti vault-2 -- vault operator unseal $UNSEAL_KEY
```

And all pods are running and now ready once you have unsealed all the vault nodes.

```bash
$ oc get pods
NAME                                   READY   STATUS    RESTARTS   AGE
vault-0                                1/1     Running   0          5m40s
vault-1                                1/1     Running   0          5m40s
vault-2                                1/1     Running   0          5m40s
vault-agent-injector-68df5cdbc-4jnrr   1/1     Running   0          5m40s
```

## Vault Configuration

### Namespace and Mount Structuring Guide

<p class="tip">
⛷️ <b>TIP</b> ⛷️ - Skip the background reading if you want, but at some point you will need it !
</p>  

For this demo, there are a few [recommended patterns](https://learn.hashicorp.com/tutorials/vault/namespace-structure?in=vault/recommended-patterns) and background reading on [ACL Policy templating](https://learn.hashicorp.com/tutorials/vault/policy-templating) that should be considered a MUST read. In particular watch the [Youtube Video](https://www.youtube.com/watch?v=zDnIqSB4tyA&t=1532s). Also checkout the [References](#references) links.

We will:

- setup vault self-service where team users can manage their own KV per team/application
- leverage Vault identities, vault ACL templates for ensuring apps can only read their own secrets
- not use vault namespaces (they are an enterprise feature)
- connect to vault with a project-scoped k8s service account
- deploy an app with an application k8s service-account
- application k8s service-account can read, list secrets for that app only
- users in the team group has full access to their team secrets
- admins must configure
  - project, project sa, vault auth, vault policy
- users can configure
  - app secrets, app service accounts, vault config for these

Paths in vault:

```bash
== Access ==
ldap/                                   <-- ldap users in $TEAM_GROUP
token/                                  <-- default, token
$BASE_DOMAIN-$PROJECT_NAME/$APP_NAME    <-- kubernetes roles by cluster-project/app

== Groups ==
$TEAM_GROUP/                            <-- ldap entity ids (users) for $TEAM_GROUP

== Secrets ==
kv/                                     <-- kv version 2
kv/$TEAM_GROUP                          <-- team group secrets
kv/$TEAM_GROUP/$PROJECT_NAME            <-- project secrets
kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME  <-- app secrets kvv2

== Policies ==
$TEAM_GROUP_$APP_NAME/              <-- users in $TEAM_GROUP CRUDL on kv/TEAM_GROUP
                                    <-- k8s project sa auth/$BASE_DOMAIN-$PROJECT_NAME/role CRUDL

$BASE_DOMAIN-$PROJECT_NAME-kv-read  <-- k8s app sa RL on kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

Notes: CRUDL = create, read, update, delete, list
```

### Login and check vault

Login to vault using the environment vars and token. 

```
vault login token=${ROOT_TOKEN}
```

If all OK, you should see.

```bash
Success! You are now authenticated. The token information displayed below
is already stored in the token helper. You do NOT need to run "vault login"
again. Future Vault requests will automatically use this token.

Key                  Value
---                  -----
token                this-is-not-my-token
token_accessor       this-is-not-my-token-accessor
token_duration       ∞
token_renewable      false
token_policies       ["root"]
identity_policies    []
policies             ["root"]
```

We can check vault quorum is OK.

```bash
$ vault operator raft list-peers

Node       Address                        State       Voter
----       -------                        -----       -----
vault-0    vault-0.vault-internal:8201    leader      true
vault-1    vault-1.vault-internal:8201    follower    true
vault-2    vault-2.vault-internal:8201    follower    true
```

If you browse to the Web UI you should be able to login using your token as well.

![images/vault-login.png](images/vault-login.png)

### Team based access

There are [many](https://www.vaultproject.io/api-docs/auth/userpass) auth methods supported by vault.

You can continue using the `root` token or setup `userpass` if you do not have LDAP in your cluster.

- [ldap](https://www.vaultproject.io/api-docs/auth/ldap)
- [userpass](https://www.vaultproject.io/api-docs/auth/userpass)

#### LDAP

We have LDAP configured for users in our OpenShift cluster. We can easily configure this to authenticate with vault. Export our `bindDN` user password.

```bash
export LDAP_PASSWORD=this-is-not-my-password
```

Change `dn`'s to suit your ldap configuration, enable auth login for vault. 

```bash
vault auth enable ldap

vault write auth/ldap/config \
  url="ldap://ipa.ipa.svc.cluster.local:389" \
  binddn="uid=ldap_admin,cn=users,cn=accounts,dc=redhatlabs,dc=com" \
  bindpass="$LDAP_PASSWORD" \
  userdn="cn=users,cn=accounts,dc=redhatlabs,dc=com" \
  userattr="uid" \
  groupdn="cn=student,cn=groups,cn=accounts,dc=redhatlabs,dc=com" \
  groupattr="cn"
```

If you login to vault from the Web UI you should see this Access > Auth Methods > `ldap` auth method.

![images/auth-ldap-vault.png](images/auth-ldap-vault.png)

We can now try ldap using a regular user. 

```bash
vault login -method=ldap username=mike
```

If all is OK, you should see.

```bash
Password (will be hidden): 
Success! You are now authenticated. The token information displayed below
is already stored in the token helper. You do NOT need to run "vault login"
again. Future Vault requests will automatically use this token.

Key                    Value
---                    -----
token                  this-is-not-mikes-token
token_accessor         this-is-not-mikes-token-accessor
token_duration         768h
token_renewable        true
token_policies         ["default"]
identity_policies      []
policies               ["default"]
token_meta_username    mike
```

#### Userpass

FIXME - add userpass instructions

### Team Setup

#### Admin

```bash
export TEAM_NAME=bar
export TEAM_GROUP=student
export PROJECT_NAME=${TEAM_NAME}

oc new-project ${PROJECT_NAME}

cat <<EOF | oc apply -f-
kind: RoleBinding
apiVersion: rbac.authorization.k8s.io/v1
metadata:
  name: ${TEAM_GROUP}-admin
  namespace: ${PROJECT_NAME}
subjects:
  - kind: Group
    apiGroup: rbac.authorization.k8s.io
    name: ${TEAM_GROUP}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: admin
EOF

oc adm policy add-cluster-role-to-user system:auth-delegator -z ${APP_NAME} -n ${PROJECT_NAME}

vault login token=${ROOT_TOKEN}

vault policy write $TEAM_GROUP-$PROJECT_NAME -<<EOF
path "kv/data/{{identity.groups.names.$TEAM_GROUP.name}}/$PROJECT_NAME/*" {
    capabilities = [ "create", "update", "read", "delete", "list" ]
}
path "auth/$BASE_DOMAIN-$PROJECT_NAME/*" {
    capabilities = [ "create", "update", "read", "delete", "list" ]
}
EOF

# these are lists policies, member_entity_ids 
vault write identity/group name="$TEAM_GROUP" \
     policies="student_foo,$TEAM_GROUP-$PROJECT_NAME" \
     member_entity_ids=77b3550d-d610-afe2-e24a-588923b7a8b8 \
     metadata=team="$TEAM_GROUP"
     
vault secrets enable -path=kv/ -version=2 kv
vault auth enable -path=$BASE_DOMAIN-${PROJECT_NAME} kubernetes

vault auth list
export MOUNT_ACCESSOR=$(vault auth list -format=json | jq -r ".\"$BASE_DOMAIN-$PROJECT_NAME/\".accessor")

vault policy write $BASE_DOMAIN-$PROJECT_NAME-kv-read - << EOF
    path "kv/data/$TEAM_GROUP/{{identity.entity.aliases.$MOUNT_ACCESSOR.metadata.service_account_namespace}}/{{identity.entity.aliases.$MOUNT_ACCESSOR.metadata.service_account_name}}" {
        capabilities=["read","list"]
    }
EOF

vault policy read $BASE_DOMAIN-$PROJECT_NAME-kv-read
```

![images/acl-policy.png](images/acl-policy.png)

#### Non-Admin

```bash
export APP_NAME=vault-quickstart

oc login --server=https://api.${BASE_DOMAIN}:6443 -u mike
vault login -method=ldap username=mike

vault write auth/$BASE_DOMAIN-$PROJECT_NAME/role/$APP_NAME \
  bound_service_account_names=$APP_NAME \
  bound_service_account_namespaces=$PROJECT_NAME \
  policies=$BASE_DOMAIN-$PROJECT_NAME-kv-read \
  period=120s

vault kv put kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME \
  app=$APP_NAME \
  username=foo \
  password=baz
  
vault kv get kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME
```

## Application

### Build

```bash
mvn package -Dquarkus.package.type=fast-jar -DskipTests
```

### Deploy

```bash
mvn oc:build oc:resource oc:apply \
 -Djkube.namespace=$PROJECT_NAME \
 -Dbase.domain=$BASE_DOMAIN
```

```bash
export SA_TOKEN=$(oc -n ${PROJECT_NAME} get sa/${APP_NAME} -o yaml | grep ${APP_NAME}-token | awk '{print $3}')
export SA_JWT_TOKEN=$(oc -n ${PROJECT_NAME} get secret $SA_TOKEN -o jsonpath="{.data.token}" | base64 --decode; echo)
export SA_CA_CRT=$(oc -n ${PROJECT_NAME} get secret $SA_TOKEN -o jsonpath="{.data['ca\.crt']}" | base64 --decode; echo)
vault write auth/$BASE_DOMAIN-${PROJECT_NAME}/config \
  token_reviewer_jwt="$SA_JWT_TOKEN" \
  kubernetes_host="$(oc whoami --show-server)" \
  kubernetes_ca_cert="$SA_CA_CRT"
```

### Test

V1 Secret

```bash
curl -sk -w"\n" https://$(oc get route $APP_NAME --template='{{ .spec.host }}')/kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

{app=vault-quickstart, password=bar, username=foo}
```

V2 Secret

```bash
vault kv put kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME \
  app=$APP_NAME \
  username=cab \
  password=abc

curl -sk -w"\n" https://$(oc get route $APP_NAME --template='{{ .spec.host }}')/kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

{app=vault-quickstart, password=abc, username=cab}
```

Lookup another application's secret (Not Allowed)

```bash
oc login --server=https://api.${BASE_DOMAIN}:6443 -u mike
vault login -method=ldap username=mike
vault token lookup

export APP_NAME=another-app

vault kv put kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME \
  app=$APP_NAME \
  username=cab \
  password=abc

curl -sk -w"\n" https://$(oc get route vault-quickstart --template='{{ .spec.host }}')/kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

# k8s app sa can only get secrets at kv/student/foo/vault-quickstart
# due to vault ACL policy
{"details":"Error id d47c2c4c-0893-4652-b2bd-f9dcb0ab8dfe-2","stack":""}
Caused by: io.quarkus.vault.runtime.client.VaultClientException code=403 body={"errors":["1 error occurred:\n\t* permission denied\n\n"]}

export APP_NAME=vault-quickstart
curl -sk -w"\n" https://$(oc get route $APP_NAME --template='{{ .spec.host }}')/kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

{app=vault-quickstart, password=bar, username=foo}
```

Delete a secret (Not Allowed) ACL policy denies this to the app sa 

```bash
curl -X DELETE -sk -w"\n" https://$(oc get route $APP_NAME --template='{{ .spec.host }}')/kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

{"details":"Error id cb28d8e2-4ff2-4a14-b110-884489b36e00-2","stack":""}
```

We could update the $BASE_DOMAIN-$PROJECT_NAME-kv-read policy with "delete" just to make sure

![images/acl-delete-test.png](images/acl-delete-test.png)

```bash
capabilities=["read","list","delete"]

curl -X DELETE -sk -w"\n" https://$(oc get route $APP_NAME --template='{{ .spec.host }}')/kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

Secret: student/bar/vault-quickstart deleted
```

## External Secrets Operator

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

## Vault Config Operator

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

## References

Handy links found along the way

- https://www.vaultproject.io/docs
- https://cloud.redhat.com/blog/how-to-secure-cloud-native-applications-with-hashicorp-vault-and-cert-manager
- https://external-secrets.io/v0.5.3/provider-hashicorp-vault
- https://learn.hashicorp.com/tutorials/vault/policy-templating
- https://www.youtube.com/watch?v=zDnIqSB4tyA&t=1532s
- https://learn.hashicorp.com/tutorials/vault/pattern-policy-templates?in=vault/recommended-patterns
- https://learn.hashicorp.com/tutorials/vault/namespace-structure?in=vault/recommended-patterns
- https://github.com/hashicorp/vault-guides.git
- https://quarkiverse.github.io/quarkiverse-docs/quarkus-vault/dev/vault-auth.html#_kubernetes_authentication
- https://cert-manager.io/docs
