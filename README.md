# Lumina A55 Studio

Editorial computational-photography camera app crafted for the **Samsung Galaxy A55 5G** (Exynos 1480 + AMD Xclipse 530 RDNA2 GPU, Android 14+/API 33+).

## Architecture (`v5.0.0`)

- **Physical sensors:** 50 MP Sony IMX906 (OIS, f/1.8), 12 MP IMX258 ultrawide (f/2.2), 5 MP macro (f/2.4), 32 MP IMX616 front (f/2.2).
- **Samsung One UI Camera2 reality (unrooted):** Samsung disables `RAW_SENSOR` (DNG) and restricts full Camera2 hardware level on the unrooted A55.
- **Lumina A55 Studio solution:**
  1. **Multi-Frame YUV Burst Stacking (`MultiFrameBurstProcessor`)** — captures and aligns `YUV_420_888` frames with tile-shift alignment and ghost rejection before JPEG encoding to suppress shadow noise and protect highlights without needing root/RAW.
  2. **CameraX Vendor Extensions + Physical Lens Mapper (`A55CameraInspector`)** — enumerates every camera ID and extension (`HDR`, `NIGHT`, `BOKEH`, `AUTO`) exposed by the device ROM and activates `0.5x`, `1x`, `2x`, `Macro` (when exposed), and `32MP` front.
  3. **Offline Context & Concept Engine (`SceneConceptEngine` + `PhotometricAnalyzer` + `NeuralStudioEngine`)** — combines ML Kit Face Detection, Image Labeling, Multi-Class Selfie Segmentation, MiDaS depth CoC (`1–3px` subtle lens separation), Zero-DCE low-light gain, and green fluorescent cast detection.
  4. **Xclipse 530 GPU AGSL `RuntimeShader` (`EditorialShaderEngine`)** — applies Capture One-style **OKLab Luminance (`L`)** dehaze + filmic S-curve (preserving melanin skin chromatic ratios without orange shifts), Skin Hue Lock (`15°–42°`), fluorescent green-to-magenta midtone neutralization, selective frequency-separated sharpness restricted to eyes/glasses/fabric, GPU `Black Pro-Mist 1/8` (`L > 0.84` highlight bloom), melanin-safe Silver Gelatin B&W (`0.45R + 0.45G + 0.10B`), and natural radial lens vignette.
  5. **5 Real Photographer Presets:** `Editorial Limpo`, `Luz Natural`, `Golden Film`, `Cinema`, `P&B Prata` (+ `Auto Banca`).
  6. **Optional Online Studio (5-Block Prompt + Auditor Anti-IA):** Selectable direction styles (`Ajuste Sutil`, `Estúdio 85mm`, `Cinema 35mm`), Vision quality gate scoring realism (automatic 1-retry if `< 8/10`), and transparent fallback to local `Offline · Editorial Limpo` when an API key lacks image generation quota (`HTTP 429`).

## Build & Test

```bash
./gradlew testDebugUnitTest assembleRelease
```

## License
[MIT](LICENSE) — Emerson Almeida (`@deusbr`)
