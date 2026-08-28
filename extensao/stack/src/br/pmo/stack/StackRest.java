package br.pmo.stack;

import java.io.IOException;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * REST endpoint da Gestao da Stack.
 */
@Path("/stack")
public class StackRest {

  private final StackManager manager = new StackManager();

  @GET
  @Path("/servicos")
  @Produces(MediaType.APPLICATION_JSON)
  public Response servicos() {
    try {
      List<String> servicos = manager.servicos();
      return Response.ok(servicos).build();
    } catch (IOException e) {
      return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
    }
  }

  @GET
  @Path("/dependencias")
  @Produces(MediaType.APPLICATION_JSON)
  public Response dependencias() {
    try {
      StackManager.RelatorioDependencias r = manager.analisarDependencias();
      return Response.ok(r.servicos).build();
    } catch (IOException e) {
      return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
    }
  }
}
