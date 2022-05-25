# vault-quickstart

Vault Install
```bash
export BASE_DOMAIN=$(oc get dns cluster -o jsonpath='{.spec.baseDomain}')
export VAULT_HELM_RELEASE=vault
export VAULT_ROUTE=${VAULT_HELM_RELEASE}.apps.$BASE_DOMAIN
export VAULT_ADDR=https://${VAULT_ROUTE}
export VAULT_SERVICE=${VAULT_HELM_RELEASE}-active.hashicorp.svc
export VAULT_SKIP_VERIFY=true
export KUBERNETES_HOST=https://kubernetes.default.svc:443

helm repo add hashicorp https://helm.releases.hashicorp.com
helm repo update

cat <<EOF > values.yaml
global:
  tlsDisable: false
  openshift: true
injector:
  image:
    repository: "registry.connect.redhat.com/hashicorp/vault-k8s"
    tag: "0.14.2-ubi"
  agentImage:
    repository: "registry.connect.redhat.com/hashicorp/vault"
    tag: "1.9.6-ubi"
ui:
  enabled: true
server:
  image:
    repository: "registry.connect.redhat.com/hashicorp/vault"
    tag: "1.9.6-ubi"
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

# HA, 3 node cluster, we override tolerations since we only have 2 worker nodes (deploy on all nodes - master)
helm install vault hashicorp/vault -f values.yaml \
    --set server.route.host=$VAULT_ROUTE \
    --set server.extraEnvironmentVars.VAULT_TLS_SERVER_NAME=$VAULT_ROUTE \
    --set server.tolerations[0].operator=Exists,server.tolerations[0].effect=NoSchedule \
    --create-namespace \
    --namespace hashicorp \
    --wait

oc -n hashicorp exec -ti vault-0 -- vault operator init -key-threshold=1 -key-shares=1

# use the root token to unseal
Unseal Key 1: this-is-not-my-key
Initial Root Token: this-is-not-my-token

oc -n hashicorp exec -ti vault-0 -- vault operator unseal this-is-not-my-token
oc -n hashicorp exec -ti vault-1 -- vault operator unseal this-is-not-my-token
oc -n hashicorp exec -ti vault-2 -- vault operator unseal this-is-not-my-token

```

Vault Configuration
```bash
export TEAM_NAME=foo
export PROJECT_NAME=${TEAM_NAME}
export APP_NAME=vault-quickstart

vault login token=this-is-not-my-token

vault operator raft list-peers

oc new-project ${PROJECT_NAME}
oc -n ${PROJECT_NAME} create sa ${TEAM_NAME}-vault
oc adm policy add-cluster-role-to-user system:auth-delegator -z ${TEAM_NAME}-vault -n ${PROJECT_NAME}

export SA_TOKEN=$(oc -n ${PROJECT_NAME} get sa/${TEAM_NAME}-vault -o yaml | grep ${TEAM_NAME}-vault-token | awk '{print $3}')
export SA_JWT_TOKEN=$(oc -n ${PROJECT_NAME} get secret $SA_TOKEN -o jsonpath="{.data.token}" | base64 --decode; echo)
export SA_CA_CRT=$(oc -n ${PROJECT_NAME} get secret $SA_TOKEN -o jsonpath="{.data['ca\.crt']}" | base64 --decode; echo)

vault auth enable -path=$BASE_DOMAIN kubernetes
vault write auth/$BASE_DOMAIN/config \
  token_reviewer_jwt="$SA_JWT_TOKEN" \
  kubernetes_host="$(oc whoami --show-server)" \
  kubernetes_ca_cert="$SA_CA_CRT"

vault auth list
export MOUNT_ACCESSOR=$(vault auth list -format=json | jq -r ".\"$BASE_DOMAIN/\".accessor")

vault policy write kubernetes-kv-read - << EOF
    path "kv/data/{{identity.entity.aliases.$MOUNT_ACCESSOR.metadata.service_account_namespace}}/{{identity.entity.aliases.$MOUNT_ACCESSOR.metadata.service_account_name}}" {
        capabilities=["read","list"]
    }
EOF

vault policy read kubernetes-kv-read

vault write auth/$BASE_DOMAIN/role/$PROJECT_NAME-$APP_NAME \
  bound_service_account_names=$APP_NAME \
  bound_service_account_namespaces=$PROJECT_NAME \
  policies=kubernetes-kv-read \
  period=120s

vault kv put kv/$PROJECT_NAME/$APP_NAME \
  app=$APP_NAME \
  username=foo \
  password=bar
```

Build
```bash
mvn package -Dquarkus.package.type=fast-jar -DskipTests
```

Deploy
```bash
mvn oc:build oc:resource oc:apply -Djkube.namespace=foo
```

Test
```bash
$ curl -sk -w"\n" https://$(oc get route vault-quickstart --template='{{ .spec.host }}')/kv/foo/$APP_NAME

{app=vault-quickstart, password=bar, username=foo}
```
