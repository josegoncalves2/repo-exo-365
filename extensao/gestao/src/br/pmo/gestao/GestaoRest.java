package br.pmo.gestao;

import java.io.IOException;
import java.nio.file.Paths;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * REST endpoint da Gestao de Backup e Migracao.
 */
@javax.ws.rs.Path("/gestao")
public class GestaoRest {

  private final br.pmo.gestao.GestaoPlataforma gestao = new br.pmo.gestao.GestaoPlataforma();

  @javax.ws.rs.GET
  @javax.ws.rs.Path("/validar")
  @Produces(MediaType.APPLICATION_JSON)
  public Response validar() {
    br.pmo.gestao.GestaoPlataforma.Relatorio r = gestao.validar();
    return Response.ok("{\"ok\":" + r.okCount + ",\"falhas\":" + r.falhaCount + ",\"tudoOk\":" + r.tudoOk() + "}").build();
  }

  @javax.ws.rs.POST
  @javax.ws.rs.Path("/snapshot")
  @Produces(MediaType.APPLICATION_JSON)
  public Response snapshot() {
    try {
      java.nio.file.Path snap = gestao.criarSnapshot();
      return Response.ok("{\"snapshot\":\"" + snap.toString() + "\"}").build();
    } catch (IOException e) {
      return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
    }
  }
}
