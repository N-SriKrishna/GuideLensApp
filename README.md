# GuideLensApp: AI-Powered Visual Navigation for Android

**GuideLensApp** is a smart navigation assistant built as a **native Android application**.  
It leverages **on-device computer vision** to understand a user's surroundings in real time.  
The app detects a target object (e.g., a chair), identifies walkable areas, calculates a safe path, and provides intuitive **turn-by-turn directions** using augmented reality overlays on the camera feed.

This project demonstrates the power of **TensorFlow Lite** and **ONNX Runtime** for complex, real-time AI tasks on mobile devices.

---

## 🚀 Core Features

- **Real-time Object Detection**  
  Uses a **YOLO World v2** model to detect and track user-defined target objects in the live camera feed.

- **Safe Floor Segmentation**  
  Utilizes **PP-LiteSeg** (INT8 quantized) to semantically segment the environment, identifying walkable areas for safe navigation with optimized performance.

- **Intelligent Pathfinding**  
  Implements **A\*** search on the segmented floor map to calculate the most efficient, obstacle-free path to the target.

- **Pure Pursuit Navigation**  
  Converts the A\* path into intuitive navigation commands like `"Move Forward"` or `"Turn Slightly Left"`.

- **Augmented Reality Overlays**  
  Displays the detected target, safe floor area, and planned route in real time on the camera feed.

---

## 🛠️ Technology Stack

| Component              | Technology Used                                            |
|------------------------|-----------------------------------------------------------|
| **Platform**           | Android (API 24+)                                         |
| **Language**           | Kotlin (primary)                                          |
| **UI Framework**       | Jetpack Compose                                           |
| **ML Frameworks**      | ONNX Runtime                                              |
| **Object Detection**   | YOLO World v2 (.onnx) via ONNX Runtime Android            |
| **Floor Segmentation** | PP-LiteSeg INT8 (.onnx) via ONNX Runtime Android          |
| **Image Processing**   | Android Graphics & Custom Kotlin                          |
| **Pathfinding**        | Custom A* & Pure Pursuit implementation in Kotlin         |
| **Camera Handling**    | Android CameraX                                           |
| **Concurrency**        | Kotlin Coroutines                                         |
| **Architecture**       | MVVM (Model-View-ViewModel)                               |

---

## 🔬 Model Conversion Journey

The app uses two different ML runtimes optimized for their respective tasks.

### 🧠 Object Detection Model (YOLO World v2)
**Pipeline:**  
`PyTorch → ONNX → TensorFlow SavedModel → Quantized ONNX (INT8)`

### 🌐 Floor Segmentation Model (PP-LiteSeg)
**Pipeline:**  
`PaddlePaddle → ONNX → Quantized ONNX (INT8)`

---

## ⚙️ Key Optimizations & Benefits

1. **INT8 Quantization**  
   The PP-LiteSeg model is quantized to INT8, resulting in:
   - **4× smaller model size** (reduced memory footprint)  
   - **2–3× faster inference** on mobile devices  
   - Minimal accuracy loss for floor segmentation tasks  

2. **ONNX Runtime Integration**  
   ONNX Runtime provides:
   - Native INT8 acceleration support  
   - Optimized mobile execution providers  
   - Better performance for quantized models compared to TFLite  

---

## 🧩 Dependencies

```text
# Object Detection
tensorflow==2.12.0

# Floor Segmentation
onnxruntime-android==1.16.0
onnx==1.12.0
