/*
 * @formatter:off
 * Copyright © 2019 admin (admin@artifexlabs.io)
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
package io.artifexlabs.inventory.webapi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.HashAlgorithm;
import io.artifexlabs.inventory.api.bus.BusActions;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Content-addressed lookup across every medium (ongoing item 6).
 *
 * <p>
 * The item-scoped half of this feature — describing a medium, listing it, renaming a path — lives on
 * {@link ItemsResource} rather than here, because JAX-RS root-resource matching does not backtrack: a resource rooted
 * at {@code /api/v1/items} claims every path beneath it, so a second resource declaring {@code /items/{id}/...} would
 * simply never be reached (the same trap recorded in PLAN.md's eighth milestone).
 *
 * <p>
 * <b>Ingestion needs no client tool.</b> The manifest endpoint accepts JSON entries or, equivalently,
 * {@code text/plain} in {@code sha256sum} format — which is what {@code sha256sum} (Linux), {@code shasum -a 256}
 * (macOS), and {@code b3sum} already print. So describing a disc is one pipe:
 *
 * <pre>
 * find . -type f -exec sha256sum {} + | curl -X PUT --data-binary @- \
 *   -H 'Content-Type: text/plain' .../api/v1/items/{id}/data/manifest
 * </pre>
 *
 * The plain-text form carries paths and hashes only; sizes, mime types, and archive decomposition need the JSON form,
 * because those are facts the coreutils line format cannot express.
 */
@Path("/api/v1/data")
public class DataResource {

  @Inject
  BusClient bus;

  /** "Which disc has this file?" — the inventory question, asked of data. */
  @GET
  @Path("/by-hash/{algorithm}/{hash}")
  @Produces(MediaType.APPLICATION_JSON)
  public CompletionStage<Response> byHash(@PathParam("algorithm") String algorithm, @PathParam("hash") String hash) {
    return BusResponses.respond(
        this.bus.request(BusActions.DATA_BY_HASH, null, new JsonObject().put("algorithm", algorithm).put("hash", hash)),
        found -> Response.ok(((JsonArray) found).encode()).build());
  }

  /**
   * Parse {@code sha256sum}-style lines: {@code <hex>  <path>}, two spaces by convention but any run of whitespace
   * works, and a {@code *} binary marker is tolerated. Blank lines are skipped; anything else is refused loudly rather
   * than silently dropped, because a partially-read manifest would claim a medium holds less than it does.
   */
  static JsonArray parseDigestLines(String body) {
    List<JsonObject> entries = new ArrayList<>();
    if (body == null)
      return new JsonArray();
    int lineNumber = 0;
    for (String raw : body.split("\r?\n")) {
      lineNumber++;
      String line = raw.strip();
      if (line.isEmpty())
        continue;
      int split = indexOfWhitespace(line);
      if (split < 0)
        throw new IllegalArgumentException("line " + lineNumber + " is not '<hash>  <path>': " + line);
      String hash = line.substring(0, split).toLowerCase(java.util.Locale.ROOT);
      String path = line.substring(split).stripLeading();
      if (path.startsWith("*")) // coreutils' binary-mode marker
        path = path.substring(1);
      final int at = lineNumber;
      HashAlgorithm algorithm = byDigestLength(hash)
          .orElseThrow(() -> new IllegalArgumentException("line " + at + " has no recognizable digest"));
      // build through DataEntry so paths normalize and hashes validate here,
      // where the error can still name the offending line
      entries.add(DataEntry.of(path, 0L, algorithm, hash).toJson());
    }
    return new JsonArray(List.copyOf(entries));
  }

  private static java.util.Optional<HashAlgorithm> byDigestLength(String hash) {
    for (HashAlgorithm a : HashAlgorithm.values())
      if (a.accepts(hash))
        return java.util.Optional.of(a);
    return java.util.Optional.empty();
  }

  private static int indexOfWhitespace(String line) {
    for (int i = 0; i < line.length(); i++)
      if (Character.isWhitespace(line.charAt(i)))
        return i;
    return -1;
  }
}
