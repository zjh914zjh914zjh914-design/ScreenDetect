# ScreenDetect — 屏幕画面变化监控 App（Android）

监控屏幕画面（如另一个 App 里的监控视频），画面变化幅度超过阈值时播放报警音。

## 技术方案
- **MediaProjection**：屏幕画面采集（每次启动监控会话需系统弹窗授权）
- **OpenCV MOG2 背景建模**：运动检测，自动过滤监控噪点 / 光照缓慢变化
- **ROI 区域检测**：只分析屏幕上监控视频所在矩形区域，降低 CPU 占用
- **灵敏度滑块**：运行时实时调节报警阈值
- **前台服务**：切后台继续运行，常驻通知栏

## 编译（GitHub Actions 自动打包）
1. 新建 GitHub 仓库（如 `ScreenDetect`），分支 `main`
2. 将本目录全部文件上传到仓库根目录（保持目录结构）
3. 每次 push 自动触发 `.github/workflows/build.yml` 编译
4. 编译完成后在仓库 Actions 页面下载产物：`app-release-unsigned.apk`
5. APK 为**未签名**，Android 10+ 无法直接安装，需签名后安装（详见下文）

## 参数调节（无需重新编译）
- **检测区域（ROI）**：App 内点「设置检测区域」，屏幕弹出框选层，拖动移动 / 拖右下角缩放，点保存立即生效（监控运行中也能实时切换），区域自动保存，下次启动沿用
- **灵敏度阈值**：App 内拖动滑块实时调节（越小越灵敏），监控运行中立即生效
- **声音提醒 / 震动提醒**：两个勾选框独立开关，实时生效并自动保存
- **调试面板**：App 内实时显示「变化值 / 当前阈值 / 本轮峰值 / 报警次数」，并在报警时记录时间和变化数值（保留最近 20 条），方便调阈值
- 如需代码级初始值：编辑 `ScreenDetectService.kt` 顶部 `roiRect`（首次运行无保存值时生效）

## 安装到手机注意事项
1. 签名 APK 后安装，需开启"允许安装未知来源应用"
2. 每次点"开始屏幕监控"需系统弹窗授权；会话不中断则无需重复授权
3. **锁屏会中断监控**，保持亮屏（可调最低亮度 + 插电）
4. 国产手机：关闭电池优化、允许后台活动、锁定最近任务，避免被杀进程

## 签名方法（二选一）
### 本地签名（有 JDK）
```bash
keytool -genkey -v -keystore mykey.keystore -alias mykey -keyalg RSA -keysize 2048 -validity 10000
# 下载 Android build-tools 后：
apksigner sign --ks mykey.keystore --ks-key-alias mykey app-release-unsigned.apk
```
### GitHub Actions 内签名
把 keystore 以 base64 存入仓库 Secrets（KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD），在 build.yml 中加入签名步骤即可。
