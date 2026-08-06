/*
 * @formatter:off
 * Copyright © 2019 admin (admin@infrastructurebuilder.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * @formatter:on
 */
package org.lawfulevil.inventory.webapp;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

/**
 * The vanilla UI: login grants a token (held server-side in the session),
 * items browse with two-way containment navigation, and move/add-to-container
 * actions post back here and call inventory-server.
 */
@Path("/")
@Blocking
@Produces(MediaType.TEXT_HTML)
public class PageResource {
  private final static String SESSION_COOKIE = "inv_session";

  @Inject
  ServerClient server;

  @Inject
  SessionStore sessions;

  @Inject
  Template login;

  @Inject
  Template items;

  @Inject
  Template item;

  @Inject
  Template admin;

  @Inject
  Template tokens;

  @Inject
  Template audit;

  @GET
  public Response index() {
    return redirect("/items");
  }

  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "inventory.oidc.enabled", defaultValue = "false")
  boolean oidcEnabled;

  @GET
  @Path("/login")
  public TemplateInstance loginPage() {
    return this.login.data("error", null).data("oidcEnabled", this.oidcEnabled);
  }

  @POST
  @Path("/login")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response doLogin(@FormParam("email") String email, @FormParam("password") String password) {
    Optional<ServerClient.Login> result = this.server.login(email, password);
    if (result.isEmpty())
      return Response.ok(
          this.login.data("error", "Invalid email or password").data("oidcEnabled", this.oidcEnabled).render())
          .build();
    String sessionId = this.sessions.create(result.get().token(), result.get().user());
    return Response.seeOther(URI.create("/items"))
        .cookie(new NewCookie.Builder(SESSION_COOKIE).value(sessionId).path("/").httpOnly(true).build()).build();
  }

  @POST
  @Path("/logout")
  public Response doLogout(@CookieParam(SESSION_COOKIE) String sessionId) {
    this.sessions.invalidate(sessionId).ifPresent(s -> this.server.logout(s.token()));
    return redirect("/login");
  }

  @GET
  @Path("/items")
  public Response itemsPage(@CookieParam(SESSION_COOKIE) String sessionId) {
    return withSession(sessionId, s -> this.items
        .data("items", toMaps(this.server.items(s.token()))).data("user", s.user().getMap()));
  }

  @GET
  @Path("/items/{id}")
  public Response itemPage(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id) {
    return withSession(sessionId, s -> {
      Optional<JsonObject> found = this.server.item(s.token(), id);
      if (found.isEmpty())
        return null;
      JsonObject it = found.get();
      List<Map<String, Object>> children = toMaps(
          it.getJsonArray("containedItems", new JsonArray()).stream().map(o -> (JsonObject) o).toList());
      List<Map<String, Object>> containers = toMaps(this.server.containersOf(s.token(), id));
      List<Map<String, Object>> candidates = this.server.items(s.token()).stream()
          .filter(c -> !id.equals(c.getString("id"))).map(JsonObject::getMap).toList();
      List<Map<String, Object>> history = toMaps(this.server.auditFor(s.token(), id, 20));
      return this.item.data("item", it.getMap()).data("children", children).data("containers", containers)
          .data("candidates", candidates).data("history", history).data("user", s.user().getMap());
    });
  }

  @POST
  @Path("/items/create")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response createItem(@CookieParam(SESSION_COOKIE) String sessionId, @FormParam("name") String name,
      @FormParam("displayName") String displayName, @FormParam("type") String type) {
    Optional<SessionStore.Session> session = this.sessions.get(sessionId);
    if (session.isEmpty())
      return redirect("/login");
    try {
      return this.server.createItem(session.get().token(), name, blankToNull(displayName), blankToNull(type))
          .map(created -> redirect("/items/" + created.getString("id"))).orElseGet(() -> redirect("/items"));
    } catch (ServerClient.Unauthorized e) {
      this.sessions.invalidate(sessionId);
      return redirect("/login");
    }
  }

  @POST
  @Path("/items/{id}/edit")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response editItem(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id,
      @FormParam("name") String name, @FormParam("displayName") String displayName,
      @FormParam("type") String type, @FormParam("description") String description,
      @FormParam("quantity") String quantity, @FormParam("weightGrams") String weightGrams,
      @FormParam("lengthCm") String lengthCm, @FormParam("widthCm") String widthCm,
      @FormParam("heightCm") String heightCm) {
    return containmentAction(sessionId, id, s -> this.server.item(s.token(), id).map(existing -> {
      JsonObject updated = existing.copy();
      updated.put("name", name);
      putOrRemove(updated, "displayName", blankToNull(displayName));
      updated.put("type", type == null || type.isBlank() ? "_" : type);
      putOrRemove(updated, "description", blankToNull(description));
      putOrRemoveNumber(updated, "quantity", quantity, Long::parseLong);
      putOrRemoveNumber(updated, "weightGrams", weightGrams, Double::parseDouble);
      if (notBlank(lengthCm) && notBlank(widthCm) && notBlank(heightCm))
        updated.put("dimensionsCm", new JsonObject().put("lengthCm", Double.parseDouble(lengthCm.trim()))
            .put("widthCm", Double.parseDouble(widthCm.trim())).put("heightCm", Double.parseDouble(heightCm.trim())));
      else
        updated.remove("dimensionsCm");
      updated.put("timestamp", java.time.Instant.now());
      return this.server.updateItem(s.token(), updated);
    }).orElse(false));
  }

  @POST
  @Path("/items/{id}/delete")
  public Response deleteItem(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id) {
    Optional<SessionStore.Session> session = this.sessions.get(sessionId);
    if (session.isEmpty())
      return redirect("/login");
    try {
      this.server.deleteItem(session.get().token(), id);
      return redirect("/items");
    } catch (ServerClient.Unauthorized e) {
      this.sessions.invalidate(sessionId);
      return redirect("/login");
    }
  }

  @GET
  @Path("/admin")
  public Response adminPage(@CookieParam(SESSION_COOKIE) String sessionId) {
    return withAdminSession(sessionId, s -> this.admin.data("users", toMaps(this.server.users(s.token())))
        .data("user", s.user().getMap()));
  }

  @POST
  @Path("/admin/users")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response adminCreateUser(@CookieParam(SESSION_COOKIE) String sessionId, @FormParam("email") String email,
      @FormParam("displayName") String displayName, @FormParam("password") String password,
      @FormParam("admin") String adminFlag) {
    return adminAction(sessionId,
        s -> this.server.createUser(s.token(), email, blankToNull(displayName), password, "on".equals(adminFlag))
            .isPresent());
  }

  @POST
  @Path("/admin/users/{id}/delete")
  public Response adminDeleteUser(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id) {
    return adminAction(sessionId, s -> this.server.deleteUser(s.token(), id));
  }

  @POST
  @Path("/admin/users/{id}/admin")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response adminSetAdmin(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id,
      @FormParam("admin") String admin) {
    return adminAction(sessionId, s -> this.server.setAdmin(s.token(), id, Boolean.parseBoolean(admin)));
  }

  @GET
  @Path("/admin/users/{id}/tokens")
  public Response adminTokens(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id) {
    return withAdminSession(sessionId, s -> this.tokens.data("tokens", toMaps(this.server.tokensFor(s.token(), id)))
        .data("userId", id).data("user", s.user().getMap()));
  }

  @POST
  @Path("/admin/tokens/revoke")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response adminRevokeToken(@CookieParam(SESSION_COOKIE) String sessionId, @FormParam("token") String token,
      @FormParam("userId") String userId) {
    Optional<SessionStore.Session> session = this.sessions.get(sessionId);
    if (session.isEmpty() || !isAdmin(session.get()))
      return redirect("/login");
    try {
      this.server.revokeToken(session.get().token(), token);
      return redirect("/admin/users/" + userId + "/tokens");
    } catch (ServerClient.Unauthorized e) {
      this.sessions.invalidate(sessionId);
      return redirect("/login");
    }
  }

  @GET
  @Path("/audit")
  public Response auditPage(@CookieParam(SESSION_COOKIE) String sessionId) {
    return withAdminSession(sessionId, s -> this.audit
        .data("events", toMaps(this.server.auditRecent(s.token(), 100, 0))).data("user", s.user().getMap()));
  }

  private static boolean isAdmin(SessionStore.Session s) {
    return Boolean.TRUE.equals(s.user().getBoolean("admin"));
  }

  private Response withAdminSession(String sessionId,
      java.util.function.Function<SessionStore.Session, Object> render) {
    Optional<SessionStore.Session> session = this.sessions.get(sessionId);
    if (session.isEmpty())
      return redirect("/login");
    if (!isAdmin(session.get()))
      return Response.status(Response.Status.FORBIDDEN).entity("Admin access required").build();
    return withSession(sessionId, render);
  }

  private Response adminAction(String sessionId, java.util.function.Function<SessionStore.Session, Boolean> action) {
    Optional<SessionStore.Session> session = this.sessions.get(sessionId);
    if (session.isEmpty())
      return redirect("/login");
    if (!isAdmin(session.get()))
      return Response.status(Response.Status.FORBIDDEN).entity("Admin access required").build();
    try {
      action.apply(session.get());
      return redirect("/admin");
    } catch (ServerClient.Unauthorized e) {
      this.sessions.invalidate(sessionId);
      return redirect("/login");
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static void putOrRemove(JsonObject j, String key, String value) {
    if (value == null)
      j.remove(key);
    else
      j.put(key, value);
  }

  private static void putOrRemoveNumber(JsonObject j, String key, String raw,
      java.util.function.Function<String, Number> parse) {
    if (notBlank(raw))
      j.put(key, parse.apply(raw.trim()));
    else
      j.remove(key);
  }

  @POST
  @Path("/items/{id}/add-to")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response addTo(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id,
      @FormParam("containerId") String containerId) {
    return containmentAction(sessionId, id, s -> this.server.addToContainer(s.token(), containerId, id));
  }

  @POST
  @Path("/items/{id}/move-to")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response moveTo(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id,
      @FormParam("containerId") String containerId) {
    return containmentAction(sessionId, id, s -> this.server.moveToContainer(s.token(), id, containerId));
  }

  @POST
  @Path("/items/{id}/remove-from")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response removeFrom(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id,
      @FormParam("containerId") String containerId) {
    return containmentAction(sessionId, id, s -> this.server.removeFromContainer(s.token(), containerId, id));
  }

  private Response containmentAction(String sessionId, String itemId,
      java.util.function.Function<SessionStore.Session, Boolean> action) {
    Optional<SessionStore.Session> session = this.sessions.get(sessionId);
    if (session.isEmpty())
      return redirect("/login");
    try {
      action.apply(session.get());
      return redirect("/items/" + itemId);
    } catch (ServerClient.Unauthorized e) {
      this.sessions.invalidate(sessionId);
      return redirect("/login");
    }
  }

  private Response withSession(String sessionId,
      java.util.function.Function<SessionStore.Session, Object> render) {
    Optional<SessionStore.Session> session = this.sessions.get(sessionId);
    if (session.isEmpty())
      return redirect("/login");
    try {
      Object result = render.apply(session.get());
      if (result == null)
        return Response.status(Response.Status.NOT_FOUND).entity("No such item").build();
      return Response.ok(((TemplateInstance) result).render()).build();
    } catch (ServerClient.Unauthorized e) {
      this.sessions.invalidate(sessionId);
      return redirect("/login");
    }
  }

  private static Response redirect(String location) {
    return Response.seeOther(URI.create(location)).build();
  }

  private static List<Map<String, Object>> toMaps(List<JsonObject> list) {
    return list.stream().map(JsonObject::getMap).toList();
  }
}
