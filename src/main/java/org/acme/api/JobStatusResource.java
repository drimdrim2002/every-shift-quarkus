package org.acme.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.api.dto.ErrorResponse;
import org.acme.api.dto.StatusResponse;
import org.acme.service.JobExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Job Execution 상태 조회 API
 * GET /api/status/{id}
 */
@Path("/api/status")
public class JobStatusResource {

    private static final Logger LOG = LoggerFactory.getLogger(JobStatusResource.class);

    @Inject
    JobExecutionService jobExecutionService;

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus(@PathParam("id") String id) {
        LOG.debug("Status check requested: id={}", id);

        Optional<org.acme.model.JobExecution> job = jobExecutionService.findById(id);

        if (job.isEmpty()) {
            LOG.warn("Execution not found: id={}", id);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Execution not found: " + id))
                    .build();
        }

        return Response.ok(StatusResponse.from(job.get())).build();
    }
}
