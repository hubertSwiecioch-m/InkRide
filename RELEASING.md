# Releasing InkRide

Release APKs are built automatically by the [`Release`](.github/workflows/release.yml)
GitHub Actions workflow whenever changes land on `main` (for example, after a pull
request is merged). The workflow runs the unit tests, builds a **signed** release
APK, and publishes it as a GitHub Release.

You can also trigger it manually from the **Actions → Release → Run workflow** menu.

## One-time setup: signing key

The release build is signed with an upload keystore. Generate one locally (do this
once and keep the `.jks` file safe — losing it means you can no longer update an
already-installed app):

```bash
keytool -genkeypair -v \
  -keystore inkride-release.jks \
  -alias inkride \
  -keyalg RSA -keysize 2048 -validity 10000
```

You will be asked for a keystore password, a key password, and certificate details.

### Add the secrets to GitHub

Encode the keystore as base64 and copy the value:

```bash
base64 -i inkride-release.jks | pbcopy   # macOS
# base64 -w0 inkride-release.jks          # Linux
```

In the GitHub repo go to **Settings → Secrets and variables → Actions** and add
four repository secrets:

| Secret name         | Value                                            |
|---------------------|--------------------------------------------------|
| `KEYSTORE_BASE64`   | base64-encoded contents of `inkride-release.jks` |
| `KEYSTORE_PASSWORD` | the keystore password                            |
| `KEY_ALIAS`         | `inkride` (or the alias you chose)               |
| `KEY_PASSWORD`      | the key password                                 |

That's it — the next merge to `main` produces a signed release.

## Building a signed release locally (optional)

Copy the template and fill in your values (this file is git-ignored):

```bash
cp keystore.properties.example keystore.properties
```

```properties
storeFile=/absolute/path/to/inkride-release.jks
storePassword=********
keyAlias=inkride
keyPassword=********
```

Then build:

```bash
./gradlew assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/`.

> If neither the environment variables (CI) nor `keystore.properties` (local) are
> present, `assembleRelease` falls back to debug signing so the project still builds.
> A debug-signed APK is fine for testing but **must not** be distributed as a release.
