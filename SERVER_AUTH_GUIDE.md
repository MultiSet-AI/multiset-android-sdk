# Authenticating Through Your Own Server

The Quick Start gives the SDK your `clientId` and `clientSecret`. Both then ship inside your app,
where anyone who unpacks it can read the secret. This guide moves the secret to your server:
your server gets MultiSet access tokens, your app fetches them from your own API, and the SDK
sends them on every MultiSet request.

Requires MultiSet Android SDK 2.2.0 or later.

## How it works

```
Your app ──── your sign-in ───► Your API ──── client ID + secret ───► MultiSet  POST /v1/m2m/token
Your app ◄────── token ──────── Your API ◄──── {token, expiresOn} ────────────┘

MultiSet SDK ──── Authorization: Bearer <token> ───► MultiSet API   (every request)
```

You give the SDK a **token provider**: a suspending function that returns a token. The SDK calls
it when it starts, shortly before the current token expires, and when MultiSet refuses a token.
How the function gets the token is up to you: any URL, method, headers, response format or
networking library.

## 1. Server: hand out tokens

Your server requests a token from MultiSet with your client ID and secret:

```bash
curl -X POST https://api.multiset.ai/v1/m2m/token -u "$MULTISET_CLIENT_ID:$MULTISET_CLIENT_SECRET"
# {"token": "<JWT>", "expiresOn": "2026-11-03T11:36:01.356Z"}
```

Expose an endpoint that hands one to your signed-in users. A minimal example in Node.js 18+ with
Express:

```js
const express = require("express");

const app = express();
app.use(express.json());

const basicAuth = Buffer.from(
  `${process.env.MULTISET_CLIENT_ID}:${process.env.MULTISET_CLIENT_SECRET}`
).toString("base64");
const FIVE_MINUTES = 5 * 60 * 1000;
let cached = null; // { token, expiresOn }

async function newMultiSetToken() {
  const response = await fetch("https://api.multiset.ai/v1/m2m/token", {
    method: "POST",
    headers: { Authorization: `Basic ${basicAuth}` },
  });
  if (!response.ok) throw new Error(`MultiSet token request failed: HTTP ${response.status}`);
  cached = await response.json();
  return cached;
}

// requireSignedInUser is your own sign-in middleware.
app.post("/multiset/token", requireSignedInUser, async (req, res) => {
  try {
    const stillGood = cached && Date.parse(cached.expiresOn) - Date.now() > FIVE_MINUTES;
    res.json(req.body?.forceNew || !stillGood ? await newMultiSetToken() : cached);
  } catch {
    res.status(502).json({ error: "MultiSet token unavailable" });
  }
});
```

Rules for the endpoint:

- **Keep the secret on the server**, in an environment variable or a secret manager. Never send
  it to the app.
- **Require your own sign-in, over HTTPS.** Whoever holds a token can call the MultiSet API as
  your account until it expires.
- **Caching is fine.** Hand out a new token once the cached one has less than 5 minutes left,
  and always a new one when the app asks after a refusal (`forceNew` above).
- **Use the app's environment.** Request tokens from the host that the app's
  `MultiSetSDKConfig.baseUrl` points at. That is `https://api.multiset.ai` unless you changed it.

## 2. App: give the SDK a token provider

Remove `clientId` and `clientSecret` from the app and pass a `MultiSetTokenProvider` instead:

```kotlin
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.MultiSetSDKConfig
import com.multiset.sdk.MultiSetTokenProvider
import com.multiset.sdk.MultiSetTokenRequestReason
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

val tokenProvider = MultiSetTokenProvider { reason ->
    val forceNew = reason == MultiSetTokenRequestReason.REJECTED
    val request = Request.Builder()
        .url("https://api.example.com/multiset/token")
        .header("Authorization", "Bearer ${MyAppSession.accessToken}")
        .post("""{"forceNew":$forceNew}""".toRequestBody("application/json".toMediaType()))
        .build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Token endpoint answered HTTP ${response.code}")
        JSONObject(response.body!!.string()).getString("token")
    }
}

val config = MultiSetSDKConfig.Builder(tokenProvider)
    .mapCode("YOUR_MAP_CODE")
    .build()
MultiSetSDK.initialize(applicationContext, config, callback)
```

`MyAppSession.accessToken` stands for however your app proves its user to your API. You can
also implement `MultiSetTokenProvider` in a class of your own. It is a suspending interface, so
implement it in Kotlin.

The SDK passes a reason with each call:

| Reason | When the SDK asks | What to return |
|---|---|---|
| `INITIAL` | `MultiSetSDK.initialize` | A valid token |
| `EXPIRING` | The first request in the last minutes of the token's life | A valid token; a cached one is fine |
| `REJECTED` | MultiSet refused the current token with HTTP 401 | A newly issued token, not a cached one |

- **Return only the token string.** The expiry is inside the token (the JWT's `exp` claim) and
  the SDK reads it from there. Your API may return `expiresOn` too; the app doesn't need it.
- A request refused with a 401 is retried once with the new token. Returning the refused token
  again fails the request instead.
- Requests made at the same time share one call to your provider.
- The provider runs on a background thread, so a blocking call like the one above is fine. A
  call that takes longer than 20 seconds fails; give your request a timeout of its own too.
- Build the provider once, or make it a data class. Calling `initialize` again with an equal
  provider, as an activity recreated by a rotation does, keeps the token it already gave.

## 3. Handle failures

- **At startup:** if the provider throws or returns an empty string,
  `onAuthenticationFailure(error)` receives `"Token provider failed: …"`. To retry, call
  `MultiSetSDK.initialize` again.
- **Later:** a failed renewal fails only the request that needed the new token. The next request
  asks your provider again, and a map upload retries it like a dropped connection.

## Checklist

- [ ] `clientId` and `clientSecret` are gone from the app, its configuration and its repository
- [ ] The token endpoint requires your sign-in and uses HTTPS
- [ ] `REJECTED` gets a newly issued token
- [ ] The provider's request has a timeout
- [ ] The provider is built once, or is a data class, so a rotation keeps the token

## Try it in the sample apps

Set `MULTISET_TOKEN_SERVER_URL` in `multiset.properties` to an endpoint that answers a GET with
`{"token": "<JWT>"}`, and rebuild `:app` or `:xreal-aura-vps`. Each sample's
`TokenServerProvider` adds `reason=initial`, `expiring` or `rejected` to the URL, and the sample
then never uses its `MULTISET_CLIENT_ID` or `MULTISET_CLIENT_SECRET`. A plain `http://` endpoint
works only in debug builds.
