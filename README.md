# Waseem AI Assistant 🤖📱

**Waseem AI Assistant** is an autonomous offline AI Agent for Android that controls your device using voice or text commands. It combines **On-Device LLM (MediaPipe Tasks GenAI)**, **Shizuku API (for privileged system settings)**, **Accessibility Service (for autonomous screen clicks & typing)**, and a **Jetpack Compose Floating Overlay**.

- **Developer:** Waseem Asif
- **Email:** [wasimasif761@gmail.com](mailto:wasimasif761@gmail.com)
- **Package Name:** `com.waseemasif.aiagent`
- **License:** MIT License

---

## 🌟 Key Features (اہم خصوصیات)

1. **🗣️ Offline Voice & Text Control:**
   - Floating on-screen cyber bubble overlay that listens to offline speech commands or keyboard input.
   - Built with modern Jetpack Compose inside Android's WindowManager.

2. **🧠 On-Device MediaPipe LLM:**
   - Powered by Google MediaPipe Tasks GenAI (`gemma2-2b-it-gpu-int4.bin` or compatible `.task`/`.bin` models).
   - Constrained JSON intent parsing with deterministic offline local fallback.

3. **⚡ Shizuku Privileged Shell Automation:**
   - Toggles system settings directly without root: Wi-Fi (`svc wifi enable/disable`), Bluetooth (`cmd bluetooth_manager`), Mobile Data (`svc data`), Airplane mode, Volume, and Brightness.

4. **👆 Accessibility Screen Automation:**
   - `PhoneControlService` finds on-screen buttons, text, and view IDs to execute `ACTION_CLICK` and `ACTION_SET_TEXT` automatically.
   - System navigation: Home, Back, Lock Screen, Screenshot, Notifications.

5. **📊 Interactive Control Center:**
   - System permission health checklist (Overlay, Mic, Accessibility, Shizuku).
   - Interactive command terminal with live JSON inspection and execution audit log.

---

## 🚀 How to Push to GitHub & Get APK (GitHub پر کوڈ اپلوڈ کرنے اور APK حاصل کرنے کا طریقہ)

### 1️⃣ Push Code to GitHub (گٹ ہب پر پش کریں)

Apne computer ya terminal me ye commands run karein:

```bash
# 1. Initialize git (agar pehle se nahi hai)
git init

# 2. Files add karein
git add .

# 3. Commit karein
git commit -m "Initial commit of Waseem AI Assistant"

# 4. Main branch set karein
git branch -M main

# 5. Apna GitHub repository link add karein
git remote add origin https://github.com/<YOUR_GITHUB_USERNAME>/<YOUR_REPO_NAME>.git

# 6. Push karein
git push -u origin main
```

---

### 2️⃣ Automatic APK Build on GitHub (گٹ ہب پر آٹومیٹک APK بننا)

Is project me **GitHub Actions Workflow** (`.github/workflows/build-apk.yml`) pehle se set hai:
1. Jab aap GitHub par `git push` karenge, GitHub Actions automatically APK build karna shuru kar dega.
2. Apne GitHub repository me **"Actions"** tab par jayein.
3. Wahan **"Build Android APK"** workflow run hota hua nazar aayega (~2-3 minutes).
4. Build complete hone par **Artifacts** section me **`Waseem-AI-Assistant-APK`** file available hogi jise aap direct mobile me download kar sakte hain!

---

### 3️⃣ Build APK Locally in Android Studio / Terminal (کمپیوٹر پر APK بنانے کا طریقہ)

Agar aap apne computer par APK generate karna chahte hain:

```bash
# Debug APK bananey ke liye:
./gradlew assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

Ya **Android Studio** me project open karein:
- **Build** menu > **Build Bundle(s) / APK(s)** > **Build APK(s)**

---

## 📱 Mobile Phone Setup & Permissions (موبائل میں سیٹ اپ اور پرمیشنز)

APK install karne ke baad:

1. **Display Over Other Apps (Overlay):**
   - App open karein aur "Grant" par tap karein taake floating bubble screen par show ho sakay.
2. **Microphone Access:**
   - Voice commands ke liye Mic permission allow karein.
3. **Accessibility Service:**
   - Mobile Settings > Accessibility > **Waseem AI Screen Controller** ko **ON** karein taake agent screen par buttons click aur type kar sakay.
4. **Shizuku (System Toggles):**
   - Play Store se **Shizuku** app install karein aur Wireless Debugging ke zariye start karein. Phir Waseem AI app me "Authorize" par tap karein.
5. **(Optional) MediaPipe LLM Model:**
   - Gemma 2B model (`gemma2-2b-it-gpu-int4.bin`) ko mobile ke `/sdcard/Download/` ya app ke `filesDir` me rakh kar Dashboard se load kar sakte hain. Model file na hone par app ka built-in fast agent automatically commands process karega!

---

## 📜 License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.  
Copyright (c) 2026 **Waseem Asif** (`wasimasif761@gmail.com`).
