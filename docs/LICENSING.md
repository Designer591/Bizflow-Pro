# Licensing

## Design
- Licences are **Ed25519-signed** text tokens (`payload.signature`). The payload holds licence id, edition, kind, licensee, device limit, issue and expiry dates and grace days. No object deserialisation is used.
- The **private key** lives only in the licence server's data folder (`signing-private.key`). The desktop app contains **only the public key** (`desktop/src/main/resources/licence-public-key.txt`). There are no hard-coded activation codes.
- Validation is **offline**: the app verifies the signature locally, so shops without internet keep working. Online activation/refresh is optional.
- Editions: **Starter** (POS, inventory, customers, basic reports, backup), **Professional** (+ suppliers/purchasing, finance, employees/shifts, advanced reports, exports), **Enterprise** (+ multi-branch). Kinds: trial, perpetual, annual, monthly.
- States shown in the app: valid, expiring soon (14 days), **grace period** (still usable with warnings), expired (locked to the licence screen, data untouched), invalid, device limit reached, clock tampering (system date set back).
- Without a licence file the app runs a **14-day evaluation** (first-run date stored in settings).

## One-time setup (EROLITECHNOLOGIES)
```
mvn -pl licence-server -am package
# deploy the jar together with the libs/ folder next to it (target/libs)
java -jar licence-server/target/licence-server-1.0.0.jar init --data ./data
```
This prints/writes the public key to `data/licence-public-key.txt`. Copy it to `desktop/src/main/resources/licence-public-key.txt` and rebuild the app. **Back up `data/signing-private.key` offline. If it is lost or leaked, every issued licence must be replaced.** `init` refuses to overwrite an existing key.

## Running the licence service
```
set LICENCE_ADMIN_TOKEN=<random, 24+ characters>
set LICENCE_KEYSTORE_PASSWORD=<keystore password>
java -jar licence-server-1.0.0.jar serve --data ./data --port 8443 --keystore server.p12
```
Host it behind HTTPS with a real certificate (`--dev` allows plain HTTP for local testing only). The desktop app refuses non-HTTPS server addresses except localhost.

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /v1/activate` (`key`, `device`) | licence key | Activate a device, returns the signed licence |
| `POST /v1/refresh` (`key`, `device`) | licence key | Pick up renewals/upgrades |
| `POST /admin/issue` (`licensee, edition, kind, devices, days, grace`) | `X-Admin-Token` | Create a licence, returns the key to give the customer |
| `POST /admin/renew` (`id, days`) | `X-Admin-Token` | Extend a licence |
| `POST /admin/revoke` (`id, revoked`) | `X-Admin-Token` | Revoke / restore |
| `GET /admin/list` | `X-Admin-Token` | List licences and activations |

## Selling a licence
1. `POST /admin/issue` with the customer name, edition, kind, device count, days and grace days. You get a **licence key**.
2. Customer: Settings > Licence > **Online activation** (key + server address), or **Offline activation**: send you the *device ID* shown there; you run `java -jar licence-server-1.0.0.jar issue-offline --data ./data --key <KEY> --device <DEVICEID>` and send back the text to paste.
3. Renewal: `POST /admin/renew`, then the customer clicks **Check for renewal** (online) or pastes a newly issued licence (offline).

## Not yet built
An administrator-facing **web/desktop console** for the licence server. Today administration is by the HTTPS admin endpoints above (curl/Postman) and the CLI. Payment collection and customer portal are out of scope.
