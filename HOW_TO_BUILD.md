# How to Build Amrita Global Enterprise Attendance APK

## Prerequisites
- Android Studio (latest version) — https://developer.android.com/studio
- JDK 17 or higher
- Android SDK (API 34)

## Steps to Build APK

### Option 1: Android Studio (Recommended)
1. Open Android Studio
2. Click **File → Open** and select the `AttendanceApp` folder
3. Wait for Gradle sync to complete
4. To add your company logo:
   - Replace `res/drawable/ic_logo_placeholder.xml` with your logo PNG file named `ic_logo_placeholder.png`
5. Go to **Build → Build Bundle(s) / APK(s) → Build APK(s)**
6. APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### Option 2: Command Line
```bash
cd AttendanceApp
./gradlew assembleDebug
```
APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (Signed)
1. Go to **Build → Generate Signed Bundle / APK**
2. Choose APK, create or use existing keystore
3. Select release build variant
4. APK output: `app/build/outputs/apk/release/app-release.apk`

## Adding Your Company Logo
- Place your logo image as `app/src/main/res/drawable/ic_logo_placeholder.png`
- Recommended size: 44x44dp (about 132x132px for xxhdpi)
- Transparent background PNG works best

## App Features
- Employee dropdown (Vivek, Archie, Test)
- Check In / Lunch Out / Lunch In / Check Out radio buttons (disabled until employee selected)
- Live IST clock with date (starts only after employee selected)
- GPS location with latitude/longitude + address + embedded map
- Selfie camera capture
- Settings page: EspoCRM Attendance URL, Employee Master URL, API Key
- Submit attendance to EspoCRM via REST API with multipart form data

## Permissions Required
- CAMERA
- ACCESS_FINE_LOCATION
- INTERNET
