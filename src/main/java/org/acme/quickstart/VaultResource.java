package org.acme.quickstart;

import io.quarkus.vault.VaultKVSecretEngine;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Path("/")
public class VaultResource {

    @Inject
    VaultKVSecretEngine vaultKVSecretEngine;

    @GET
    @Path("/kv/{group}/{ns}/{app-name}")
    @Produces(MediaType.TEXT_PLAIN)
    public String getSecrets(@PathParam("group") String group, @PathParam("ns") String ns, @PathParam("app-name") String appName) {
        return vaultKVSecretEngine.readSecret(group + "/" + ns + "/" + appName).toString();
    }

    @DELETE
    @Path("/kv/{group}/{ns}/{app-name}")
    @Produces(MediaType.TEXT_PLAIN)
    public String deleteSecrets(@PathParam("group") String group, @PathParam("ns") String ns, @PathParam("app-name") String appName) {
        vaultKVSecretEngine.deleteSecret(group + "/" + ns + "/" + appName);
        return "Secret: " + group + "/" + ns + "/" + appName + " deleted";
    }

}
