# Application

We are going to deploy a Quarkus application that can test CRUDL based calls to vault using k8s based authentication.

Login using our team user `mike`.

```bash
oc login --server=https://api.${BASE_DOMAIN}:6443 -u mike
vault login -method=ldap username=mike
```

## Build

Build our application locally.

```bash
mvn package -Dquarkus.package.type=fast-jar -DskipTests
```

## Deploy

Deploy into OpenShift. This uses jkube.

```bash
mvn oc:build oc:resource oc:apply \
  -Djkube.namespace=$PROJECT_NAME \
  -Dbase.domain=$BASE_DOMAIN
```

## Test

Make sure $APP_NAME is set.

```bash
export APP_NAME=vault-quickstart
```

Read version 1 of our KV secret.

```bash
curl -sk -w"\n" https://$(oc get route $APP_NAME --template='{{ .spec.host }}')/kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

# output
{app=vault-quickstart, password=bar, username=foo}
```

Let's create a version 2 of our KV secret.

```bash
vault kv put kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME \
  app=$APP_NAME \
  username=cab \
  password=abc

curl -sk -w"\n" https://$(oc get route $APP_NAME --template='{{ .spec.host }}')/kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

# output
{app=vault-quickstart, password=abc, username=cab}
```

Let's try and lookup another application's secret using our deployed app. This will **not be allowed** by ACL template policy.

Create the new secret for a new app in the same project.

```bash
export APP_NAME=another-app

vault kv put kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME \
  app=$APP_NAME \
  username=baz \
  password=jaz
```

Now try to read it.

```bash
curl -sk -w"\n" https://$(oc get route vault-quickstart --template='{{ .spec.host }}')/kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

# error returned
{"details":"Error id d47c2c4c-0893-4652-b2bd-f9dcb0ab8dfe-2","stack":""}
```

If we check the pod logs we see a `403 permission denied`

```bash
oc logs $(oc get pods -l app=vault-quickstart -o name) | grep -A1 ERROR

# output
ERROR: HTTP Request to /kv/student/foo-apps/another-app failed, error id: b0cbc303-0a76-4b0c-a276-509963bc5259-1
org.jboss.resteasy.spi.UnhandledException: io.quarkus.vault.runtime.client.VaultClientException code=403 body={"errors":["1 error occurred:\n\t* permission denied\n\n"]}

```

Let's try and `Delete` a secret. This will **not be allowed** by ACL template policy.

```bash
export APP_NAME=vault-quickstart

curl -X DELETE -sk -w"\n" https://$(oc get route $APP_NAME --template='{{ .spec.host }}')/kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

{"details":"Error id cb28d8e2-4ff2-4a14-b110-884489b36e00-2","stack":""}
```

Let's manully update the $BASE_DOMAIN-$PROJECT_NAME-kv-read policy with "delete" just to make sure.

```bash
capabilities=["read","list","delete"]
```

![images/acl-delete-test.png](images/acl-delete-test.png)

And retest.

```bash
curl -X DELETE -sk -w"\n" https://$(oc get route $APP_NAME --template='{{ .spec.host }}')/kv/$TEAM_GROUP/$PROJECT_NAME/$APP_NAME

# output
Secret: student/foo-apps/vault-quickstart deleted
```
