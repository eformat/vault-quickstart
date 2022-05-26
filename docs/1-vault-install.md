# Vault Install

## Environment Variables

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

## Login to OpenShift

Login to OpenShift from the CLI using a `cluster-admin` user.

```bash
oc login --server=https://api.${BASE_DOMAIN}:6443 -u <admin>
```

## Install Cert Manager

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

## Setup PKI

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

You can inspect the `data:` section of the cert secret which contains the generated certificate details (`ca.crt`, `tls.crt`, `tls.key`).

```bash
oc get secret vault-certs -o jsonpath='{.data}' | jq .
```

## Install Vault

Install Vault using helm. Add the helm repo:

```bash
helm repo add hashicorp https://helm.releases.hashicorp.com
helm repo update
```

Create a default values file:
- HA 3 node vault cluster
- Uses the Hashicorp UBI images (you can get enterprise support from Hashicorp - use `vault-enterprise`) - check [here](https://catalog.redhat.com/software/containers) for latest versions
- audit storage configured (adjust the disk size accordingly)

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
    size: 5Gi
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

## Unseal the vault

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
```

Unseal each node in the cluster.

```bash
for x in `seq 0 2`; do
  oc -n hashicorp exec -ti vault-$x -- vault operator unseal $UNSEAL_KEY
done
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

## Vault CLI

Grab the [vault cli](https://learn.hashicorp.com/tutorials/vault/getting-started-install?in=vault/getting-started) 

```bash
wget https://releases.hashicorp.com/vault/1.10.3/vault_1.10.3_linux_amd64.zip
unzip vault_1.10.3_linux_amd64.zip
sudo mv vault /usr/local/bin/vault && sudo chmod 755 /usr/local/bin/vault
```
