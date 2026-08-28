package br.pmo.addon;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * REST endpoint do Add-on Manager.
 * 
 * <p>Expõe os métodos do {@link AddonManager} via REST para a interface web.
 * 
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /addon-manager/list} — lista todos os add-ons</li>
 *   <li>{@code POST /addon-manager/activate/{id}} — ativa um add-on</li>
 *   <li>{@code POST /addon-manager/deactivate/{id}} — desativa um add-on</li>
 * </ul>
 */
@Path("/addon-manager")
public class AddonManagerRest {

  private final AddonManager manager = new AddonManager();

  @GET
  @Path("/list")
  @Produces(MediaType.APPLICATION_JSON)
  public Response listar() {
    try {
      List<AddonManager.Addon> addons = manager.listar();
      List<AddonDTO> dtos = addons.stream()
          .map(a -> new AddonDTO(a.id, a.nome, a.ativo, a.versao, a.descricao))
          .collect(Collectors.toList());
      return Response.ok(dtos).build();
    } catch (IOException e) {
      return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
    }
  }

  @POST
  @Path("/activate/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response ativar(@PathParam("id") String id) {
    try {
      AddonManager.Addon a = manager.ativar(id);
      return Response.ok(new AddonDTO(a.id, a.nome, a.ativo, a.versao, a.descricao)).build();
    } catch (Exception e) {
      return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
    }
  }

  @POST
  @Path("/deactivate/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response desativar(@PathParam("id") String id) {
    try {
      AddonManager.Addon a = manager.desativar(id);
      return Response.ok(new AddonDTO(a.id, a.nome, a.ativo, a.versao, a.descricao)).build();
    } catch (Exception e) {
      return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
    }
  }

  public static class AddonDTO {
    public String id;
    public String nome;
    public boolean ativo;
    public String versao;
    public String descricao;

    public AddonDTO() {}

    public AddonDTO(String id, String nome, boolean ativo, String versao, String descricao) {
      this.id = id;
      this.nome = nome;
      this.ativo = ativo;
      this.versao = versao;
      this.descricao = descricao;
    }
  }
}
