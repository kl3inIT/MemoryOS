# Landing page delivery

The public site at `https://vanda.app` is the static [`landing/`](../../landing) package. CI verifies it, builds one nginx image and, on main, publishes that image by digest. An operator deploys it on the staging VPS as the separate Compose project `memoryos-landing` behind Nginx Proxy Manager. The application deployment script never starts, stops or rolls it back. Design and decisions: [MEM-82](../increments/active/mem-82-landing-page/design.md).

## Release identity

`CI Gate` requires the `landing` job: lint, format, component and metadata tests, the production build with its font-asset assertion, TypeScript, the image build, [`smoke-image.sh`](../../landing/scripts/smoke-image.sh) and Compose validation. After the gate succeeds on main, `Publish landing` pushes the preserved image as `ghcr.io/kl3init/memoryos-landing:sha-<sha>-<run>-<attempt>` and records its digest in the job summary and in the `landing-release-<sha>-<attempt>` artifact (`landing.env`). Deploy only such a digest; there is no mutable tag.

## Server layout

| Path | Content |
| --- | --- |
| `/apps/memoryos-landing/compose.landing.yaml` | `infrastructure/deployment/compose.landing.yaml` from the released revision |
| `/apps/memoryos-landing/landing.env` | `MEMORYOS_LANDING_IMAGE=<digest reference>` from the release artifact |
| `/apps/memoryos-landing/landing.env.previous` | The last accepted reference, kept for rollback |

Run the commands below on the VPS from `/apps/memoryos-landing`.

## Deploy or update the container

1. Keep the accepted reference: `cp landing.env landing.env.previous` (skip on the first deployment).
2. Write `landing.env` from the release artifact and copy `compose.landing.yaml` from the same revision.
3. Pull with a temporary GHCR credential that can read packages, remove the credential, then start:

   ```sh
   docker login ghcr.io --username <github-user>   # read:packages token, entered at the prompt
   docker compose --env-file landing.env --file compose.landing.yaml pull
   docker logout ghcr.io
   docker compose --env-file landing.env --file compose.landing.yaml up --detach --wait
   ```

4. Check the served headers from inside the container:

   ```sh
   docker exec memoryos-landing wget -q -S -O /dev/null http://127.0.0.1:8080/
   ```

## First publication of vanda.app

Before changing anything, record the current Cloudflare state for `vanda.app`: an export of the DNS records, the redirect rule to `roll-bits.com`, and the output of `nslookup -type=mx vanda.app`. Replacing the redirect was approved by the owner on 2026-09-11.

1. Deploy the container as above.
2. In Cloudflare for `vanda.app`:
   - Delete the rule that redirects `vanda.app` to `roll-bits.com`.
   - Set `vanda.app` `A` to the staging VPS public IPv4 `72.62.193.33`, proxy status **DNS only**.
   - Set `www.vanda.app` `A` to the same address, **DNS only**.
   - Leave MX, SPF/DKIM/DMARC TXT and verification records unchanged.
3. Wait until `nslookup vanda.app 1.1.1.1` and `nslookup www.vanda.app 1.1.1.1` return the VPS address.
4. In Nginx Proxy Manager:
   - Proxy host `vanda.app` → `http://memoryos-landing:8080`, Block Common Exploits on, WebSockets off. SSL: new Let's Encrypt certificate, Force SSL, HTTP/2, HSTS on without subdomains.
   - Redirection host `www.vanda.app` → `https://vanda.app`, HTTP 301, preserve path, its own Let's Encrypt certificate with Force SSL.
5. Verify from outside the server:

   ```sh
   curl -sSI https://vanda.app/                                          # 200
   curl -sSI https://www.vanda.app/                                      # 301, Location: https://vanda.app/
   curl -sS -o /dev/null -w '%{http_code}\n' https://vanda.app/missing   # 404
   nslookup -type=mx vanda.app                                           # equals the recorded MX set
   ```

   The 200 response carries `Strict-Transport-Security`, the Content Security Policy, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy` and `Permissions-Policy`.
6. Run Lighthouse (mobile) against `https://vanda.app/`. Record the scores, date and deployed digest in MEM-82.

## Roll back

Container: restore the previous reference and start it. Pull it first with a temporary credential if the image is no longer on the server.

```sh
cp landing.env.previous landing.env
docker compose --env-file landing.env --file compose.landing.yaml up --detach --wait
```

Publication: restore the Cloudflare records and redirect rule recorded before step 2. The Nginx Proxy Manager hosts can stay; without DNS they receive no traffic.

## Boundaries

- The site shares the staging VPS; its availability follows that server and Nginx Proxy Manager.
- The container publishes no host port; Nginx Proxy Manager reaches it over `proxy-network`.
- Never add `compose.landing.yaml` to the application deployment or its image to `images.env`.
