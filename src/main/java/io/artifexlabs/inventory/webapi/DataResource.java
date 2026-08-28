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
   * "Where are there duplicated sections in my media inventory?" — asked of everything, not of one medium.
   *
   * <p>
   * The medium-scoped form is {@code GET /api/v1/items/{id}/data/sections}; it is the same handler with a target. Both
   * take the same parameters, described on {@link #sectionQuery}.
   */
  @GET
  @Path("/sections")
  @Produces(MediaType.APPLICATION_JSON)
  public CompletionStage<Response> sections(@QueryParam("match") String match, @QueryParam("scope") String scope,
      @QueryParam("minFiles") Integer minFiles, @QueryParam("minBytes") Long minBytes,
      @QueryParam("minDepth") Integer minDepth, @QueryParam("page") Integer page, @QueryParam("size") Integer size) {
    return BusResponses.respond(
        this.bus.request(BusActions.DATA_SECTIONS, null,
            sectionQuery(match, scope, minFiles, minBytes, minDepth, page, size)),
        found -> Response.ok(((JsonArray) found).encode()).build());
  }

  /**
   * Turn the query string into a section query, omitting anything absent so the API's own defaults apply.
   *
   * <p>
   * {@code match} is {@code structure} (names and sizes, answerable the moment a manifest lands), {@code merkle} (names
   * and content, proof of a copy) or {@code content} (content only, so renamed files still match). {@code scope} is
   * {@code across_media}, {@code within_medium} or {@code both} — and within-medium is not an edge case, because a
   * snapshotting filesystem keeps generations of one tree on a single disc.
   *
   * <p>
   * <b>Lowering {@code minFiles} is how you drown.</b> On the measured tree 46.7% of directories hold two files or
   * fewer and 7,492 held nothing but a {@code pom.xml}; the default floor of 8 exists to keep those coincidences out of
   * the answer. Depth alone does not do it — those directories sit at many depths.
   */
  static JsonObject sectionQuery(String match, String scope, Integer minFiles, Long minBytes, Integer minDepth,
      Integer page, Integer size) {
    JsonObject q = new JsonObject();
    if (match != null && !match.isBlank())
      q.put("match", match);
    if (scope != null && !scope.isBlank())
      q.put("scope", scope);
    if (minFiles != null)
      q.put("minFiles", minFiles);
    if (minBytes != null)
      q.put("minBytes", minBytes);
    if (minDepth != null)
      q.put("minDepth", minDepth);
    if (page != null)
      q.put("page", page);
    if (size != null)
      q.put("size", size);
    return q;
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

  /**
   * Pick a text manifest parser by SHAPE rather than by asking the caller to declare one. A find line is TAB-separated;
   * a {@code sha256sum} line never contains a tab. Guessing is acceptable here precisely because the two formats cannot
   * be confused — and a wrong guess would fail loudly on the very first line rather than quietly mis-parse.
   */
  static JsonArray parseManifestText(String body) {
    if (body == null || body.isBlank())
      return new JsonArray();
    for (String raw : body.split("\r?\n")) {
      if (raw.strip().isEmpty())
        continue;
      return raw.indexOf('\t') >= 0 ? parseFindLines(body) : parseDigestLines(body);
    }
    return new JsonArray();
  }

  /**
   * Parse a {@code find}-shaped manifest: {@code <size>\t<mtime>\t<path>}, one line per file, produced by
   *
   * <pre>
   *   find /mnt/x -type f       -printf '%s\t%TFT%TT\t%p\n'
   *   find /mnt/x -type d -empty -printf '\t\t%p/\n'
   * </pre>
   *
   * <b>This, not {@code sha256sum}, is what a manifest is now.</b> A manifest says what EXISTS; hashing reads every
   * byte. {@code find} describes a 120 TB tree in minutes and costs no reads, which is the entire reason hashing could
   * be moved off the ingest path. {@code sha256sum} output always presupposed the hashing we now do afterwards.
   *
   * <p>
   * Two consequences worth the format change: sizes become REAL — {@link #parseDigestLines} has always written
   * {@code 0L}, which would silently disable the size floor every duplicate-section query depends on — and a trailing
   * {@code /} lets an EMPTY directory be named, without which two media differing only by an empty folder hash
   * identically.
   *
   * <p>
   * Size and mtime may both be blank (that is what the empty-directory line above produces). Anything else malformed is
   * refused by line number rather than skipped, because a partially-read manifest claims a medium holds less than it
   * does.
   */
  static JsonArray parseFindLines(String body) {
    List<JsonObject> entries = new ArrayList<>();
    if (body == null)
      return new JsonArray();
    int lineNumber = 0;
    for (String raw : body.split("\r?\n")) {
      lineNumber++;
      if (raw.strip().isEmpty())
        continue;
      // split on TAB only: paths legitimately contain spaces, and a media tree
      // is full of them
      String[] parts = raw.split("\t", 3);
      if (parts.length < 3)
        throw new IllegalArgumentException("line " + lineNumber + " is not '<size>\\t<mtime>\\t<path>': " + raw);
      String sizeText = parts[0].strip();
      String mtimeText = parts[1].strip();
      String path = parts[2];
      long size;
      try {
        size = sizeText.isEmpty() ? 0L : Long.parseLong(sizeText);
      } catch (NumberFormatException bad) {
        throw new IllegalArgumentException("line " + lineNumber + " has an unreadable size: " + sizeText);
      }
      java.time.Instant modified = null;
      if (!mtimeText.isEmpty())
        try {
          // find's %TFT%TT is local time without a zone; treat it as UTC rather
          // than guessing the scanning machine's offset
          modified = java.time.LocalDateTime.parse(mtimeText.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException bad) {
          throw new IllegalArgumentException("line " + lineNumber + " has an unreadable timestamp: " + mtimeText);
        }
      // no hash: that is the point. The async hasher fills it in later.
      entries.add(new DataEntry(path, size, null, null, null, modified, List.of()).toJson());
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
