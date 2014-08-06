/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.neutron.loadbalancer;

import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.loadbalancer.LBMediaType;
import org.midonet.cluster.data.neutron.LoadBalancerApi;
import org.midonet.cluster.data.neutron.loadbalancer.VIP;
import org.midonet.event.neutron.VipEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;

import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;

public class VipResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
        VipResource.class);
    private final static VipEvent VIP_EVENT = new VipEvent();

    private final LoadBalancerApi api;

    @Inject
    public VipResource(RestApiConfig config, UriInfo uriInfo,
                       SecurityContext context, LoadBalancerApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(LBMediaType.VIP_JSON_V1)
    @Produces(LBMediaType.VIP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(VIP vip)
        throws SerializationException, StateAccessException {
        log.info("VipResource.create entered {}", vip);

        try {
            api.createVip(vip);
            VIP_EVENT.create(vip.id, vip);
            log.info("VipResource.create exiting {}", vip);
            return Response.created(
                LBUriBuilder.getVip(getBaseUri(), vip.id))
                .entity(vip).build();
        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
        throws SerializationException, StateAccessException {
        log.info("VipResource.delete entered {}", id);
        api.deleteVip(id);
        VIP_EVENT.delete(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(LBMediaType.VIP_JSON_V1)
    @Produces(LBMediaType.VIP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, VIP vip)
        throws SerializationException, StateAccessException,
               BridgeZkManager.VxLanPortIdUpdateException {
        log.info("VipResource.update entered {}", vip);

        try {
            api.updateVip(id, vip);
            VIP_EVENT.update(id, vip);
            log.info("VipResource.update exiting {}", vip);
            return Response.ok(
                LBUriBuilder.getVip(getBaseUri(), vip.id))
                .entity(vip).build();
        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}