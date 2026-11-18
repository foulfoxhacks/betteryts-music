# Turning on "Connect with Google"

BetterYTS Music can import a user's YouTube / YouTube Music playlists, liked songs and
subscriptions. It's optional, read-only (`youtube.readonly`), and works without Google Play
services (stock Android, microG and de-Googled phones alike), because sign-in runs in the
browser through the BetterYTS Worker.

Until the two secrets below exist, the Worker answers `503 Google sign-in isn't set up` and the
app shows the error when someone taps **Connect with Google**. Nothing else is affected.

## How it works

```
App ── browser ──▶ yts.foulfoxhacks.dev/auth/google/start ──▶ Google consent
                                                                   │
App ◀── betterytsmusic://oauth?code ◀── /auth/google/callback ◀────┘
 │
 └─ POST /api/auth/google/token {code, code_verifier} ─▶ Worker adds the client secret ─▶ Google
```

- **PKCE**: the code verifier is created on the phone and never leaves it, so an intercepted
  redirect is useless.
- The **client secret** lives only in the Worker (a Cloudflare secret).
- **Tokens** pass through the Worker and are never stored or logged there. On the phone the
  refresh token is encrypted with an Android Keystore key.
- **Disconnect** revokes the token at Google; imported music stays in the app.

## 1. Google Cloud project (you)

1. Go to <https://console.cloud.google.com/> and create a project, e.g. "BetterYTS Music".
2. **APIs & Services → Library**: enable **YouTube Data API v3**.
3. **APIs & Services → OAuth consent screen** (Google Auth Platform):
   - User type **External**. App name "BetterYTS Music", support email, logo (optional).
   - Authorized domain: `foulfoxhacks.dev`.
   - App home page `https://yts.foulfoxhacks.dev`, privacy policy URL (see step 4).
   - Scopes: add `https://www.googleapis.com/auth/youtube.readonly`.
   - While the app is in **Testing**, add your own Google account under **Test users**.
4. **Credentials → Create credentials → OAuth client ID**:
   - Application type: **Web application** (not Android: the Worker does the exchange).
   - Authorized redirect URI: `https://yts.foulfoxhacks.dev/auth/google/callback`
   - Copy the **Client ID** and **Client secret**.

## 2. Add the secrets to the Worker

This project uses the `cf` CLI, which can't set a single secret yet, so use Wrangler for this
one command (it reads the Worker name from `--name`; no Wrangler config file is added):

```bash
npx wrangler secret put GOOGLE_CLIENT_ID --name betteryts
npx wrangler secret put GOOGLE_CLIENT_SECRET --name betteryts
```

Each command prompts for the value, so it never lands in shell history. Setting a secret
publishes a new version of the Worker with the code that's currently deployed, so deploy the
Worker version that has the `/auth/google/*` routes first (`npx cf deploy`).

## 3. Try it

Open the app → **Library** → **Connect with Google** → sign in with a test user → back in the
app → **Import**. With the app in Testing mode Google shows an "unverified app" warning; that's
expected.

## 4. Before other people can use it

- **Privacy policy.** Google (and the Play Store) require one at a public URL. It should say:
  the app reads YouTube playlists, liked videos and subscriptions only to copy them into the
  on-device library; nothing is stored on BetterYTS servers; tokens stay on the phone;
  disconnecting revokes access.
- **Verification.** `youtube.readonly` is a *sensitive* scope. Unverified, the app is limited
  to 100 users and shows a warning. Verification asks for the privacy policy, domain ownership
  (`foulfoxhacks.dev`), and a short video showing the sign-in and what the data is used for.
- **Quota.** The project gets 10,000 YouTube API units a day across all users. An import costs
  about 1 unit per 50 items (a typical library: 10–60 units), so ~150+ imports a day fit.
  Quota errors show as "YouTube's daily limit for this app has been reached".
