# Find Us

A small Android app for two people to see each other on one map.

- **Private by design:** each location is encrypted on the phone (AES-256-GCM) before it's
  uploaded. The key exists only on your two phones. It travels inside the invite code and is
  never sent to the server. Firebase only ever stores ciphertext.
- **Locked to two phones:** the database rules let a pair have at most two members. After the
  second person joins, nobody else can join, even someone who has the invite code.
- **No accounts:** each phone signs in anonymously. You don't need an email, a phone number or a
  Google login.
- **Free map:** it uses OpenStreetMap, so there's no Maps API key and no billing.
- **You choose when to share:** location is only sent while "Sharing on" is active. A notification
  shows while it's on, and you can stop it from there in one tap. Turning sharing off deletes
  your last location from the server.

## What you need

- [Android Studio](https://developer.android.com/studio) (free)
- A free Firebase project. The free "Spark" plan is plenty for two people.
- Two Android phones running Android 8.0 or newer

## One-time setup (about 10 minutes)

### 1. Create the Firebase project

1. Go to <https://console.firebase.google.com> → **Create a project** (Google Analytics is not
   needed).
2. **Add app → Android**, package name **`app.findus`**. Download **`google-services.json`** and
   put it in `findus/app/google-services.json`. It's git-ignored, so it won't be committed.
3. **Build → Authentication → Get started → Sign-in method → Anonymous → Enable.**
4. **Build → Firestore Database → Create database** (pick a location near you, start in
   *production mode*).
5. In Firestore, open the **Rules** tab, replace everything with the contents of
   [`firebase/firestore.rules`](firebase/firestore.rules), and click **Publish**.
   *This step is what keeps your data private, so don't skip it.*

### 2. Build and install

1. In Android Studio choose **File → Open** and select the `findus` folder.
2. Plug in a phone with [USB debugging](https://developer.android.com/studio/debug/dev-options)
   on and press **Run ▶**. Do the same for the second phone.
   You can also use **Build → Build APK(s)** and send the APK to the other phone to install.

### 3. Pair your phones

1. On both phones, open Find Us and type your name.
2. On phone 1, tap **Start a new pair**, then **Send** to send the invite code to your partner
   (for example by text message).
3. On phone 2, copy the code, tap **Paste**, then **Join**.
4. On each phone, tap **Share my location** and allow location and notifications.

You'll now see each other on the map. The buttons on the right show both of you, centre on
you, or open directions to your partner in your maps app. The card at the top shows when your
partner's location was last updated, how far away they are, and their battery level.

## Good to know

- **Updates** are sent about every 30 seconds while sharing is on, even with the app closed. This
  uses some battery. Turn sharing off when you don't need it.
- **After a phone restart**, open the app once to resume sharing. Android doesn't let location
  sharing start silently in the background.
- **Reinstalling the app or clearing its data** removes that phone's key and anonymous account. If
  that happens, one of you taps **⋮ → Leave pair**, and you pair again with a new code.
- **Treat the invite code like a password** until your partner has joined. After that it can't be
  used to join the pair.
- **Map tiles** come from OpenStreetMap's free servers, which is fine for personal use.

## How it works

```
Phone A ──encrypt(location, key)──▶ Firestore pairs/{id}/locations/{uidA} ──▶ Phone B decrypts
                                    (members: [uidA, uidB], ciphertext only)
```

| File | What it does |
| --- | --- |
| `app/src/main/java/app/findus/data/PairCrypto.kt` | Encryption and the invite code format |
| `app/src/main/java/app/findus/data/PairRepository.kt` | Anonymous sign-in, creating/joining a pair, live updates |
| `app/src/main/java/app/findus/location/SharingService.kt` | Foreground service that sends your location |
| `app/src/main/java/app/findus/ui/` | Setup screens and the map |
| `firebase/firestore.rules` | Server-side access rules |

Run the unit tests (encryption and invite codes) with `./gradlew test`.

To test the security rules against the Firebase emulator (needs Node.js and Java), run
`cd firebase/rules-test && npm install && npm test`.
