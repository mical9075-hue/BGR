# BG Remover — Offline Android App (Kotlin + U2Net)

Fully offline background remover that works on **people AND general
objects** (products, animals, furniture, anything) — powered by the
U2Net salient-object-detection model via the open-source
[`removebg`](https://github.com/AppcentMobile/removebg) library.

## ✅ Nothing to download

The model ships bundled inside the library dependency itself. Just
open the project in Android Studio and run it.

## Why U2Net instead of the earlier selfie-only model?

The first version of this app used MediaPipe's `selfie_segmenter`,
which is a lightweight, low-resolution (256x256) model built for
real-time video-call background blur — not photo-quality cutouts,
and only trained on selfie-style human framing. That's why edges
looked rough.

**U2Net** is a general-purpose "what's the main subject" model:

- Works on humans **and** arbitrary objects (bags, shoes, pets,
  furniture, products, etc.) — not just faces/selfies
- Much better edge quality out of the box
- Industry-standard — it's the same model used by many popular
  background-removal tools

**On resolution / "4K, HD" output:** the neural network itself runs
at a modest internal size for speed (this is true of every mobile
background-removal app, not just this one), but the resulting alpha
mask is always applied back onto your **full original photo
resolution**. So a 4000×3000 photo in gives a 4000×3000 transparent
PNG out — the output is exactly as sharp as your input image, not
limited by the model's internal working resolution.

This app also adds a light edge-feathering pass (`featherAlphaEdges`
in `MainActivity.kt`) on top of whatever mask the model produces, to
further smooth any residual jagged edges.

## Project kholna

1. Android Studio (Hedgehog ya usse naya version) install karein
2. `File > Open` → is `BGRemoverApp` folder ko select karein
3. Gradle sync hone dein (JitPack se `removebg` library download
   hogi — internet chahiye pehli baar build ke liye, phir app khud
   fully offline chalti hai)
4. Run karein (▶️) — real device ya emulator (API 24+) pe

## App kaise kaam karti hai

- **Gallery / Camera** button se image select karo
- **Remove Background** button dabao — on-device U2Net model
  background hata dega (transparent PNG, checkerboard preview)
- **Hold to Compare** chip ko dabaye rakho to briefly original photo
  dikhega, chhodte hi result wapas aa jayega
- **Save** button se result `Pictures/BGRemover` folder mein save ho
  jayega

## Structure

```
BGRemoverApp/
├── app/
│   ├── build.gradle              # removebg (U2Net) + AndroidX deps
│   ├── src/main/
│   │   ├── AndroidManifest.xml   # permissions (camera, storage)
│   │   ├── java/com/example/bgremover/
│   │   │   └── MainActivity.kt   # sara logic yahan hai
│   │   ├── res/layout/activity_main.xml   # Material 3 UI
│   │   ├── res/drawable/         # icons, gradients, checkerboard
│   │   └── res/mipmap-*/         # launcher icons (placeholder)
├── build.gradle
├── settings.gradle                # includes jitpack.io repo
└── gradle.properties
```

## Further improving quality

- **Even sharper edges (hair strands etc.):** swap in a dedicated
  matting model like MODNet for portraits specifically, or ISNet
  (`isnet-general-use`) for general objects — both are noticeably
  heavier and would need a custom TensorFlow Lite Interpreter
  integration (no ready Android library currently), so only worth it
  if U2Net's results aren't good enough for your use case.
- **Speed:** U2Net's full model is larger (~176MB) than the earlier
  selfie model. If APK size or speed becomes an issue, look for a
  `u2netp` (lightweight, ~4.7MB) variant instead.
- **App icon:** still a placeholder (simple circle). Generate a real
  one via Android Studio's "Image Asset" tool.

## Requirements

- Android Studio Hedgehog (2023.1.1) ya naya
- Kotlin 1.9+
- minSdk 24 (Android 7.0+)
- Internet on first Gradle sync only (to fetch the library from
  JitPack) — the app itself runs 100% offline afterward

## New: Change Background feature

After removing the background, tap **Change Background** to open a
picker with:

- **Colors** — 12 curated solid-color backdrops
- **Gradients** — 8 smooth two-color gradients
- **Online** — free curated stock photos (via Picsum, no API key
  needed) — requires internet
- **From Gallery** — pick any photo from your device as the backdrop
- **Transparent** — quick reset back to the checkerboard cutout

The chosen background is center-crop-scaled to fully cover the
canvas, then your cutout subject is drawn on top. **Save** always
saves whatever is currently showing — transparent PNG if no
background is applied, or the flattened composite if one is.

Files added for this feature:
- `BackgroundOptions.kt` — data model + curated presets
- `BackgroundAdapter.kt` — RecyclerView grid adapter
- `res/layout/bottom_sheet_background.xml` — picker UI
- `res/layout/item_background_swatch.xml` — grid item

Requires `android.permission.INTERNET` (already added to the
manifest) for the Online tab only — everything else works fully
offline.

## 📱 Mobile se APK banana (bina PC ke) — GitHub Actions

Ye project ke andar `.github/workflows/build.yml` already daal diya
hai — GitHub ka free cloud build service use karke APK bana dega,
sab kuch phone se hi.

### Steps:

1. **GitHub account banao** (agar nahi hai) — [github.com](https://github.com)
   phone browser ya GitHub app se signup kar lein (free)

2. **Naya repository banao:**
   - GitHub app kholo ya browser mein github.com
   - "+" → "New repository"
   - Naam do (e.g. `bg-remover-app`), Public/Private koi bhi, Create karo

3. **Is poore `BGRemoverApp` folder ko GitHub pe upload karo:**
   - Sabse aasan tareeqa: GitHub website (mobile browser mein "Desktop site" mode on karke) → apni repo kholo → "Add file" → "Upload files" → is zip ko **pehle extract** karke sari files/folders select karke upload karo
   - (Zip seedha upload nahi hoga, files/folders individually ya drag-drop karni hongi — mobile file manager app se zip extract kar lein pehle, jaise "ZArchiver" ya "Files by Google")
   - Ya phir **Termux** app use karke `git push` bhi kar sakte hain agar comfortable hain command line se

4. **Automatically build shuru ho jayegi:**
   - Repo ke "Actions" tab mein jaake dekho — "Build APK" workflow chal raha hoga (2-5 minute lagte hain)

5. **APK download karo:**
   - Jab workflow complete ho jaye (green tick ✅), usi run ko open karo
   - Neeche "Artifacts" section mein "app-debug-apk" milega — download kar lo
   - Ye ek `.zip` hoga jisme andar `app-debug.apk` hoga — extract karke phone pe install kar lo (Settings → "Install unknown apps" allow karna padega apne file manager/browser ke liye)

### Alternative (agar GitHub thoda mushkil lage):

- **Termux + Gradle app pe hi build:** possible hai lekin bohot heavy/slow setup hai (JDK + Android SDK sab Termux mein install karna), is project ke size/dependencies (MediaPipe removed, ab removebg+Glide) ke sath phone pe crash/slow hone ka risk zyada hai — GitHub Actions zyada reliable hai.
- **AIDE app** (Android IDE) — chhote/simple projects ke liye theek hai, lekin is project ki Gradle dependencies (removebg, Glide, Material3) ke sath compatibility guaranteed nahi — GitHub Actions safest option hai.

**Recommendation: GitHub Actions wala tareeqa use karein** — sabse reliable aur free hai.
