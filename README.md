inventory-web-api
=================

*Renamed 2026-08-07 from `inventory-webapp` (repo, directory, and Maven artifact all
renamed); the server-rendered UI moved out to the `inventory-webapp` module in Phase 5.*

The browser-facing API tier: a transparent pass-through of the whole `/api/v1/*`
surface to inventory-server, authenticated with the same bearer tokens
inventory-server issues. This is the stable public surface for the web UI today and
mobile apps later; it holds no session state and serves no HTML.
