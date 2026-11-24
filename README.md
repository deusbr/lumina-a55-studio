# Lumina A55 Studio

Editorial computational-photography camera app crafted for the **Samsung Galaxy A55 5G** (Exynos 1480 + AMD Xclipse 530 RDNA2 GPU, Android 14+/API 33+).

## Why This Architecture (Galaxy A55 Hardware Findings)
- **Physical sensors:** 50 MP Sony IMX906 (OIS, f/1.8), 12 MP IMX258 ultrawide (f/2.2), 5 MP macro (f/2.4), 32 MP IMX616 front (f/2.2).
- **Samsung One UI Camera2 reality (unrooted):** Samsung disables `RAW_SENSOR` (DNG) and restricts full Camera2 hardware level on the unrooted A55.
- **Lumina A55 Studio solution:**
  1. **Multi-Frame YUV Burst Stacking (`MultiFrameBurstProcessor`)** — captures and aligns `YUV_420_888` frames with tile-shift alignment and ghost rejection before JPEG encoding to suppress shadow noise and protect highlights without needing root/RAW.
  2. **CameraX Vendor Extensions + Physical Lens Mapper (`A55CameraInspector`)** — enumerates every camera ID and extension (`HDR`, `NIGHT`, `BOKEH`, `AUTO`) exposed by the device ROM and activates `0.5x`, `1x`, `2x`, `Macro` (when exposed), and `32MP` front.
  3. **Offline Context & Concept Engine (`SceneConceptEngine` + `PhotometricAnalyzer`)** — combines ML Kit Face Detection, Image Labeling, Subject Segmentation, and photometric light-direction analysis (backlit, side-lit chiaroscuro, golden hour, low-key night) to choose and tune the editorial recipe automatically.
  4. **Xclipse 530 GPU AGSL `RuntimeShader` (`EditorialShaderEngine`)** — applies ACES Filmic tone mapping, skin-hue vectorscope protection, subject relighting, background negative fill & optical bokeh, Black Pro-Mist bloom, halation, film grain, and optical vignette.

## Build & Test

```bash
./gradlew testDebugUnitTest assembleDebug
```

## Resumo em Português (PT-BR)
App Android nativo (Kotlin + Jetpack Compose + CameraX/Camera2 + ML Kit offline + shaders AGSL na GPU) pensado para extrair o máximo das câmeras do **Samsung Galaxy A55**, entendendo o conceito e a luz de cada cena para entregar fotos com estética de ensaio fotográfico (Portra 400, CineStill 800T, Hasselblad Natural, Gold 200 e Leica Mono).

## License
[MIT](LICENSE) — Emerson Almeida (`@deusbr`)
