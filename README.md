# GuideLensApp: AI-Powered Visual Navigation for Android

**GuideLensApp** is a smart navigation assistant built as a **native Android application**. It leverages **on-device computer vision** to understand a user's surroundings in real-time. The app detects a target object (e.g., a chair), identifies walkable areas, calculates a safe path, and provides intuitive **turn-by-turn directions** using augmented reality overlays on the camera feed.

This project demonstrates the power of **TensorFlow Lite** for complex, real-time AI tasks on mobile devices.

---

## 🚀 Core Features

- **Real-time Object Detection**  
  Uses a **YOLO World v2** model to detect and track user-defined target objects in the live camera feed.

- **Safe Floor Segmentation**  
  Utilizes **DeepLabV3** to semantically segment the environment, identifying walkable areas for safe navigation.

- **Intelligent Pathfinding**  
  Implements **A\*** search on the segmented floor map to calculate the most efficient, obstacle-free path to the target.

- **Pure Pursuit Navigation**  
  Converts the A\* path into intuitive navigation commands like `"Move Forward"` or `"Turn Slightly Left"`.

- **Augmented Reality Overlays**  
  Displays the detected target, safe floor area, and planned route in real-time on the camera feed.

---

## 🛠️ Technology Stack

| Component              | Technology Used                                            |
|------------------------|-----------------------------------------------------------|
| Platform               | Android (API 24+)                                        |
| Language               | Kotlin (primary)                                         |
| UI Framework           | Jetpack Compose                                          |
| ML Framework           | TensorFlow Lite                                          |
| Object Detection       | YOLO World v2 (.tflite)                                  |
| Floor Segmentation     | DeepLabV3 (.tflite) via TFLite Task Vision Library       |
| Image Processing       | Android Graphics & Custom Kotlin                          |
| Pathfinding            | Custom A* & Pure Pursuit implementation in Kotlin        |
| Camera Handling        | Android CameraX                                          |
| Concurrency            | Kotlin Coroutines                                        |
| Architecture           | MVVM (Model-View-ViewModel)                              |

---

## 🔬 Model Conversion Journey

The original **PyTorch models** were not natively supported on Android, so a careful **conversion pipeline** was needed:

**Pipeline:**  
`PyTorch → ONNX → TensorFlow SavedModel → TensorFlow Lite (.tflite)`

### Key Challenges & Solutions

1. **Dependency Conflicts**  
   Solved by creating a clean Python virtual environment with pinned versions:
   ```text
   tensorflow==2.12.0
   onnx==1.12.0
   onnx-tf==1.10.0
