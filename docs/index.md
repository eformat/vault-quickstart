# Vault Zero to Hero 

This is a `How-to Guide`. It addresses how to get started with Hashicorp Vault on OpenShift from scratch.

The Sections are organized as follows.

```bash
├── Vault Install                - Install a highly avaiable instance of Hasicorp Vault into OpenShift.
├── Vault Configuration          - Configure Vault and set it up for our self-service usage.
├── Application                  - Deploy a simple Quarkus Java test application that talks to Vault.
├── External Secrets Operator    - Mount Vault key values as secrets using this operator.
├── Vault Configuration Operator - GitOps for your Vault.
```

## Prerequisites

- OpenShift Cluster (4.10+) with OLM.
- cluster-admin login
- helm3
- openssl

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
