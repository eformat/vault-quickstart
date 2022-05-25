package org.acme.quickstart;

import io.quarkus.vault.VaultKVSecretEngine;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/")
public class GreetingResource {

    @Inject
    VaultKVSecretEngine vaultKVSecretEngine;

    @GET
    @Path("/kv/{ns}/{app-name}")
    @Produces(MediaType.TEXT_PLAIN)
    public String getSecrets(@PathParam("ns") String ns, @PathParam("app-name") String appName) {
        return vaultKVSecretEngine.readSecret(ns + "/" + appName).toString();
    }
}
