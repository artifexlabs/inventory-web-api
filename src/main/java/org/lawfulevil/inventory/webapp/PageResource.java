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
 *
 * The session stores only the token; the user is fetched fresh from
 * inventory-server on every page, so admin changes and token revocations take
 * effect immediately without re-login.
 */
@Path("/")
@Blocking
@Produces(MediaType.TEXT_HTML)
public class PageResource {
  private final static String SESSION_COOKIE = "inv_session";

  /** The per-request view of who is logged in: the token plus the fresh user. */
  private record Ctx(String token, JsonObject user) {
  }

  @Inject
  ServerClient server;

  @Inject
  SessionStore sessions;

  @Inject
  UserLookup userLookup;

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

  @Inject
  Template locations;

  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "inventory.oidc.enabled", defaultValue = "false")
  boolean oidcEnabled;

  @GET
  public Response index() {
    return redirect("/items");
  }

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
    String sessionId = this.sessions.create(result.get().token());
    return Response.seeOther(URI.create("/items"))
        .cookie(new NewCookie.Builder(SESSION_COOKIE).value(sessionId).path("/").httpOnly(true).build()).build();
  }

  @POST
  @Path("/logout")
  public Response doLogout(@CookieParam(SESSION_COOKIE) String sessionId) {
    this.sessions.invalidate(sessionId).ifPresent(token -> {
      this.userLookup.invalidate(token);
      this.server.logout(token);
    });
    return redirect("/login");
  }

  @GET
  @Path("/items")
  public Response itemsPage(@CookieParam(SESSION_COOKIE) String sessionId) {
    return withSession(sessionId, c -> {
      List<Map<String, Object>> all = toMaps(this.server.items(c.token()));
      for (Map<String, Object> m : all) {
        Object q = m.get("quantity");
        Object pv = m.get("parValues");
        Map<?, ?> par = pv instanceof JsonObject jo ? jo.getMap() : pv instanceof Map<?, ?> mm ? mm : null;
        if (q instanceof Number qty && par != null && par.get("minOnHand") instanceof Number min)
          m.put("belowMin", qty.longValue() < min.longValue());
      }
      return this.items.data("items", all).data("user", c.user().getMap());
    });
  }

  @GET
  @Path("/items/{id}")
  public Response itemPage(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id) {
    return withSession(sessionId, c -> {
      Optional<JsonObject> found = this.server.item(c.token(), id);
      if (found.isEmpty())
        return null;
      JsonObject it = found.get();
      List<Map<String, Object>> children = toMaps(
          it.getJsonArray("containedItems", new JsonArray()).stream().map(o -> (JsonObject) o).toList());
      List<Map<String, Object>> containers = toMaps(this.server.containersOf(c.token(), id));
      List<Map<String, Object>> candidates = this.server.items(c.token()).stream()
          .filter(x -> !id.equals(x.getString("id"))).map(JsonObject::getMap).toList();
      List<Map<String, Object>> history = toMaps(this.server.auditFor(c.token(), id, 20));
      List<Map<String, Object>> allLocations = toMaps(this.server.locations(c.token()));
      List<Map<String, Object>> itemAssets = toMaps(this.server.assetsFor(c.token(), id));
      String locationName = it.getString("locationId") == null ? null
          : allLocations.stream().filter(l -> it.getString("locationId").equals(l.get("id")))
              .map(l -> (String) l.get("name")).findFirst().orElse(null);
      return this.item.data("item", it.getMap()).data("children", children).data("containers", containers)
          .data("candidates", candidates).data("history", history).data("locations", allLocations)
          .data("assets", itemAssets).data("locationName", locationName).data("user", c.user().getMap());
    });
  }

  @POST
  @Path("/items/create")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response createItem(@CookieParam(SESSION_COOKIE) String sessionId, @FormParam("name") String name,
      @FormParam("displayName") String displayName, @FormParam("type") String type) {
    return action(sessionId, c -> this.server.createItem(c.token(), name, blankToNull(displayName), blankToNull(type))
        .map(created -> "/items/" + created.getString("id")).orElse("/items"));
  }

  @POST
  @Path("/items/{id}/edit")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response editItem(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id,
      @FormParam("name") String name, @FormParam("displayName") String displayName,
      @FormParam("type") String type, @FormParam("description") String description,
      @FormParam("quantity") String quantity, @FormParam("weightGrams") String weightGrams,
      @FormParam("lengthCm") String lengthCm, @FormParam("widthCm") String widthCm,
      @FormParam("heightCm") String heightCm, @FormParam("locationId") String locationId,
      @FormParam("minOnHand") String minOnHand, @FormParam("maxOnHand") String maxOnHand) {
    return action(sessionId, c -> {
      this.server.item(c.token(), id).ifPresent(existing -> {
        JsonObject updated = existing.copy();
        updated.put("name", name);
        putOrRemove(updated, "displayName", blankToNull(displayName));
        updated.put("type", type == null || type.isBlank() ? "_" : type);
        putOrRemove(updated, "description", blankToNull(description));
        putOrRemoveNumber(updated, "quantity", quantity, Long::parseLong);
        putOrRemoveNumber(updated, "weightGrams", weightGrams, Double::parseDouble);
        if (notBlank(lengthCm) && notBlank(widthCm) && notBlank(heightCm))
          updated.put("dimensionsCm",
              new JsonObject().put("lengthCm", Double.parseDouble(lengthCm.trim()))
                  .put("widthCm", Double.parseDouble(widthCm.trim()))
                  .put("heightCm", Double.parseDouble(heightCm.trim())));
        else
          updated.remove("dimensionsCm");
        putOrRemove(updated, "locationId", blankToNull(locationId));
        if (notBlank(minOnHand) && notBlank(maxOnHand))
          updated.put("parValues", new JsonObject().put("minOnHand", Long.parseLong(minOnHand.trim()))
              .put("maxOnHand", Long.parseLong(maxOnHand.trim())));
        else
          updated.remove("parValues");
        updated.put("timestamp", java.time.Instant.now());
        this.server.updateItem(c.token(), updated);
      });
      return "/items/" + id;
    });
  }

  @POST
  @Path("/items/{id}/delete")
  public Response deleteItem(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id) {
    return action(sessionId, c -> {
      this.server.deleteItem(c.token(), id);
      return "/items";
    });
  }

  @POST
  @Path("/items/{id}/add-to")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response addTo(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id,
      @FormParam("containerId") String containerId) {
    return action(sessionId, c -> {
      this.server.addToContainer(c.token(), containerId, id);
      return "/items/" + id;
    });
  }

  @POST
  @Path("/items/{id}/move-to")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response moveTo(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id,
      @FormParam("containerId") String containerId) {
    return action(sessionId, c -> {
      this.server.moveToContainer(c.token(), id, containerId);
      return "/items/" + id;
    });
  }

  @POST
  @Path("/items/{id}/remove-from")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response removeFrom(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id,
      @FormParam("containerId") String containerId) {
    return action(sessionId, c -> {
      this.server.removeFromContainer(c.token(), containerId, id);
      return "/items/" + id;
    });
  }

  @GET
  @Path("/admin")
  public Response adminPage(@CookieParam(SESSION_COOKIE) String sessionId) {
    return withAdminSession(sessionId, c -> this.admin.data("users", toMaps(this.server.users(c.token())))
        .data("user", c.user().getMap()));
  }

  @POST
  @Path("/admin/users")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response adminCreateUser(@CookieParam(SESSION_COOKIE) String sessionId, @FormParam("email") String email,
      @FormParam("displayName") String displayName, @FormParam("password") String password,
      @FormParam("admin") String adminFlag) {
    return adminAction(sessionId, c -> {
      this.server.createUser(c.token(), email, blankToNull(displayName), password, "on".equals(adminFlag));
      return "/admin";
    });
  }

  @POST
  @Path("/admin/users/{id}/delete")
  public Response adminDeleteUser(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id) {
    return adminAction(sessionId, c -> {
      this.server.deleteUser(c.token(), id);
      return "/admin";
    });
  }

  @POST
  @Path("/admin/users/{id}/admin")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response adminSetAdmin(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id,
      @FormParam("admin") String admin) {
    return adminAction(sessionId, c -> {
      this.server.setAdmin(c.token(), id, Boolean.parseBoolean(admin));
      return "/admin";
    });
  }

  @GET
  @Path("/admin/users/{id}/tokens")
  public Response adminTokens(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id) {
    return withAdminSession(sessionId, c -> this.tokens
        .data("tokens", toMaps(this.server.tokensFor(c.token(), id))).data("userId", id)
        .data("user", c.user().getMap()));
  }

  @POST
  @Path("/admin/tokens/revoke")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response adminRevokeToken(@CookieParam(SESSION_COOKIE) String sessionId, @FormParam("token") String token,
      @FormParam("userId") String userId) {
    return adminAction(sessionId, c -> {
      this.server.revokeToken(c.token(), token);
      return "/admin/users/" + userId + "/tokens";
    });
  }

  @GET
  @Path("/audit")
  public Response auditPage(@CookieParam(SESSION_COOKIE) String sessionId) {
    return withAdminSession(sessionId, c -> this.audit
        .data("events", toMaps(this.server.auditRecent(c.token(), 100, 0))).data("user", c.user().getMap()));
  }

  @GET
  @Path("/locations")
  public Response locationsPage(@CookieParam(SESSION_COOKIE) String sessionId) {
    return withSession(sessionId, c -> this.locations
        .data("locations", toMaps(this.server.locations(c.token()))).data("user", c.user().getMap()));
  }

  @POST
  @Path("/locations/create")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response createLocation(@CookieParam(SESSION_COOKIE) String sessionId, @FormParam("name") String name,
      @FormParam("latitude") String latitude, @FormParam("longitude") String longitude) {
    return action(sessionId, c -> {
      Double lat = notBlank(latitude) ? Double.valueOf(latitude.trim()) : null;
      Double lng = notBlank(longitude) ? Double.valueOf(longitude.trim()) : null;
      this.server.createLocation(c.token(), name, lat, lng);
      return "/locations";
    });
  }

  @POST
  @Path("/locations/{id}/delete")
  public Response deleteLocation(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id) {
    return action(sessionId, c -> {
      this.server.deleteLocation(c.token(), id);
      return "/locations";
    });
  }

  @POST
  @Path("/items/{id}/assets/upload")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  public Response uploadAsset(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id,
      @org.jboss.resteasy.reactive.RestForm("file") org.jboss.resteasy.reactive.multipart.FileUpload file) {
    return action(sessionId, c -> {
      if (file != null && file.uploadedFile() != null) {
        try {
          byte[] data = java.nio.file.Files.readAllBytes(file.uploadedFile());
          String type = file.contentType() == null ? "application/octet-stream" : file.contentType();
          String name = file.fileName() == null || file.fileName().isBlank() ? "unnamed" : file.fileName();
          this.server.uploadAsset(c.token(), id, name, type, data);
        } catch (java.io.IOException e) {
          throw new RuntimeException("could not read upload", e);
        }
      }
      return "/items/" + id;
    });
  }

  @GET
  @Path("/assets/{assetId}")
  public Response asset(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("assetId") String assetId) {
    try {
      Optional<Ctx> ctx = resolve(sessionId);
      if (ctx.isEmpty())
        return redirect("/login");
      return this.server.downloadAsset(ctx.get().token(), assetId)
          .map(a -> Response.ok(a.data(), a.contentType()).build())
          .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    } catch (ServerClient.Unauthorized e) {
      this.sessions.invalidate(sessionId);
      return redirect("/login");
    }
  }

  @POST
  @Path("/assets/{assetId}/delete")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response deleteAsset(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("assetId") String assetId,
      @FormParam("itemId") String itemId) {
    return action(sessionId, c -> {
      this.server.deleteAsset(c.token(), assetId);
      return "/items/" + itemId;
    });
  }

  /** QR scan deep link: the printed label resolves here. */
  @GET
  @Path("/i/{id}")
  public Response deepLink(@PathParam("id") String id) {
    return redirect("/items/" + id);
  }

  @GET
  @Path("/items/{id}/qr.png")
  @Produces("image/png")
  public Response qrPng(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id) {
    try {
      Optional<Ctx> ctx = resolve(sessionId);
      if (ctx.isEmpty())
        return redirect("/login");
      return this.server.qrPng(ctx.get().token(), id).map(png -> Response.ok(png, "image/png").build())
          .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    } catch (ServerClient.Unauthorized e) {
      this.sessions.invalidate(sessionId);
      return redirect("/login");
    }
  }

  @POST
  @Path("/items/{id}/print-label")
  public Response printLabel(@CookieParam(SESSION_COOKIE) String sessionId, @PathParam("id") String id) {
    return action(sessionId, c -> {
      this.server.printLabel(c.token(), id);
      return "/items/" + id;
    });
  }

  // --- session plumbing -------------------------------------------------

  private static boolean isAdmin(Ctx c) {
    return Boolean.TRUE.equals(c.user().getBoolean("admin"));
  }

  /** Resolve the session to a token plus the CURRENT user (throws Unauthorized). */
  private Optional<Ctx> resolve(String sessionId) {
    return this.sessions.token(sessionId).map(token -> new Ctx(token, this.userLookup.me(token)));
  }

  private Response withSession(String sessionId, java.util.function.Function<Ctx, Object> render) {
    try {
      Optional<Ctx> ctx = resolve(sessionId);
      if (ctx.isEmpty())
        return redirect("/login");
      Object result = render.apply(ctx.get());
      if (result == null)
        return Response.status(Response.Status.NOT_FOUND).entity("No such item").build();
      return Response.ok(((TemplateInstance) result).render()).build();
    } catch (ServerClient.Unauthorized e) {
      this.sessions.invalidate(sessionId);
      return redirect("/login");
    }
  }

  private Response withAdminSession(String sessionId, java.util.function.Function<Ctx, Object> render) {
    try {
      Optional<Ctx> ctx = resolve(sessionId);
      if (ctx.isEmpty())
        return redirect("/login");
      if (!isAdmin(ctx.get()))
        return Response.status(Response.Status.FORBIDDEN).entity("Admin access required").build();
      return Response.ok(((TemplateInstance) render.apply(ctx.get())).render()).build();
    } catch (ServerClient.Unauthorized e) {
      this.sessions.invalidate(sessionId);
      return redirect("/login");
    }
  }

  /** Run a mutation and redirect to wherever the action says to go next. */
  private Response action(String sessionId, java.util.function.Function<Ctx, String> act) {
    try {
      Optional<Ctx> ctx = resolve(sessionId);
      if (ctx.isEmpty())
        return redirect("/login");
      return redirect(act.apply(ctx.get()));
    } catch (ServerClient.Unauthorized e) {
      this.sessions.invalidate(sessionId);
      return redirect("/login");
    }
  }

  private Response adminAction(String sessionId, java.util.function.Function<Ctx, String> act) {
    try {
      Optional<Ctx> ctx = resolve(sessionId);
      if (ctx.isEmpty())
        return redirect("/login");
      if (!isAdmin(ctx.get()))
        return Response.status(Response.Status.FORBIDDEN).entity("Admin access required").build();
      return redirect(act.apply(ctx.get()));
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
}
