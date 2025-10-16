# 📱 GuideLensApp: AI-Powered Visual Navigation for Android

**GuideLensApp** is an accessibility-focused on-device navigation system that helps visually impaired users navigate indoor environments independently.  
Built entirely in **Kotlin** with **Jetpack Compose**, it combines real-time **object detection**, **semantic floor segmentation**, and **intelligent pathfinding** to deliver turn-by-turn **audio guidance** through an intuitive AR interface — all processed locally on the device.

---

## 🌟 Why GuideLensApp?

✅ **100 % On-Device** — No internet required, full privacy  
✅ **Audio-First Design** — Text-to-Speech announcements for all navigation events  
✅ **Production-Ready** — Device-adaptive configuration, robust error handling  
✅ **Optimized Performance** — INT8 quantization, NNAPI acceleration, 15–20 FPS  
✅ **Open Source** — GPL-3.0 license, fully documented and community-driven  

---

## 🚀 Core Features

### 🧠 Computer Vision & Navigation

- **Real-Time Object Detection** – YOLO World v2 (INT8) detects 80 object classes at 640×640 resolution with 150–250 ms latency.  
  Supports 16 navigable targets: `chair, door, table, bed, couch, toilet, sink, refrigerator, stairs, person, bottle, cup, laptop, phone, keyboard, mouse`.

- **Semantic Floor Segmentation** – Custom-trained **PP-LiteSeg (INT8)** identifies walkable surfaces at 256×256 resolution, achieving 4× smaller size and 2–3× faster inference than FP32.

- **Intelligent Pathfinding** – **A\*** search on a down-sampled 15 px grid with 1-cell obstacle inflation.  
  Computes optimal paths in 20–40 ms with guaranteed termination (≤ 10 000 iterations).

- **Pure Pursuit Control** – Robotics-grade trajectory tracking with 100 px look-ahead; generates natural commands:  
  *“Move straight forward”*, *“Turn slightly left”*, *“Turn sharply right”*.

---

## ♿ Accessibility Features

- **Text-to-Speech Integration**
  - “Navigating to [object]” on start  
  - “[Object] found” on first detection  
  - Turn commands every 3 s (max)  
  - “Arrived at destination” on goal  
  - “Navigation stopped” on exit

- **Scene Description** – On-demand environment summary:  
  *“I can see 2 chairs, 1 table, and 1 door.”*

- **Voice Command Framework (Ready)** – Architecture supports:  
  *“Navigate to [object]”*, *“What do you see?”*, *“Read text”*, *“Stop navigation”*

- **Augmented Reality Overlays** – For sighted guides:  
  Green floor masks (60 % alpha), bounding boxes, cyan path lines, red target crosshairs.

---

## 🛠️ Technology Stack

| Component | Technology | Details |
|------------|-------------|----------|
| **Platform** | Android API 24+ | Nougat 7.0 and later |
| **Language** | Kotlin 100 % | Modern coroutines-based |
| **UI** | Jetpack Compose | Material Design 3 UI |
| **Architecture** | MVVM | `ViewModel`, `StateFlow` separation |
| **ML Runtime** | ONNX Runtime 1.16.0 | Cross-platform INT8 optimized |
| **Object Detection** | YOLO World v2 (INT8) | 80 classes (~10 MB), NNAPI accel |
| **Segmentation** | PP-LiteSeg (INT8) | Custom trained (~3 MB) CPU opt |
| **Camera** | CameraX 1.3+ | `STRATEGY_KEEP_ONLY_LATEST` |
| **Concurrency** | Kotlin Coroutines | Custom dispatchers + ThreadPoolExecutor |

**Model Pipeline**

- **YOLO World v2** → PyTorch → ONNX → INT8 Quantization  
- **PP-LiteSeg** → Custom PyTorch Training → ONNX → INT8 Quantization  

**Why ONNX Runtime?**  
15–20 % faster INT8 inference than TFLite, superior NNAPI integration, cross-platform portability.

---

## 🧭 Algorithms & Implementation

### 🗺️ A\* Pathfinding
- Manhattan-distance heuristic, 8-directional movement (straight = 10, diagonal = 14)  
- 1-cell obstacle inflation for safety margins  
- BFS fallback for invalid start/goal positions  
- Executes in 20–40 ms on 85×48 grid  

### 🔄 Pure Pursuit Controller
- Curvature κ = 2 × sin α / L (100 px look-ahead)  
- Thresholds: sharp > 0.05, slight > 0.02  
- Generates discrete commands for TTS clarity  
- Stable convergence without oscillation  

---

## ⚙️ Device-Adaptive Configuration

| Tier | FPS | Resolution | ML Threads | Acceleration |
|------|-----|-------------|-------------|---------------|
| **High-End** (≥ 8 GB RAM, ≥ 8 cores) | 20 | 1280×720 | 4 | NNAPI + FP16 |
| **Mid-Range** (4–6 GB RAM) | 15 | 960×540 | 2 | CPU only INT8 |

Dynamic profiling adjusts thresholds, frame rates, and resolution at runtime.

---

## 📦 Installation

### Prerequisites
- Android Studio Hedgehog (2023.1.1+)  
- Android SDK API 24+  
- Physical device with camera (≥ 4 GB RAM recommended 8 GB)

### Setup
bash
git clone https://github.com/N-SriKrishna/GuideLensApp.git
cd GuideLensApp

## 📦 Installation

### Add ML models to `app/src/main/assets/`
 - yolov8s-worldv2_int8.onnx (~10 MB)
 - floor_segmentation_int8.onnx (~3 MB)

### Build & Run
1. **File → Sync Project with Gradle**  
2. **Build → Make Project**  
3. **Run → Run 'app'** (grant camera permission)

---

## ▶️ Usage

### Basic Navigation
1. Tap ⚙️ to select target object (e.g. *Chair*)  
2. Tap “Start Navigation” → Hear “Navigating to chair”  
3. Follow audio commands: “Turn slightly left”, “Move forward”  
4. Arrival → “Arrived at destination”  
5. Stop → “Navigation stopped”  

**Scene Description** – Tap 📄 to hear object summary  
**Visual Overlays** – Green floors, cyan paths, bounding boxes, red crosshair  

---

## ⚙️ Performance & Optimization

### Benchmarks (Samsung Galaxy S23, Snapdragon 8 Gen 2)
| Component | Latency | Notes |
|------------|----------|-------|
| Object Detection | 150–250 ms | YOLO World INT8 + NNAPI |
| Floor Segmentation | 80–120 ms | PP-LiteSeg INT8 |
| Path Planning | 20–40 ms | A* on 85×48 grid |
| End-to-End | 250–400 ms | 2.5–4 FPS pipeline |

Memory ≈ **250 MB (active)** · Battery ≈ **15–20 % per hour**

### Key Optimizations
- **INT8 Quantization** → 4× smaller models, 2–3× faster inference  
- **NNAPI Acceleration** → 2× speedup on Snapdragon NPUs  
- **Frame Throttling** → Adaptive 50–67 ms intervals  
- **Memory Pooling** → Bitmap reuse + explicit GC  
- **Grid Down-sampling** → 100× fewer cells for A*  

---

## 🏗️ Project Architecture
app/src/main/java/com/example/guidelensapp/
├── MainActivity.kt # Entry, permissions, Compose setup
├── GuideLensApplication.kt # App init, crash handler
├── Config.kt # Device-adaptive settings
├── viewmodel/
│ ├── NavigationViewModel.kt # Pipeline control, TTS integration
│ └── NavigationUiState.kt # UI state (StateFlow)
├── ml/
│ ├── ObjectDetector.kt # YOLO World ONNX inference
│ └── ONNXFloorSegmenter.kt # PP-LiteSeg ONNX inference
├── sensors/
│ ├── SpatialTracker.kt 
├── navigation/
│ └── PathPlanner.kt # A* + Pure Pursuit
├── accessibility/
│ └── TextToSpeechManager.kt # TTS wrapper
├── utils/
│ ├── ThreadManager.kt # Thread pools (ML thread max priority)
│ └── MemoryManager.kt # Bitmap pool + GC control
└── ui/composables/
├── CameraView.kt # CameraX integration
├── OverlayCanvas.kt # AR visualization
└── ObjectSelectorView.kt # Target selection dialog

**Data Flow:**  
Camera Frame → ViewModel → [SensorTracker + ObjectDetector → FloorSegmenter → PathPlanner] → TTS → StateFlow → Compose UI

## 🧩 Engineering Highlights

- **Asynchronous Initialization** – Coroutines + custom dispatchers prevent UI blocking  
- **Robust Error Handling** – Fallback modes if models fail to load  
- **Thread Optimization** – Single ML thread avoids context switch overhead  
- **Memory Safety** – WeakReference bitmap pool + forced GC on crash  
- **High Code Quality** – Null-safe Kotlin, clean MVVM separation, structured logging  

---

## 🎯 Future Enhancements

- **Distance Estimation** (“Object 2 m ahead”)  
- **Dynamic Obstacle Avoidance** with real-time re-planning  
- **OCR Text Reading** (ML Kit integration)  
- **Haptic Turn Cues** for tactile feedback  
- **Full Voice Command Support** for hands-free interaction  
- **Multi-Object Waypoints & Navigation History**  
- **Sensor Fusion** for improved heading stability  
- **Model Distillation** for total model size under 5 MB  

---

## 💡 Accessibility & Impact

**GuideLensApp** empowers users with visual impairments to navigate independently using only a smartphone camera — no beacons, maps, or internet required.  
It demonstrates that **real-time, privacy-preserving AI** can be practical on-device, enhancing inclusion and mobility for millions worldwide.

---

## 📚 References & Resources

- [ONNX Runtime Mobile Docs](https://onnxruntime.ai/docs/get-started/with-mobile.html)  
- [Ultralytics YOLO World Docs](https://docs.ultralytics.com/hub/app/android/)  
- [PP-LiteSeg Paper (ArXiv)](https://arxiv.org/html/2504.20976v1)  
- [A* Algorithm – Wikipedia](https://en.wikipedia.org/wiki/A*_search_algorithm)  
- [Pure Pursuit Controller – MathWorks](https://www.mathworks.com/help/robotics/ref/purepursuit.html)  

---

## 🧑‍💻 Author & Repository

**Developer:** Sri Krishna Nurandu  
**Repository:** [github.com/N-SriKrishna/GuideLensApp](https://github.com/N-SriKrishna/GuideLensApp)  
**License:** [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)  

---

## 🏁 Conclusion

**GuideLensApp** showcases the future of **on-device AI navigation** — merging deep learning, classical algorithms, and accessibility design into one cohesive Android application.  
With optimized models, adaptive runtime, and robust engineering, it stands as a **reference implementation for real-time computer-vision navigation** on mobile devices.

